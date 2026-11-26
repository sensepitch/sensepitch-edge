package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.sensepitch.edge.OtlpTestSupport.attributes;
import static org.sensepitch.edge.OtlpTestSupport.gunzip;
import static org.sensepitch.edge.TestRequestLogInfo.request;
import static org.sensepitch.edge.TestingParameters.EVENT_TIMEOUT;
import static org.sensepitch.edge.TestingParameters.EVENT_TIMEOUT_SECONDS;
import static org.sensepitch.edge.TestingParameters.OUT_OF_REACH_MILLIS;
import static org.sensepitch.edge.TestingParameters.TEST_TIMEOUT_SECONDS;

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
import java.net.http.HttpRequest;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/// OTLP/HTTP export of the request log: buffering, batching, retry and the metrics. The posted
/// bytes are parsed with the official OTLP message classes, which proves the hand written encoding
/// conforms to the schema.
///
/// Most tests run the destination on simulated time, see [#simulated(OtlpConfig.Builder)]: a
/// [FakeTime] drives the flush, the backoff and the post durations, a [FakeLogServer]
/// takes the posts, and the test answers them. Everything runs in the test thread, so the log is
/// captured per thread and the class runs in parallel with the others. The wire format
/// (compression, headers, the exported record) is checked against a real log server, which
/// [OtlpLogDestinationIT] also uses as its fixture.
///
/// New OTLP scenarios belong here. Outages as step chains (age limit, breaker, probes) are in
/// [OtlpOutageBDDTest], timing details of the sender in [HttpBatchSenderTest].
///
/// @author Jens Wilke
@Timeout(TEST_TIMEOUT_SECONDS)
class OtlpLogDestinationTest {

  @RegisterExtension LogCapture logs = new LogCapture();

  // the real log server, for the wire format and for OtlpLogDestinationIT

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
          .map(
              record ->
                  attributes(record.getAttributesList())
                      .get(OtlpTestSupport.PATH_ATTRIBUTE)
                      .getStringValue())
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
    server = HttpServer.create(new InetSocketAddress(Loopback.ADDRESS, port), 0);
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
    // sender would log the retry warning late, into the log capture of another test. Returns at
    // once when no exchange is running.
    TestingParameters.stop(server);
  }

  /// Bounds receipt of the next post, which the server queues before answering it.
  Received receive() throws InterruptedException {
    Received post = received.poll(EVENT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertThat(post).as("OTLP post received within %s seconds", EVENT_TIMEOUT_SECONDS).isNotNull();
    return post;
  }

  String endpoint() {
    return "http://" + Loopback.ADDRESS + ":" + port + "/v1/logs";
  }

  OtlpConfig.Builder config() {
    return OtlpConfig.builder().endpoint(endpoint()).flushIntervalMillis(1);
  }

  /// Destination with short retry delays, to keep the tests on the real server fast
  OtlpLogDestination fastDestination(OtlpConfig config) {
    return new OtlpLogDestination(
        "otlp", config.toBuilder().initialRetryDelayMillis(10).maxRetryDelayMillis(50).build());
  }

  /// Records of all requests the real server answered with success, in the order received
  List<String> deliveredPaths() {
    List<String> paths = new ArrayList<>();
    for (Received post : received) {
      if (post.status() / 100 == 2) {
        paths.addAll(post.paths());
      }
    }
    return paths;
  }

  // simulated time

  /// Flush interval of a simulated destination
  static final int FLUSH_MILLIS = 100;

  /// Initial and maximum retry delay of a simulated destination: with jitter, the probe after a
  /// failure is due within this time
  static final int RETRY_MILLIS = 1_000;

  /// Delay of the log server in the `Retry-After` tests
  static final int RETRY_AFTER_SECONDS = 30;

  final FakeTime clock = new FakeTime();
  final FakeLogServer logServer = new FakeLogServer();

  /// Requests of the [#logServer] answered so far, they are answered in posting order
  int answered;

  /// Record paths of the requests answered with success, in the order answered
  final List<String> delivered = new ArrayList<>();

  /// Body bytes of the requests answered with success
  long deliveredBytes;

  /// The configuration of a simulated destination before the test changes it: uncompressed, so
  /// every post is made in the thread that logs, flushes or answers
  static OtlpConfig.Builder simulatedConfig() {
    return OtlpConfig.builder()
        .endpoint(HttpBatchSenderTest.UNREACHABLE)
        .compression(OtlpConfig.NONE)
        .flushIntervalMillis(FLUSH_MILLIS)
        .initialRetryDelayMillis(RETRY_MILLIS)
        .maxRetryDelayMillis(RETRY_MILLIS);
  }

  /// A destination on [#clock] that posts into [#logServer]; it becomes [#destination]
  OtlpLogDestination simulated(OtlpConfig.Builder config) {
    destination =
        new OtlpLogDestination(
            "otlp",
            config.build(),
            new RequestLogMetrics(),
            clock,
            clock.scheduler()::schedule,
            logServer);
    return destination;
  }

  /// Requests posted so far
  int posted() {
    return logServer.requests().size();
  }

  /// Answers the oldest open request with the status
  void answer(int status) {
    answer(status, null);
  }

  /// Answers the oldest open request with the status and a `Retry-After` header
  void answer(int status, String retryAfter) {
    HttpRequest request = logServer.requests().get(answered++);
    if (status / 100 == 2) {
      delivered.addAll(OtlpTestSupport.paths(request));
      deliveredBytes += OtlpTestSupport.body(request).length;
    }
    logServer.succeed(status, retryAfter);
  }

  /// Answers the open requests with the status, and those the answers release, until none is open
  void answerAll(int status) {
    while (answered < posted()) {
      answer(status);
    }
  }

  /// Fails the oldest open request like a log server that is down
  void refuseNext() {
    answered++;
    logServer.fail(HttpBatchSenderTest.refused());
  }

  /// Ends the thread and finishes the destination, answering every post with success
  void finishAndDeliver() {
    destination.endThread();
    CompletableFuture<Void> stopped = destination.finish();
    answerAll(200);
    assertThat(stopped).as("all sent").isDone();
  }

  // helpers for both

  /// Size of a record of the info, as framed in a batch
  static int framedSize(RequestLogInfo info) {
    int size = OtlpRequestLogSink.encode(info).length;
    return 1 + ProtobufWriter.varintSize(size) + size;
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

  long bufferedBytesGauge() {
    return OtlpTestSupport.bufferedBytesGauge(destination);
  }

  /// Keeps a logging worker alive until the test releases it, from a lambda that cannot throw
  static void awaitQuietly(CountDownLatch latch) {
    try {
      assertThat(latch.await(EVENT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
          .as("worker released")
          .isTrue();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  // wire format, against the real log server

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
    assertThat(stoppedWhenCompleted.get(EVENT_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
    assertThat(destination.compressionExecutorStopped()).isTrue();
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

  /// `HOSTNAME`, the pod name in Kubernetes, wins over the local host name; a blank value is unset
  @Test
  void instanceIdPrefersTheHostnameVariable() {
    assertThat(OtlpLogDestination.instanceId("edge-7d9f")).isEqualTo("edge-7d9f");
    assertThat(OtlpLogDestination.instanceId(" ")).isEqualTo(OtlpLogDestination.instanceId(null));
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

  // batching, on simulated time

  /// Records of a thread are buffered and sent together
  @Test
  void recordsOfAThreadAreSentTogether() {
    simulated(simulatedConfig().flushIntervalMillis(OUT_OF_REACH_MILLIS));
    OtlpRequestLogger logger = new OtlpRequestLogger(destination);
    for (int i = 0; i < 25; i++) {
      logger.logRequest(request("/" + i));
    }
    assertThat(destination.queueSize()).isEqualTo(25);
    assertThat(posted()).isZero();
    finishAndDeliver();
    assertThat(posted()).isOne();
    assertThat(delivered).hasSize(25);
  }

  /// A full batch is sent before the flush interval is over
  @Test
  void fullBatchIsSentEarly() {
    simulated(
        simulatedConfig()
            .flushIntervalMillis(OUT_OF_REACH_MILLIS)
            .batchBytes(OtlpConfig.MIN_BATCH_BYTES));
    OtlpRequestLogger logger = new OtlpRequestLogger(destination);
    TestRequestLogInfo info = request("/0");
    int perBatch = (OtlpConfig.MIN_BATCH_BYTES + framedSize(info) - 1) / framedSize(info);
    for (int i = 0; i < perBatch; i++) {
      logger.logRequest(info);
    }
    assertThat(posted()).as("sent without waiting for the interval").isOne();
    answer(200);
    assertThat(delivered).hasSize(perBatch);
  }

  /// At the flush interval the partly filled batch goes out, not before
  @Test
  void partialBatchIsSentAtTheFlush() {
    simulated(simulatedConfig());
    new OtlpRequestLogger(destination).logRequest(request("/a"));
    clock.advanceMillis(FLUSH_MILLIS - 1);
    assertThat(posted()).isZero();
    clock.advanceMillis(1);
    assertThat(posted()).isOne();
    answer(200);
    assertThat(delivered).containsExactly("/a");
  }

  @Test
  void closeSendsPendingRecords() {
    simulated(simulatedConfig().flushIntervalMillis(OUT_OF_REACH_MILLIS));
    new OtlpRequestLogger(destination).logRequest(request("/pending"));
    finishAndDeliver();
    assertThat(delivered).containsExactly("/pending");
  }

  /// A thread that terminated is forgotten, after its last records were sent
  @Test
  void terminatedThreadIsRetired() throws Exception {
    simulated(simulatedConfig());
    OtlpRequestLogger logger = new OtlpRequestLogger(destination);
    Thread worker = Thread.ofPlatform().start(() -> logger.logRequest(request("/last")));
    assertThat(worker.join(EVENT_TIMEOUT)).as("logging worker finished").isTrue();
    clock.advanceMillis(FLUSH_MILLIS);
    answerAll(200);
    assertThat(delivered).containsExactly("/last");
    assertThat(destination.finish()).as("no batcher of the thread left").isDone();
  }

  @Test
  void recordAfterCloseIsDropped() {
    simulated(simulatedConfig());
    OtlpRequestLogger logger = new OtlpRequestLogger(destination);
    logger.logRequest(request("/a"));
    logger.close();
    logger.logRequest(request("/b"));
    assertThat(destination.droppedCount()).isOne();
  }

  /// A failing mapping leaves no partial record in the batch
  @Test
  void failedEncodingLeavesNoPartialRecord() {
    simulated(simulatedConfig().flushIntervalMillis(OUT_OF_REACH_MILLIS));
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
    logger.logRequest(request("/whole"));
    finishAndDeliver();
    assertThat(delivered).containsExactly("/whole");
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
    FakeLogServer logServer = new FakeLogServer();
    // uncompressed, so the flush posts on the test thread
    OtlpConfig built = config.compression(OtlpConfig.NONE).flushIntervalMillis(500).build();
    OtlpLogDestination destination =
        new OtlpLogDestination(
            "otlp", built, new RequestLogMetrics(), clock, clock.scheduler()::schedule, logServer);
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
      assertThat(logged.await(EVENT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
          .as("workers logged")
          .isTrue();
      assertThat(logServer.requests()).as("nothing posted before the flush").isEmpty();
      clock.advanceMillis(built.flushIntervalMillis());
      posted = logServer.requests().stream().map(OtlpTestSupport::paths).toList();
    } finally {
      release.countDown();
    }
    for (Thread worker : workers) {
      assertThat(worker.join(EVENT_TIMEOUT)).as("logging worker finished").isTrue();
    }
    for (int i = 0; i < posted.size(); i++) {
      logServer.succeed(200);
    }
    assertThat(destination.finish()).as("all sent").isDone();
    assertThat(destination.droppedCount()).isZero();
    return posted;
  }

  // failures, outages and limits, on simulated time

  /// Sent records and bytes, and the circuit breaker metrics of an outage and its recovery
  @Test
  void metricsOfAnOutage() {
    simulated(simulatedConfig());
    long start = System.currentTimeMillis();
    new OtlpRequestLogger(destination).logRequest(request("/a"));
    clock.advanceMillis(FLUSH_MILLIS);
    // each post is open for 1 ms of the sender's clock
    clock.advanceMillis(1);
    answer(503);
    clock.advanceMillis(RETRY_MILLIS);
    assertThat(posted()).as("the probe").isEqualTo(2);
    clock.advanceMillis(1);
    answer(200);
    assertThat(counter("sensepitch_request_log_sent_records")).isOne();
    assertThat(counter("sensepitch_request_log_sent_bytes"))
        .as("the request body")
        .isEqualTo(deliveredBytes);
    assertThat(breakerCounter("calls", "kind", "successful")).isOne();
    assertThat(breakerCounter("calls", "kind", "failed")).isOne();
    assertThat(breakerCounter("calls", "kind", "ignored")).isZero();
    assertThat(breakerCounter("errors", "error", "http_503")).isOne();
    HistogramSnapshot.HistogramDataPointSnapshot postDuration =
        (HistogramSnapshot.HistogramDataPointSnapshot)
            scrape()
                .datapoint(
                    "sensepitch_request_log_post_duration_seconds",
                    RequestLogMetrics.DESTINATION_LABEL,
                    destination.destination());
    assertThat(postDuration.getCount()).as("the failed and the successful post").isEqualTo(2);
    // the probe goes out when the backoff with jitter ends, somewhere before the clock stopped
    assertThat(postDuration.getSum())
        .as("seconds of both posts, each open at least 1 ms")
        .isGreaterThanOrEqualTo(0.002);
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
    // the breaker reads these from the wall clock, not from the simulated time
    double trip = breakerGauge("last_trip_time_seconds");
    assertThat(breakerGauge("start_time_seconds")).isBetween(start / 1000.0 - 1, trip);
    assertThat(trip).isGreaterThanOrEqualTo(start / 1000.0);
    assertThat(breakerGauge("last_restart_time_seconds")).isGreaterThanOrEqualTo(trip);
    finishAndDeliver();
    assertThat(scrape().containsSnapshot(CircuitBreakerMetrics.PREFIX + "state").getDataPoints())
        .as("gauges of a stopped breaker are gone")
        .isEmpty();
    assertThat(breakerCounter("calls", "kind", "successful")).as("counters stay").isOne();
  }

  /// A `Retry-After` of 30 s wins over the millisecond backoff: after the short backoff has
  /// passed, the batch still waits, and the probe goes out only at the requested delay. The real
  /// HTTP path checks the same behaviour in [OtlpLogDestinationIT#retryAfterIsHonoured].
  @Test
  void retryAfterWinsOverTheBackoff() {
    simulated(simulatedConfig());
    new OtlpRequestLogger(destination).logRequest(request("/a"));
    clock.advanceMillis(FLUSH_MILLIS);
    assertThat(posted()).isOne();
    answer(429, Integer.toString(RETRY_AFTER_SECONDS));
    assertThat(counter("sensepitch_request_log_export_retries")).isOne();
    // 1 ms before the requested delay, the short backoff would long have fired the probe
    clock.advanceMillis(TimeUnit.SECONDS.toMillis(RETRY_AFTER_SECONDS) - 1);
    assertThat(posted()).as("no probe 1 ms before the requested delay").isOne();
    clock.advanceMillis(1);
    assertThat(posted()).as("the probe after the requested delay").isEqualTo(2);
    answer(200);
    assertThat(delivered).containsExactly("/a");
    assertThat(counter("sensepitch_request_log_sent_records")).isOne();
    assertThat(counter("sensepitch_request_log_export_retries")).isOne();
    assertThat(destination.droppedCount()).isZero();
  }

  /// An in-flight reply during maintenance is counted without announcing recovery.
  @ParameterizedTest
  @ValueSource(ints = {200, 503})
  void replyDuringMaintenanceLeavesTheBreakerForcedOpen(int firstStatus) {
    simulated(simulatedConfig());
    CircuitBreaker breaker =
        destination.metrics().circuitBreakers.find(HttpBatchSender.BREAKER_COMPONENT, "otlp");
    new OtlpRequestLogger(destination).logRequest(request("/in-flight"));
    destination.endThread();
    assertThat(posted()).isOne();
    breaker.forceOpen();
    answer(firstStatus);
    String kind = firstStatus == 200 ? "successful" : "failed";
    assertThat(breakerCounter("calls", "kind", kind)).isOne();
    assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.FORCED_OPEN);
    assertThat(breaker.forceClose()).isTrue();
    CompletableFuture<Void> stopped = destination.finish();
    clock.advanceMillis(RETRY_MILLIS);
    answerAll(200);
    assertThat(stopped).isDone();
    assertThat(counter("sensepitch_request_log_sent_records")).isOne();
    assertThat(logs.messages(OtlpLogDestination.class))
        .noneMatch(message -> message.contains("recovered after"));
  }

  /// A status that retrying cannot fix gives up the batch, it is not sent again
  @Test
  void badRequestGivesUpTheBatch() {
    simulated(simulatedConfig().flushIntervalMillis(OUT_OF_REACH_MILLIS));
    OtlpRequestLogger logger = new OtlpRequestLogger(destination);
    logger.logRequest(request("/a"));
    logger.logRequest(request("/b"));
    destination.endThread();
    CompletableFuture<Void> stopped = destination.finish();
    answer(400);
    clock.advanceMillis(RETRY_MILLIS);
    assertThat(posted()).as("not retried").isOne();
    assertThat(stopped).isDone();
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
  void exportContinuesAfterGivingUp() {
    simulated(simulatedConfig());
    OtlpRequestLogger logger = new OtlpRequestLogger(destination);
    logger.logRequest(request("/lost"));
    clock.advanceMillis(FLUSH_MILLIS);
    answer(400);
    logger.logRequest(request("/sent"));
    clock.advanceMillis(FLUSH_MILLIS);
    answer(200);
    assertThat(delivered).containsExactly("/sent");
  }

  /// A batch given up is logged once, further ones are only counted
  @Test
  void givingUpIsLoggedOnce() {
    simulated(simulatedConfig());
    OtlpRequestLogger logger = new OtlpRequestLogger(destination);
    logger.logRequest(request("/bad1"));
    clock.advanceMillis(FLUSH_MILLIS);
    answer(400);
    logger.logRequest(request("/bad2"));
    clock.advanceMillis(FLUSH_MILLIS);
    answer(400);
    assertThat(counter("sensepitch_request_log_export_failed")).isEqualTo(2);
    assertThat(logs.messages(OtlpLogDestination.class))
        .filteredOn(m -> m.contains("failed, dropped a batch"))
        .hasSize(1);
  }

  /// A status that retrying cannot fix does not open the breaker, the next batch goes out at the
  /// next flush and does not wait for a backoff
  @Test
  void badRequestDoesNotOpenTheBreaker() {
    simulated(simulatedConfig());
    OtlpRequestLogger logger = new OtlpRequestLogger(destination);
    logger.logRequest(request("/bad"));
    clock.advanceMillis(FLUSH_MILLIS);
    answer(400);
    logger.logRequest(request("/good"));
    clock.advanceMillis(FLUSH_MILLIS);
    assertThat(posted()).as("sent before a backoff could be over").isEqualTo(2);
    answer(200);
    assertThat(delivered).containsExactly("/good");
  }

  /// A probe answered with a status that retrying cannot fix ends the outage: the server is up,
  /// the next batch goes out at the next flush instead of after the probe timeout
  @Test
  void badRequestOfTheProbeEndsTheOutage() {
    simulated(simulatedConfig());
    OtlpRequestLogger logger = new OtlpRequestLogger(destination);
    logger.logRequest(request("/a"));
    clock.advanceMillis(FLUSH_MILLIS);
    answer(503);
    clock.advanceMillis(RETRY_MILLIS);
    assertThat(posted()).as("the probe").isEqualTo(2);
    answer(400);
    assertThat(counter("sensepitch_request_log_export_failed")).isOne();
    logger.logRequest(request("/b"));
    clock.advanceMillis(FLUSH_MILLIS);
    assertThat(posted()).isEqualTo(3);
    answer(200);
    assertThat(delivered).containsExactly("/b");
    assertThat(
            breakerCounter(
                "transitions", "from", "half_open", "to", "closed", "reason", "recovered"))
        .isOne();
    assertThat(logs.messages(OtlpLogDestination.class))
        .anySatisfy(m -> assertThat(m).contains("recovered after 1 failed attempts"));
  }

  /// A finished destination keeps its records during an outage, they are reported as unsent
  @Test
  void finishDuringAnOutage() {
    simulated(simulatedConfig().flushIntervalMillis(OUT_OF_REACH_MILLIS));
    OtlpRequestLogger logger = new OtlpRequestLogger(destination);
    for (int i = 0; i < 10; i++) {
      logger.logRequest(request("/" + i));
    }
    logger.endThread();
    CompletableFuture<Void> stopped = destination.finish();
    refuseNext();
    clock.advanceMillis(RETRY_MILLIS);
    refuseNext();
    assertThat(counter("sensepitch_request_log_export_retries")).isPositive();
    assertThat(stopped).isNotDone();
    assertThat(destination.queueSize()).isEqualTo(10);
    assertThat(destination.droppedCount()).isZero();
  }

  /// When the log server stays down, the waiting batches are limited. The newest records are
  /// dropped, the oldest are kept and sent when the server is back.
  @Test
  void limitDropsTheNewestRecords() {
    int batchBytes = OtlpConfig.MIN_BATCH_BYTES;
    OtlpConfig.Builder config =
        simulatedConfig()
            .batchBytes(batchBytes)
            // one request at a time, so the batches arrive in order
            .maxConcurrentRequests(1);
    long limit = config.build().maxBufferedBytes();
    simulated(config);
    OtlpRequestLogger logger = new OtlpRequestLogger(destination);
    // a few batches more than the limit holds, while the first post is not answered
    int total = (int) ((limit + 4L * batchBytes) / framedSize(request("/000000")));
    for (int i = 0; i < total; i++) {
      logger.logRequest(request(String.format("/%06d", i)));
    }
    assertThat(destination.droppedCount()).isPositive();
    assertThat(bufferedBytesGauge()).isLessThanOrEqualTo(limit);
    destination.endThread();
    CompletableFuture<Void> stopped = destination.finish();
    answer(503);
    clock.advanceMillis(RETRY_MILLIS);
    answerAll(200);
    assertThat(stopped).isDone();
    assertThat(delivered.size() + destination.droppedCount()).isEqualTo(total);
    assertThat(delivered).as("the oldest are kept").startsWith("/000000", "/000001").isSorted();
  }

  /// Batches taken during an outage are released again after it, only a few stay in the pool
  @Test
  void poolShrinksAfterAnOutage() {
    int batchBytes = OtlpConfig.MIN_BATCH_BYTES;
    // room for all batches, also on machines with few event loops
    simulated(simulatedConfig().batchBytes(batchBytes).maxBufferedBytes(64 << 20));
    OtlpRequestLogger logger = new OtlpRequestLogger(destination);
    int total = 20 * batchBytes / framedSize(request("/0"));
    for (int i = 0; i < total; i++) {
      logger.logRequest(request("/" + i));
    }
    answerAll(503);
    assertThat(bufferedBytesGauge()).isGreaterThan(10L * batchBytes);
    clock.advanceMillis(RETRY_MILLIS);
    answerAll(200);
    clock.advanceMillis(FLUSH_MILLIS);
    answerAll(200);
    assertThat(delivered).hasSize(total);
    assertThat(bufferedBytesGauge()).isZero();
    assertThat(destination.pooledBatches()).as("one thread plus a spare").isLessThanOrEqualTo(2);
  }

  /// A thread that reached the limit takes records again once the waiting batches were sent
  @Test
  void recordsAreAcceptedAgainWhenThereIsRoom() {
    int batchBytes = OtlpConfig.MIN_BATCH_BYTES;
    OtlpConfig.Builder config =
        simulatedConfig().batchBytes(batchBytes).flushIntervalMillis(OUT_OF_REACH_MILLIS);
    long limit = config.build().maxBufferedBytes();
    simulated(config);
    OtlpRequestLogger logger = new OtlpRequestLogger(destination);
    // two batches more than the limit holds, while the posts are not answered
    int total = (int) ((limit + 2L * batchBytes) / framedSize(request("/0")));
    for (int i = 0; i < total; i++) {
      logger.logRequest(request("/" + i));
    }
    long dropped = destination.droppedCount();
    assertThat(dropped).as("limit reached").isPositive();
    answerAll(200);
    logger.logRequest(request("/again"));
    finishAndDeliver();
    assertThat(delivered).contains("/again");
    assertThat(destination.droppedCount()).isEqualTo(dropped);
  }

  /// After a failure the window is one request, it grows with each success back to
  /// `maxConcurrentRequests`. The requests in flight at each step are checked by
  /// [OtlpSlowStartIT].
  @Test
  void windowIsOneAfterAFailureAndGrowsBack() {
    int batchBytes = OtlpConfig.MIN_BATCH_BYTES;
    simulated(
        simulatedConfig()
            .batchBytes(batchBytes)
            .maxBufferedBytes(64 << 20)
            .maxConcurrentRequests(8)
            .flushIntervalMillis(OUT_OF_REACH_MILLIS));
    assertThat(destination.window()).as("initially").isEqualTo(8);
    OtlpRequestLogger logger = new OtlpRequestLogger(destination);
    int total = 10 * (batchBytes / framedSize(request("/0")) + 1);
    for (int i = 0; i < total; i++) {
      logger.logRequest(request("/" + i));
    }
    assertThat(posted()).as("a window full").isEqualTo(8);
    answer(503);
    assertThat(destination.window()).as("after a failure").isOne();
    destination.endThread();
    CompletableFuture<Void> stopped = destination.finish();
    answerAll(503);
    clock.advanceMillis(RETRY_MILLIS);
    answerAll(200);
    assertThat(stopped).isDone();
    assertThat(new HashSet<>(delivered)).hasSize(total);
    assertThat(destination.droppedCount()).isZero();
    assertThat(destination.window()).as("back at the maximum").isEqualTo(8);
  }
}
