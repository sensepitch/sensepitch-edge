package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.sensepitch.edge.OtlpTestSupport.attributes;
import static org.sensepitch.edge.OtlpTestSupport.gunzip;
import static org.sensepitch.edge.TestRequestLogInfo.request;
import static org.sensepitch.edge.TestingParameters.OUT_OF_REACH_MILLIS;
import static org.sensepitch.edge.TestingParameters.TIMEOUT;
import static org.sensepitch.edge.TestingParameters.TIMEOUT_SECONDS;
import static org.sensepitch.edge.TestingParameters.await;

import com.sun.net.httpserver.HttpServer;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.logs.v1.ResourceLogs;
import io.opentelemetry.proto.logs.v1.ScopeLogs;
import io.opentelemetry.proto.resource.v1.Resource;
import io.prometheus.metrics.model.snapshots.HistogramSnapshot;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.zip.Deflater;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/// OTLP/HTTP export of the request log: buffering, batching and retry. The posted bytes are parsed
/// with the official OTLP message classes, which proves the hand written encoding conforms to the
/// schema.
///
/// New OTLP scenarios belong here. Outages on the simulated clock (age limit, breaker, probes)
/// are in [OtlpOutageBDDTest], the place for new outage scenarios; timing details of the sender
/// are in [HttpBatchSenderTest]. [OtlpLogDestinationIT] repeats covered scenarios
/// against real HTTP and timers, using this class as its fixture. The remaining server tests add
/// unit coverage; see `NOT-YET.md` for the fake clock that makes them fast.
///
/// @author Jens Wilke
@Timeout(value = 30, unit = TimeUnit.SECONDS)
// alone: captures the log of all threads, the sender logs from its own
@Isolated
class OtlpLogDestinationTest {

  @RegisterExtension LogCapture logs = LogCapture.ofAllThreads();

  /// A received POST and the status it was answered with
  record Received(
      String contentType,
      Map<String, String> headers,
      ExportLogsServiceRequest body,
      int status,
      long nanoTime) {

    int records() {
      return body.getResourceLogs(0).getScopeLogs(0).getLogRecordsCount();
    }

    List<String> paths() {
      return body.getResourceLogs(0).getScopeLogs(0).getLogRecordsList().stream()
          .map(record -> attributes(record.getAttributesList()).get("url.path").getStringValue())
          .toList();
    }
  }

  final BlockingQueue<Received> received = new LinkedBlockingQueue<>();

  /// Scripted answer of the log server
  ///
  /// @param retryAfter value of the `Retry-After` header, or `null`
  record Answer(int status, String retryAfter) {}

  /// Answers for the next requests, then [#status] is used
  final BlockingQueue<Answer> answers = new LinkedBlockingQueue<>();

  volatile int status = 200;
  volatile CountDownLatch answerGate;

  HttpServer server;
  int port;
  OtlpLogDestination destination;

  @BeforeEach
  void startServer() throws IOException {
    server = Loopback.httpServer();
    port = server.getAddress().getPort();
    startServerOn(server);
  }

  void startServerOn(HttpServer server) {
    server.createContext(
        "/v1/logs",
        exchange -> {
          byte[] body = exchange.getRequestBody().readAllBytes();
          Map<String, String> headers =
              exchange.getRequestHeaders().entrySet().stream()
                  .collect(
                      Collectors.toMap(
                          e -> e.getKey().toLowerCase(), e -> e.getValue().getFirst()));
          Answer scripted = answers.poll();
          Answer answer = scripted != null ? scripted : new Answer(status, null);
          received.add(
              new Received(
                  exchange.getRequestHeaders().getFirst("Content-Type"),
                  headers,
                  ExportLogsServiceRequest.parseFrom(
                      OtlpConfig.GZIP.equals(headers.get("content-encoding"))
                          ? gunzip(body)
                          : body),
                  answer.status(),
                  System.nanoTime()));
          CountDownLatch gate = answerGate;
          if (gate != null) {
            awaitQuietly(gate);
          }
          if (answer.retryAfter() != null) {
            exchange.getResponseHeaders().set("Retry-After", answer.retryAfter());
          }
          exchange.sendResponseHeaders(answer.status(), -1);
          exchange.close();
        });
    server.start();
  }

  /// The log server comes back on the same port after a restart
  void restartServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
    startServerOn(server);
  }

  @AfterEach
  void stop() {
    if (destination != null) {
      // does not wait, some tests end in an outage
      destination.endThread();
      destination.finish();
    }
    // Lets a running exchange write its answer: the server puts a post into `received` before
    // answering, and a test may end right after taking it. `stop(0)` would close the connection
    // under the answer, the post would fail with "header parser received no bytes", and the
    // sender would log the retry warning late, into the all threads log capture of the next
    // test. Returns at once when no exchange is running.
    TestingParameters.stop(server);
  }

  /// Bounds receipt of the next post, which the server queues before answering it.
  Received receive() throws InterruptedException {
    Received post = received.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertThat(post).as("OTLP post received within %s seconds", TIMEOUT_SECONDS).isNotNull();
    return post;
  }

  String endpoint() {
    return "http://127.0.0.1:" + port + "/v1/logs";
  }

  OtlpConfig.Builder config() {
    return OtlpConfig.builder().endpoint(endpoint()).flushIntervalMillis(1);
  }

  /// Destination with short retry delays, to keep the tests fast
  OtlpLogDestination fastDestination(OtlpConfig config) {
    return new OtlpLogDestination(
        "otlp", config.toBuilder().initialRetryDelayMillis(10).maxRetryDelayMillis(50).build());
  }

  /// Size of a record of the info, as framed in a batch
  static int framedSize(RequestLogInfo info) {
    int size = OtlpRequestLogSink.encode(info).length;
    return 1 + ProtobufWriter.varintSize(size) + size;
  }

  /// Records of all requests answered with success, in the order received
  List<String> deliveredPaths() {
    List<String> paths = new ArrayList<>();
    for (Received post : received) {
      if (post.status() / 100 == 2) {
        paths.addAll(post.paths());
      }
    }
    return paths;
  }

  /// A counter of the destination by its name, e.g. the sent records
  long counter(String name) {
    return (long)
        scrape()
            .counter(name, RequestLogMetrics.DESTINATION_LABEL, destination.destination())
            .getValue();
  }

  /// One scrape of the metrics of the destination under test
  ScrapedMetrics scrape() {
    return OtlpTestSupport.scrape(destination);
  }

  /// A circuit breaker counter of this destination, e.g. `calls` with `kind`, `successful`
  long breakerCounter(String metric, String... labels) {
    return (long)
        scrape().counter(CircuitBreakerMetrics.PREFIX + metric, breakerLabels(labels)).getValue();
  }

  /// A circuit breaker gauge of this destination, e.g. `state` with `state`, `closed`
  double breakerGauge(String metric, String... labels) {
    return scrape().gauge(CircuitBreakerMetrics.PREFIX + metric, breakerLabels(labels)).getValue();
  }

  /// The labels of the breaker of this destination, followed by the given ones
  static String[] breakerLabels(String... labels) {
    String[] keyValues = {
      CircuitBreakerMetrics.COMPONENT_LABEL,
      HttpBatchSender.BREAKER_COMPONENT,
      CircuitBreakerMetrics.NAME_LABEL,
      "otlp"
    };
    String[] all = Arrays.copyOf(keyValues, keyValues.length + labels.length);
    System.arraycopy(labels, 0, all, keyValues.length, labels.length);
    return all;
  }

  /// An in-flight reply during maintenance is counted without announcing recovery.
  @ParameterizedTest
  @ValueSource(ints = {200, 503})
  void replyDuringMaintenanceLeavesTheBreakerForcedOpen(int firstStatus) throws Exception {
    status = firstStatus;
    CountDownLatch allowAnswer = new CountDownLatch(1);
    answerGate = allowAnswer;
    destination =
        new OtlpLogDestination(
            "otlp",
            config()
                .timeoutMillis(OUT_OF_REACH_MILLIS)
                .flushIntervalMillis(OUT_OF_REACH_MILLIS)
                .maximumUnsentLogAgeMillis(OUT_OF_REACH_MILLIS)
                .build());
    CircuitBreaker breaker = destination.metrics().circuitBreakers.find("requestLog", "otlp");
    try {
      new OtlpRequestLogger(destination).logRequest(request("/in-flight"));
      destination.endThread();
      assertThat(receive().status()).isEqualTo(firstStatus);
      breaker.forceOpen();
      status = 200;
      allowAnswer.countDown();

      String kind = firstStatus == 200 ? "successful" : "failed";
      await("in-flight reply counted", () -> breakerCounter("calls", "kind", kind) >= 1);
      assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.FORCED_OPEN);
      assertThat(breaker.forceClose()).isTrue();
      destination.finish().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertThat(counter("sensepitch_request_log_sent_records")).isOne();
      assertThat(logs.messages(OtlpLogDestination.class))
          .noneMatch(message -> message.contains("recovered after"));
    } finally {
      allowAnswer.countDown();
      breaker.forceClose();
    }
  }

  /// Sent records and bytes, and the circuit breaker metrics of an outage and its recovery
  @Test
  void metricsOfAnOutage() throws Exception {
    answers.add(new Answer(503, null));
    destination = fastDestination(config().build());
    long start = System.currentTimeMillis();
    new OtlpRequestLogger(destination).logRequest(request("/a"));
    await("sent record metric updated", () -> counter("sensepitch_request_log_sent_records") >= 1);
    assertThat(counter("sensepitch_request_log_sent_records")).isOne();
    assertThat(counter("sensepitch_request_log_sent_bytes"))
        .as("the request body")
        .isEqualTo(sentBodyBytes());
    assertThat(breakerCounter("calls", "kind", "successful")).isOne();
    assertThat(breakerCounter("calls", "kind", "failed")).isOne();
    assertThat(breakerCounter("calls", "kind", "ignored")).isZero();
    assertThat(breakerCounter("errors", "error", "http_503")).isOne();
    HistogramSnapshot.HistogramDataPointSnapshot postDuration =
        (HistogramSnapshot.HistogramDataPointSnapshot)
            scrape()
                .datapoint(
                    "sensepitch_request_log_post_duration_seconds",
                    "destination",
                    destination.destination());
    assertThat(postDuration.getCount()).as("the failed and the successful post").isEqualTo(2);
    assertThat(postDuration.getSum()).as("seconds of both posts").isPositive();
    assertThat(breakerCounter("transitions", "from", "closed", "to", "open", "reason", "http_503"))
        .isOne();
    assertThat(
            breakerCounter(
                "transitions", "from", "open", "to", "half_open", "reason", "backoff_over"))
        .isOne();
    assertThat(
            breakerCounter(
                "transitions", "from", "half_open", "to", "closed", "reason", "recovered"))
        .isOne();
    assertThat(breakerGauge("state", "state", "closed")).isOne();
    assertThat(breakerGauge("state", "state", "open")).isZero();
    assertThat(breakerGauge("consecutive_failures")).isZero();
    double trip = breakerGauge("last_trip_time_seconds");
    assertThat(breakerGauge("start_time_seconds")).isBetween(start / 1000.0 - 1, trip);
    assertThat(trip).isGreaterThanOrEqualTo(start / 1000.0);
    assertThat(breakerGauge("last_restart_time_seconds")).isGreaterThanOrEqualTo(trip);
    HttpBatchSenderTest.close(destination);
    assertThat(scrape().containsSnapshot(CircuitBreakerMetrics.PREFIX + "state").getDataPoints())
        .as("gauges of a stopped breaker are gone")
        .isEmpty();
    assertThat(breakerCounter("calls", "kind", "successful")).as("counters stay").isOne();
  }

  /// The posts are compressed by default, with the uncompressed batch size
  @Test
  void gzipByDefault() throws Exception {
    // one post at the close
    destination = fastDestination(config().flushIntervalMillis(OUT_OF_REACH_MILLIS).build());
    OtlpRequestLogger logger = new OtlpRequestLogger(destination);
    for (int i = 0; i < 100; i++) {
      logger.logRequest(request("/same/path/" + i));
    }
    HttpBatchSenderTest.close(destination);
    Received post = receive();
    assertThat(post.headers()).containsEntry("content-encoding", "gzip");
    assertThat(post.records()).isEqualTo(100);
    assertThat(Long.parseLong(post.headers().get("content-length")))
        .as("compressed")
        .isLessThan(post.body().getSerializedSize() / 2);
  }

  @Test
  void compressionNone() throws Exception {
    destination = fastDestination(config().compression(OtlpConfig.NONE).build());
    assertThat(destination.compressionThreads()).isZero();
    assertThat(destination.compressionQueueCapacity()).isZero();
    assertThat(destination.compressionExecutorStopped()).isTrue();
    new OtlpRequestLogger(destination).logRequest(request("/plain"));
    HttpBatchSenderTest.close(destination);
    Received post = receive();
    assertThat(post.headers()).doesNotContainKey("content-encoding");
    assertThat(post.paths()).containsExactly("/plain");
  }

  /// Compression gets one bounded thread per destination and stops with the sender
  @Test
  void compressionExecutorIsBoundedAndStops() throws Exception {
    destination =
        fastDestination(
            config()
                .maxConcurrentRequests(3)
                .flushIntervalMillis(OUT_OF_REACH_MILLIS)
                .compressionLevel(Deflater.BEST_COMPRESSION)
                .build());
    assertThat(destination.compressionThreads()).isEqualTo(1);
    assertThat(destination.compressionQueueCapacity()).isEqualTo(3);
    assertThat(destination.compressionExecutorStopped()).isFalse();
    OtlpRequestLogger logger = new OtlpRequestLogger(destination);
    for (int i = 0; i < 100; i++) {
      logger.logRequest(request("/compressed/" + i));
    }
    // runs in the thread that stops the sender, here the completion of the post
    CompletableFuture<Boolean> stoppedWhenCompleted =
        destination.stopped().thenApply(ignored -> destination.compressionExecutorStopped());
    HttpBatchSenderTest.close(destination);
    Received post = receive();
    assertThat(post.headers()).containsEntry("content-encoding", "gzip");
    assertThat(post.paths()).hasSize(100).startsWith("/compressed/0");
    assertThat(stoppedWhenCompleted.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
    assertThat(destination.compressionExecutorStopped()).isTrue();
  }

  /// Bytes of the successful request bodies the server received
  long sentBodyBytes() {
    long bytes = 0;
    for (Received post : received) {
      if (post.status() == 200) {
        bytes += Long.parseLong(post.headers().get("content-length"));
      }
    }
    return bytes;
  }

  long bufferedBytesGauge() {
    return OtlpTestSupport.bufferedBytesGauge(destination);
  }

  @Test
  void recordIsExportedWithResourceScopeAndAttributes() throws Exception {
    destination =
        new OtlpLogDestination(
            "otlp",
            config()
                .headers(Map.of("VL-Stream-Fields", "service.name"))
                .resourceAttributes(Map.of("deployment.environment", "test"))
                .build());
    TestRequestLogInfo info =
        TestRequestLogInfo.of(HttpMethod.GET, 200).withClientAddress("192.0.2.7");
    info.request().headers().set(HttpHeaderNames.ACCEPT_LANGUAGE, "en");
    // path and query are written as ranges of the URI
    info.request().setUri("/search?q=edge");
    new OtlpRequestLogger(destination).logRequest(info);
    Received post = receive();
    assertThat(post.contentType()).isEqualTo("application/x-protobuf");
    assertThat(post.headers()).containsEntry("vl-stream-fields", "service.name");

    ResourceLogs resourceLogs = post.body().getResourceLogs(0);
    assertThat(resourceLogs.getSchemaUrl()).isEqualTo(OtelLogFields.SCHEMA_URL);
    Map<String, AnyValue> resource = attributes(resourceLogs.getResource().getAttributesList());
    assertThat(resource.get("service.name").getStringValue()).isEqualTo("sensepitch-edge");
    assertThat(resource.get("deployment.environment").getStringValue()).isEqualTo("test");

    ScopeLogs scopeLogs = resourceLogs.getScopeLogs(0);
    assertThat(scopeLogs.getScope().getName()).isEqualTo(OtlpLogDestination.SCOPE);
    assertThat(scopeLogs.getSchemaUrl()).isEqualTo(OtelLogFields.SCHEMA_URL);
    LogRecord record = scopeLogs.getLogRecords(0);
    assertThat(record.getTimeUnixNano()).isZero();
    assertThat(record.getObservedTimeUnixNano()).isPositive();
    assertThat(record.getSeverityNumberValue()).isEqualTo(9);
    assertThat(record.getSeverityText()).isEqualTo("INFO");
    assertThat(record.getEventName()).isEqualTo(SensepitchLogFields.EVENT_NAME_VALUE);
    assertThat(record.getBody().getStringValue()).isEqualTo("GET /search?q=edge 200");
    assertThat(HexFormat.of().formatHex(record.getTraceId().toByteArray()))
        .isEqualTo(TestRequestLogInfo.TRACE_ID);
    assertThat(HexFormat.of().formatHex(record.getSpanId().toByteArray()))
        .isEqualTo(TestRequestLogInfo.SPAN_ID);
    assertThat(record.getFlags()).isEqualTo(1);

    Map<String, AnyValue> attributes = attributes(record.getAttributesList());
    assertThat(attributes.get("client.address").getStringValue()).isEqualTo("192.0.2.7");
    assertThat(attributes.get("url.path").getStringValue()).isEqualTo("/search");
    assertThat(attributes.get("url.query").getStringValue()).isEqualTo("q=edge");
    assertThat(attributes.get("http.response.status_code").getIntValue()).isEqualTo(200);
    assertThat(attributes.get("sensepitch.request.cookie_present").getBoolValue()).isFalse();
    assertThat(
            attributes.get("http.request.header.accept-language").getArrayValue().getValuesList())
        .extracting(AnyValue::getStringValue)
        .containsExactly("en");
  }

  /// Records of a thread are buffered and sent together
  @Test
  void recordsOfAThreadAreSentTogether() throws Exception {
    destination =
        new OtlpLogDestination("otlp", config().flushIntervalMillis(OUT_OF_REACH_MILLIS).build());
    OtlpRequestLogger logger = new OtlpRequestLogger(destination);
    for (int i = 0; i < 25; i++) {
      logger.logRequest(TestRequestLogInfo.of(HttpMethod.GET, 200));
    }
    assertThat(destination.queueSize()).isEqualTo(25);
    HttpBatchSenderTest.close(destination);
    assertThat(receive().records()).isEqualTo(25);
    assertThat(received).isEmpty();
  }

  /// A full batch is sent before the flush interval is over
  @Test
  void fullBatchIsSentEarly() throws Exception {
    destination =
        new OtlpLogDestination(
            "otlp",
            config()
                .flushIntervalMillis(OUT_OF_REACH_MILLIS)
                .batchBytes(OtlpConfig.MIN_BATCH_BYTES)
                .build());
    OtlpRequestLogger logger = new OtlpRequestLogger(destination);
    TestRequestLogInfo info = TestRequestLogInfo.of(HttpMethod.GET, 200);
    int perBatch = (OtlpConfig.MIN_BATCH_BYTES + framedSize(info) - 1) / framedSize(info);
    for (int i = 0; i < perBatch; i++) {
      logger.logRequest(info);
    }
    Received post = receive();
    assertThat(post).as("sent without waiting for the interval").isNotNull();
    assertThat(post.records()).isEqualTo(perBatch);
  }

  /// A status that retrying cannot fix gives up the batch, it is not sent again
  @Test
  void badRequestGivesUpTheBatch() throws Exception {
    answers.add(new Answer(400, null));
    destination = fastDestination(config().flushIntervalMillis(OUT_OF_REACH_MILLIS).build());
    OtlpRequestLogger logger = new OtlpRequestLogger(destination);
    logger.logRequest(TestRequestLogInfo.of(HttpMethod.GET, 200));
    logger.logRequest(TestRequestLogInfo.of(HttpMethod.GET, 200));
    HttpBatchSenderTest.close(destination);
    assertThat(receive().status()).isEqualTo(400);
    assertThat(received).as("not retried").isEmpty();
    assertThat(counter("sensepitch_request_log_export_failed")).isOne();
    assertThat(counter("sensepitch_request_log_export_retries")).isZero();
    assertThat(destination.droppedCount()).isEqualTo(2);
    assertThat(counter("sensepitch_request_log_sent_records")).isZero();
    assertThat(breakerCounter("calls", "kind", "ignored")).isOne();
    assertThat(breakerCounter("errors", "error", "http_400")).isOne();
    assertThat(logs.messages(OtlpLogDestination.class))
        .singleElement()
        .asString()
        .contains("failed, dropped a batch: HTTP status 400");
  }

  /// Given up batches do not stop the export, later batches are sent
  @Test
  void exportContinuesAfterGivingUp() throws Exception {
    answers.add(new Answer(400, null));
    destination = fastDestination(config().build());
    OtlpRequestLogger logger = new OtlpRequestLogger(destination);
    logger.logRequest(request("/lost"));
    assertThat(receive().status()).isEqualTo(400);
    logger.logRequest(request("/sent"));
    Received next = receive();
    assertThat(next.status()).isEqualTo(200);
    assertThat(next.paths()).containsExactly("/sent");
  }

  /// When the log server stays down, the waiting batches are limited. The newest records are
  /// dropped, the oldest are kept and sent when the server is back.
  @Test
  void limitDropsTheNewestRecords() throws Exception {
    status = 503;
    int batchBytes = OtlpConfig.MIN_BATCH_BYTES;
    OtlpConfig config =
        config()
            .batchBytes(batchBytes)
            // one request at a time, so the batches arrive in order
            .maxConcurrentRequests(1)
            .build();
    long limit = config.maxBufferedBytes();
    destination = fastDestination(config);
    OtlpRequestLogger logger = new OtlpRequestLogger(destination);
    // a few batches more than the limit holds
    int total = (int) ((limit + 4L * batchBytes) / framedSize(request("/000000")));
    for (int i = 0; i < total; i++) {
      logger.logRequest(request(String.format("/%06d", i)));
    }
    assertThat(destination.droppedCount()).isPositive();
    assertThat(bufferedBytesGauge()).isLessThanOrEqualTo(limit);
    status = 200;
    HttpBatchSenderTest.close(destination);
    List<String> delivered = deliveredPaths();
    assertThat(delivered.size() + destination.droppedCount()).isEqualTo(total);
    assertThat(delivered).as("the oldest are kept").startsWith("/000000", "/000001").isSorted();
  }

  /// Batches taken during an outage are released again after it, only a few stay in the pool
  @Test
  void poolShrinksAfterAnOutage() throws Exception {
    status = 503;
    int batchBytes = OtlpConfig.MIN_BATCH_BYTES;
    // room for all batches, also on machines with few event loops
    destination =
        fastDestination(config().batchBytes(batchBytes).maxBufferedBytes(64 << 20).build());
    OtlpRequestLogger logger = new OtlpRequestLogger(destination);
    int total = 20 * batchBytes / framedSize(request("/0"));
    for (int i = 0; i < total; i++) {
      logger.logRequest(request("/" + i));
    }
    assertThat(bufferedBytesGauge()).isGreaterThan(10L * batchBytes);
    status = 200;
    await("all records delivered", () -> deliveredPaths().size() >= total);
    assertThat(deliveredPaths()).hasSize(total);
    await("buffered batches released", () -> bufferedBytesGauge() == 0);
    assertThat(destination.pooledBatches()).as("one thread plus a spare").isLessThanOrEqualTo(2);
  }

  /// A finished destination keeps its records during an outage, they are reported as unsent
  @Test
  void finishDuringAnOutage() throws Exception {
    server.stop(0);
    destination = fastDestination(config().flushIntervalMillis(OUT_OF_REACH_MILLIS).build());
    OtlpRequestLogger logger = new OtlpRequestLogger(destination);
    for (int i = 0; i < 10; i++) {
      logger.logRequest(TestRequestLogInfo.of(HttpMethod.GET, 200));
    }
    logger.endThread();
    CompletableFuture<Void> stopped = destination.finish();
    await("outage retried", () -> counter("sensepitch_request_log_export_retries") > 0);
    assertThat(stopped).isNotDone();
    assertThat(destination.queueSize()).isEqualTo(10);
    assertThat(destination.droppedCount()).isZero();
  }

  @Test
  void closeSendsPendingRecords() throws Exception {
    destination =
        new OtlpLogDestination("otlp", config().flushIntervalMillis(OUT_OF_REACH_MILLIS).build());
    new OtlpRequestLogger(destination).logRequest(TestRequestLogInfo.of(HttpMethod.GET, 200));
    HttpBatchSenderTest.close(destination);
    assertThat(received.poll()).isNotNull();
  }

  /// A thread that terminated is forgotten, after its last records were sent
  @Test
  void terminatedThreadIsRetired() throws Exception {
    destination = new OtlpLogDestination("otlp", config().flushIntervalMillis(5).build());
    OtlpRequestLogger logger = new OtlpRequestLogger(destination);
    Thread worker = new Thread(() -> logger.logRequest(request("/last")));
    worker.start();
    assertThat(worker.join(TIMEOUT)).as("logging worker finished").isTrue();
    await("terminated thread records delivered", () -> !deliveredPaths().isEmpty());
    assertThat(deliveredPaths()).containsExactly("/last");
  }

  @Test
  void recordAfterCloseIsDropped() {
    destination = new OtlpLogDestination("otlp", config().build());
    OtlpRequestLogger logger = new OtlpRequestLogger(destination);
    logger.logRequest(TestRequestLogInfo.of(HttpMethod.GET, 200));
    logger.close();
    logger.logRequest(TestRequestLogInfo.of(HttpMethod.GET, 200));
    assertThat(destination.droppedCount()).isOne();
  }

  /// A failing mapping leaves no partial record in the batch
  @Test
  void failedEncodingLeavesNoPartialRecord() throws Exception {
    destination =
        new OtlpLogDestination("otlp", config().flushIntervalMillis(OUT_OF_REACH_MILLIS).build());
    OtlpRequestLogger logger = new OtlpRequestLogger(destination);
    // fails at the last field, when most of the record is already written
    RequestLogInfo broken =
        new AnonymizedClientRequestLogInfo(TestRequestLogInfo.of(HttpMethod.GET, 200)) {
          @Override
          public long totalDurationNanos() {
            throw new IllegalStateException("broken");
          }
        };
    assertThatThrownBy(() -> logger.logRequest(broken)).isInstanceOf(IllegalStateException.class);
    logger.logRequest(TestRequestLogInfo.of(HttpMethod.GET, 200));
    HttpBatchSenderTest.close(destination);
    assertThat(receive().records()).isOne();
  }

  /// At the flush interval, the partly filled batches of all threads go out in one request
  @Test
  void partialBatchesOfThreadsArriveInOneRequest() throws Exception {
    int threads = 4;
    int perThread = 5;
    List<List<String>> posted = flushPartialBatchesOfLiveThreads(config(), threads, perThread);
    assertThat(posted).as("all threads in one request").hasSize(1);
    assertThat(new HashSet<>(posted.getFirst())).hasSize(threads * perThread);
  }

  /// Partly filled batches beyond the batch size are split into several requests
  @Test
  void partialBatchesBeyondTheBatchSizeAreSplit() throws Exception {
    int batchBytes = OtlpConfig.MIN_BATCH_BYTES;
    // each thread fills about 60% of a batch, two of them do not fit into one request
    int perThread = batchBytes * 6 / 10 / framedSize(request("/0/0000"));
    List<List<String>> posted =
        flushPartialBatchesOfLiveThreads(config().batchBytes(batchBytes), 2, perThread);
    assertThat(posted).extracting(List::size).containsExactly(perThread, perThread);
  }

  /// Runs the flush of partly filled batches of threads that are still alive, on the simulated
  /// clock, so the flush cannot come before all threads logged: each worker logs `perThread`
  /// records and waits, then the flush interval passes, then the workers end.
  ///
  /// @return the record paths of each posted request, in posting order
  static List<List<String>> flushPartialBatchesOfLiveThreads(
      OtlpConfig.Builder config, int threads, int perThread) throws Exception {
    FakeTime clock = new FakeTime();
    ControlledTransport transport = new ControlledTransport();
    // uncompressed, so the flush posts on the test thread
    OtlpConfig built = config.compression(OtlpConfig.NONE).flushIntervalMillis(500).build();
    OtlpLogDestination destination =
        new OtlpLogDestination(
            "otlp",
            built,
            new RequestLogMetrics(),
            0,
            clock,
            clock.scheduler()::schedule,
            transport);
    OtlpRequestLogger logger = new OtlpRequestLogger(destination);
    CountDownLatch logged = new CountDownLatch(threads);
    CountDownLatch release = new CountDownLatch(1);
    List<Thread> workers = new ArrayList<>();
    List<List<String>> posted;
    try {
      for (int t = 0; t < threads; t++) {
        int thread = t;
        workers.add(
            Thread.ofPlatform()
                .start(
                    () -> {
                      for (int i = 0; i < perThread; i++) {
                        logger.logRequest(request(String.format("/%d/%04d", thread, i)));
                      }
                      logged.countDown();
                      // alive until the flush took the batch
                      awaitQuietly(release);
                      destination.endThread();
                    }));
      }
      assertThat(logged.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).as("workers logged").isTrue();
      assertThat(transport.requests()).as("nothing posted before the flush").isEmpty();
      clock.advanceMillis(built.flushIntervalMillis());
      posted = transport.requests().stream().map(OtlpTestSupport::paths).toList();
    } finally {
      release.countDown();
    }
    for (Thread worker : workers) {
      assertThat(worker.join(TIMEOUT)).as("logging worker finished").isTrue();
    }
    for (int i = 0; i < posted.size(); i++) {
      transport.succeed(200);
    }
    assertThat(destination.finish()).as("all sent").isDone();
    assertThat(destination.droppedCount()).isZero();
    return posted;
  }

  /// Keeps a logging worker alive until the test releases it, from a lambda that cannot throw
  static void awaitQuietly(CountDownLatch latch) {
    try {
      assertThat(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).as("worker released").isTrue();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /// After a failure the window is one request, it grows with each success back to
  /// `maxConcurrentRequests`. The requests in flight at each step are timing dependent, they are
  /// checked by [OtlpSlowStartIT].
  @Test
  void windowIsOneAfterAFailureAndGrowsBack() throws Exception {
    status = 503;
    int batchBytes = OtlpConfig.MIN_BATCH_BYTES;
    destination =
        fastDestination(
            config()
                .batchBytes(batchBytes)
                .maxBufferedBytes(64 << 20)
                .maxConcurrentRequests(8)
                .flushIntervalMillis(OUT_OF_REACH_MILLIS)
                .build());
    assertThat(destination.window()).as("initially").isEqualTo(8);
    OtlpRequestLogger logger = new OtlpRequestLogger(destination);
    int total = 10 * (batchBytes / framedSize(request("/0")) + 1);
    for (int i = 0; i < total; i++) {
      logger.logRequest(request("/" + i));
    }
    await("outage retried", () -> counter("sensepitch_request_log_export_retries") >= 1);
    assertThat(destination.window()).as("after a failure").isOne();
    status = 200;
    HttpBatchSenderTest.close(destination);
    assertThat(new HashSet<>(deliveredPaths())).hasSize(total);
    assertThat(destination.droppedCount()).isZero();
    assertThat(destination.window()).as("back at the maximum").isEqualTo(8);
  }

  /// A status that retrying cannot fix does not open the breaker, the next batch goes out at once
  @Test
  void badRequestDoesNotOpenTheBreaker() throws Exception {
    answers.add(new Answer(400, null));
    // an open breaker would hold the second batch beyond the wait of `receive`
    destination =
        new OtlpLogDestination(
            "otlp",
            config()
                .initialRetryDelayMillis(OUT_OF_REACH_MILLIS)
                .maxRetryDelayMillis(OUT_OF_REACH_MILLIS)
                .build());
    OtlpRequestLogger logger = new OtlpRequestLogger(destination);
    logger.logRequest(request("/bad"));
    assertThat(receive().status()).isEqualTo(400);
    logger.logRequest(request("/good"));
    Received next = receive();
    assertThat(next.status()).isEqualTo(200);
    assertThat(next.paths()).containsExactly("/good");
  }

  /// A probe answered with a status that retrying cannot fix ends the outage: the server is up,
  /// the next batch goes out at once instead of after the probe timeout
  @Test
  void badRequestOfTheProbeEndsTheOutage() throws Exception {
    answers.add(new Answer(503, null));
    answers.add(new Answer(400, null));
    // a probe still open would hold the next batch beyond the wait of `receive`
    destination = fastDestination(config().timeoutMillis(OUT_OF_REACH_MILLIS).build());
    OtlpRequestLogger logger = new OtlpRequestLogger(destination);
    logger.logRequest(request("/a"));
    assertThat(receive().status()).isEqualTo(503);
    assertThat(receive().status()).as("the probe").isEqualTo(400);
    await(
        "failed export metric updated", () -> counter("sensepitch_request_log_export_failed") >= 1);
    assertThat(counter("sensepitch_request_log_export_failed")).isOne();
    logger.logRequest(request("/b"));
    Received next = receive();
    assertThat(next.status()).isEqualTo(200);
    assertThat(next.paths()).containsExactly("/b");
    assertThat(
            breakerCounter(
                "transitions", "from", "half_open", "to", "closed", "reason", "recovered"))
        .isOne();
    assertThat(logs.messages(OtlpLogDestination.class))
        .anySatisfy(m -> assertThat(m).contains("recovered after 1 failed attempts"));
  }

  /// A batch given up is logged once, further ones are only counted
  @Test
  void givingUpIsLoggedOnce() throws Exception {
    answers.add(new Answer(400, null));
    answers.add(new Answer(400, null));
    destination = new OtlpLogDestination("otlp", config().build());
    OtlpRequestLogger logger = new OtlpRequestLogger(destination);
    logger.logRequest(request("/bad1"));
    assertThat(receive().status()).isEqualTo(400);
    logger.logRequest(request("/bad2"));
    assertThat(receive().status()).isEqualTo(400);
    await(
        "both failed exports counted", () -> counter("sensepitch_request_log_export_failed") >= 2);
    assertThat(counter("sensepitch_request_log_export_failed")).isEqualTo(2);
    assertThat(logs.messages(OtlpLogDestination.class))
        .filteredOn(m -> m.contains("failed, dropped a batch"))
        .hasSize(1);
  }

  /// A thread that reached the limit takes records again once the waiting batches were sent
  @Test
  void recordsAreAcceptedAgainWhenThereIsRoom() throws Exception {
    status = 503;
    int batchBytes = OtlpConfig.MIN_BATCH_BYTES;
    OtlpConfig config =
        config().batchBytes(batchBytes).flushIntervalMillis(OUT_OF_REACH_MILLIS).build();
    destination = fastDestination(config);
    OtlpRequestLogger logger = new OtlpRequestLogger(destination);
    // two batches more than the limit holds
    int total = (int) ((config.maxBufferedBytes() + 2L * batchBytes) / framedSize(request("/0")));
    for (int i = 0; i < total; i++) {
      logger.logRequest(request("/" + i));
    }
    long dropped = destination.droppedCount();
    assertThat(dropped).as("limit reached").isPositive();
    status = 200;
    await("buffered batches released", () -> bufferedBytesGauge() == 0);
    logger.logRequest(request("/again"));
    HttpBatchSenderTest.close(destination);
    assertThat(deliveredPaths()).contains("/again");
    assertThat(destination.droppedCount()).isEqualTo(dropped);
  }

  /// The resource identifies the instance by default, configured attributes override defaults
  @Test
  void resourceDefaultsAndOverrides() throws Exception {
    Map<String, AnyValue> defaults =
        attributes(Resource.parseFrom(OtlpLogDestination.encodeResource(null)).getAttributesList());
    assertThat(defaults.get("service.name").getStringValue()).isEqualTo("sensepitch-edge");
    assertThat(defaults.get("service.instance.id").getStringValue())
        .isEqualTo(OtlpLogDestination.instanceId())
        .isNotBlank();
    Map<String, AnyValue> configured =
        attributes(
            Resource.parseFrom(
                    OtlpLogDestination.encodeResource(
                        Map.of("service.name", "edge-eu", "service.instance.id", "edge-1")))
                .getAttributesList());
    assertThat(configured.get("service.name").getStringValue()).isEqualTo("edge-eu");
    assertThat(configured.get("service.instance.id").getStringValue()).isEqualTo("edge-1");
  }
}
