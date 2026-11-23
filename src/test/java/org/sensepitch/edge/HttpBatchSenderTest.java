package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.sensepitch.edge.TestingParameters.OUT_OF_REACH_MILLIS;
import static org.sensepitch.edge.TestingParameters.TIMEOUT_SECONDS;

import com.sun.net.httpserver.HttpServer;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.util.concurrent.Ticker;
import io.prometheus.metrics.model.snapshots.GaugeSnapshot;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/// The posting does not depend on the payload format: a format of plain text lines, one per
/// record, goes through the same [HttpBatchSender] and [BatchingRequestLogger] as OTLP.
///
/// @author Jens Wilke
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class HttpBatchSenderTest {

  static final long FLUSH_MILLIS = 100;
  static final long AGE_MILLIS = 200;
  static final long INITIAL_RETRY_MILLIS = 10;

  /// Records as text lines
  static final class TextBatch extends Batch {

    TextBatch(Ticker clock) {
      super(clock);
    }

    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

    @Override
    int size() {
      return bytes.size();
    }

    @Override
    void clear() {
      bytes.reset();
    }
  }

  /// One line with the path per record, the body is the lines of all batches
  static final class TextPayload implements BatchPayload<TextBatch> {

    private final Ticker clock;

    TextPayload(Ticker clock) {
      this.clock = clock;
    }

    @Override
    public TextBatch newBatch(int batchBytes) {
      return new TextBatch(clock);
    }

    @Override
    public RecordEncoder<TextBatch> newEncoder() {
      return (batch, info) ->
          batch.bytes.writeBytes((info.request().uri() + "\n").getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void writeBody(List<TextBatch> batches, BodySink sink) {
      for (TextBatch batch : batches) {
        byte[] bytes = batch.bytes.toByteArray();
        sink.write(bytes, 0, bytes.length);
      }
    }

    @Override
    public String contentType() {
      return "text/plain";
    }
  }

  /// Sender of text lines, with a short backoff
  static HttpBatchSender<TextBatch> textSender(String endpoint, Map<String, String> headers) {
    return textSender(endpoint, headers, OtlpConfig.MIN_BATCH_BYTES, 64L * 1024 * 1024);
  }

  static HttpBatchSender<TextBatch> textSender(
      String endpoint, Map<String, String> headers, int batchBytes, long maxBufferedBytes) {
    return textSender(endpoint, headers, batchBytes, maxBufferedBytes, 2);
  }

  static HttpBatchSender<TextBatch> textSender(
      String endpoint,
      Map<String, String> headers,
      int batchBytes,
      long maxBufferedBytes,
      int maxConcurrentRequests) {
    return textSender(
        endpoint, headers, batchBytes, maxBufferedBytes, maxConcurrentRequests, null, null, null);
  }

  /// Sender of text lines on a fake clock, a timer the test moves and a transport whose answers
  /// the test completes
  static HttpBatchSender<TextBatch> textSender(
      String endpoint,
      Map<String, String> headers,
      int batchBytes,
      long maxBufferedBytes,
      int maxConcurrentRequests,
      Ticker clock,
      HttpBatchSender.Timer timer,
      HttpBatchSender.Transport transport) {
    return new HttpBatchSender<>(
        new HttpBatchSender.Settings(
            "text",
            URI.create(endpoint),
            headers,
            Duration.ofSeconds(5),
            batchBytes,
            maxBufferedBytes,
            maxConcurrentRequests,
            OUT_OF_REACH_MILLIS,
            OUT_OF_REACH_MILLIS,
            false,
            OtlpConfig.DEFAULT_COMPRESSION_LEVEL,
            new Backoff(10, 50, bound -> 0)),
        new TextPayload(clock == null ? Ticker.systemTicker() : clock),
        new RequestLogMetrics(),
        clock,
        timer,
        transport);
  }

  /// Ends the batcher of the calling thread, finishes the sender and waits until it stopped, the
  /// way a shutdown does it for an event loop
  static void close(HttpBatchSender<?> sender) throws Exception {
    sender.endThread();
    sender.finish().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
  }

  /// Destinations the queue gauges of the metrics report
  static List<String> gaugedDestinations(RequestLogMetrics metrics) {
    List<String> destinations = new ArrayList<>();
    metrics.registerCollectors(
        collector -> {
          if (collector.collect() instanceof GaugeSnapshot gauge) {
            for (GaugeSnapshot.GaugeDataPointSnapshot point : gauge.getDataPoints()) {
              String destination = point.getLabels().get(RequestLogMetrics.DESTINATION_LABEL);
              if (destination != null) {
                destinations.add(destination);
              }
            }
          }
        });
    return destinations;
  }

  /// No log server listens on port 1, connecting fails at once
  static final String UNREACHABLE = "http://127.0.0.1:1/lines";

  static TestRequestLogInfo request(String path) {
    TestRequestLogInfo info = TestRequestLogInfo.of(HttpMethod.GET, 200);
    info.request().setUri(path);
    return info;
  }

  /// Whoever waits for [HttpBatchSender#stopped()] sees a released sender: the compression thread
  /// is shut down, and neither the queue gauges nor the breaker gauges report it anymore. A
  /// dependent action runs in the thread that completes the future, so it sees the state at the
  /// moment of completion.
  @Test
  void stoppedCompletesAfterTheSenderIsReleased() throws Exception {
    RequestLogMetrics metrics = new RequestLogMetrics();
    HttpBatchSender<TextBatch> sender =
        new HttpBatchSender<>(
            new HttpBatchSender.Settings(
                "text",
                URI.create(UNREACHABLE),
                Map.of(),
                Duration.ofSeconds(5),
                OtlpConfig.MIN_BATCH_BYTES,
                64L * 1024 * 1024,
                2,
                OUT_OF_REACH_MILLIS,
                OUT_OF_REACH_MILLIS,
                true,
                OtlpConfig.DEFAULT_COMPRESSION_LEVEL,
                new Backoff(10, 50)),
            new TextPayload(Ticker.systemTicker()),
            metrics);
    assertThat(sender.compressionExecutorStopped()).isFalse();
    assertThat(gaugedDestinations(metrics)).contains("text");
    assertThat(metrics.circuitBreakers.find(HttpBatchSender.BREAKER_COMPONENT, "text")).isNotNull();
    CompletableFuture<List<Object>> seen =
        sender
            .stopped()
            .thenApply(
                ignored ->
                    List.of(
                        sender.compressionExecutorStopped(),
                        gaugedDestinations(metrics),
                        metrics.circuitBreakers.find(HttpBatchSender.BREAKER_COMPONENT, "text")
                            == null));
    close(sender);
    assertThat(seen.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        .as("executor stopped, gauged destinations, breaker removed when stopped completes")
        .containsExactly(true, List.of(), true);
  }

  /// The flush timer runs in the test thread, collects the partial batch and posts it through the
  /// controlled transport, without a server, timer or wait on another thread.
  @Test
  void flushRunsOnTheFakeTimerAndPostsAPartialBatch() {
    FakeTime clock = new FakeTime();
    ControlledTransport transport = new ControlledTransport();
    HttpBatchSender<TextBatch> sender = deterministicSender(clock, transport, 1_000);
    ThreadBatcher<TextBatch> batcher = sender.newBatcher();
    batcher.log(request("/flushed"));
    assertThat(transport.requests()).as("nothing posts before the interval").isEmpty();
    clock.advanceMillis(FLUSH_MILLIS);
    assertThat(batcher.records()).as("the flush took the batch").isZero();
    assertThat(transport.onlyRequest().uri().getPath()).isEqualTo("/lines");
    transport.succeed(204);
    batcher.close();
    sender.finish();
    assertThat(sender.stopped()).isDone();
  }

  /// A batch parked behind the only request in flight is dropped and counted when it is older
  /// than the age limit at a flush; no request has to be posted to discover that.
  @Test
  void flushDropsAnAgedBatchOnTheFakeClock() {
    FakeTime clock = new FakeTime();
    ControlledTransport transport = new ControlledTransport();
    HttpBatchSender<TextBatch> sender = deterministicSender(clock, transport, 4);
    ThreadBatcher<TextBatch> firstBatcher = sender.newBatcher();
    firstBatcher.log(request("/a"));
    firstBatcher.log(request("/b"));
    ThreadBatcher<TextBatch> secondBatcher = sender.newBatcher();
    secondBatcher.log(request("/aged"));
    assertThat(transport.requests()).as("the full batch posts right away").hasSize(1);
    clock.advanceMillis(FLUSH_MILLIS);
    assertThat(transport.requests()).as("the only request slot is taken").hasSize(1);
    clock.advanceMillis(AGE_MILLIS + 1);
    assertThat(sender.droppedCount())
        .as("the parked batch was older than the age limit")
        .isEqualTo(1);
    assertThat(sender.bufferedBytes())
        .as("only the in-flight batch is buffered")
        .isEqualTo("/a\n/b\n".length());
    firstBatcher.close();
    secondBatcher.close();
    sender.finish();
    assertThat(sender.stopped()).as("the in-flight request still blocks the sender").isNotDone();
    transport.succeed(204);
    assertThat(sender.stopped()).isDone();
  }

  /// After a retryable answer the timer schedules one probe after the backoff. When it fires, the
  /// breaker lets exactly that request through and a successful answer drains the queue.
  @Test
  void probeIsScheduledOnTheFakeTimer() {
    FakeTime clock = new FakeTime();
    ControlledTransport transport = new ControlledTransport();
    HttpBatchSender<TextBatch> sender = deterministicSender(clock, transport, 3);
    ThreadBatcher<TextBatch> batcher = sender.newBatcher();
    batcher.log(request("/a"));
    batcher.log(request("/b"));
    batcher.close();
    sender.finish();
    assertThat(transport.requests()).hasSize(1);
    transport.fail(new java.net.ConnectException("refused"));
    // The deterministic random provider selects the lower half of the nominal retry delay.
    clock.advanceMillis(INITIAL_RETRY_MILLIS / 2 - 1);
    assertThat(transport.requests()).hasSize(1);
    clock.advanceMillis(1);
    assertThat(transport.requests()).as("the probe goes out after the backoff").hasSize(2);
    transport.fail(new java.net.ConnectException("refused"));
    clock.advanceMillis(INITIAL_RETRY_MILLIS - 1);
    assertThat(transport.requests()).hasSize(2);
    clock.advanceMillis(1);
    assertThat(transport.requests()).as("one probe after the next backoff").hasSize(3);
    transport.succeed(204);
    assertThat(transport.requests())
        .as("the successful probe releases the queued batch")
        .hasSize(4);
    transport.succeed(204);
    assertThat(sender.droppedCount()).isZero();
    assertThat(sender.stopped()).isDone();
  }

  /// The payload keeps its production default when it is created without the test clock, and
  /// stamps batches with the clock it is given otherwise
  @Test
  void otlpPayloadClockDefaultsToTheSystemTicker() {
    OtlpBatchPayload defaultPayload = new OtlpBatchPayload(Map.of(), 0);
    OtlpBatch defaultBatch = defaultPayload.newBatch(4_096);
    defaultBatch.added();
    assertThat(defaultBatch.firstRecordNanos).isPositive();

    FakeTime clock = new FakeTime();
    OtlpBatchPayload deterministicPayload = new OtlpBatchPayload(Map.of(), 0, clock);
    OtlpBatch deterministicBatch = deterministicPayload.newBatch(4_096);
    deterministicBatch.added();
    assertThat(deterministicBatch.firstRecordNanos).isEqualTo(clock.nanoTime());
  }

  private static HttpBatchSender<TextBatch> deterministicSender(
      FakeTime clock, ControlledTransport transport, int batchBytes) {
    return new HttpBatchSender<>(
        new HttpBatchSender.Settings(
            "text",
            URI.create(UNREACHABLE),
            Map.of(),
            Duration.ofSeconds(5),
            batchBytes,
            1024,
            1,
            AGE_MILLIS,
            FLUSH_MILLIS,
            false,
            OtlpConfig.DEFAULT_COMPRESSION_LEVEL,
            new Backoff(INITIAL_RETRY_MILLIS, 50, bound -> 0)),
        new TextPayload(clock),
        new RequestLogMetrics(),
        clock,
        clock.scheduler()::schedule,
        transport);
  }

  @Test
  void statusOutcomes() {
    assertThat(HttpBatchSender.result(199, 0).outcome()).isEqualTo(HttpBatchSender.Outcome.GIVE_UP);
    assertThat(HttpBatchSender.result(200, 0)).isSameAs(HttpBatchSender.Result.SENT);
    assertThat(HttpBatchSender.result(299, 0)).isSameAs(HttpBatchSender.Result.SENT);
    assertThat(HttpBatchSender.result(300, 0).outcome()).isEqualTo(HttpBatchSender.Outcome.GIVE_UP);
    assertThat(HttpBatchSender.result(400, 0).outcome()).isEqualTo(HttpBatchSender.Outcome.GIVE_UP);
    for (int status : new int[] {429, 502, 503, 504}) {
      HttpBatchSender.Result result = HttpBatchSender.result(status, 7);
      assertThat(result.outcome()).as("status " + status).isEqualTo(HttpBatchSender.Outcome.RETRY);
      assertThat(result.retryAfterMillis()).isEqualTo(7);
      assertThat(result.reason()).isEqualTo("HTTP status " + status);
      assertThat(result.error()).isEqualTo("http_" + status);
    }
    assertThat(HttpBatchSender.result(400, 0).error()).isEqualTo("http_400");
  }

  /// The flush packs partly filled batches in the order the threads handed them over, so a post
  /// may start with a newer batch. [HttpBatchSender.Post#of] then keeps the age of the oldest
  /// record, the branch that sees a batch older than the first one. Without a deterministic
  /// caller the branch was only covered by a thread race in `OtlpLogDestinationTest`, which made
  /// the `HttpBatchSender*` full-coverage rule flake.
  @Test
  void postKeepsTheAgeOfTheOldestRecord() {
    TextBatch newer = new TextBatch(Ticker.systemTicker());
    newer.added();
    newer.firstRecordNanos = 200;
    TextBatch older = new TextBatch(Ticker.systemTicker());
    older.added();
    older.firstRecordNanos = 100;
    HttpBatchSender.Post<TextBatch> post = HttpBatchSender.Post.of(List.of(newer, older));
    assertThat(post.firstRecordNanos()).as("age of the oldest record").isEqualTo(100);
    assertThat(post.records()).isEqualTo(2);
  }

  /// The short error codes of the metrics, see [CircuitBreakerMetrics]
  @Test
  void errorCodes() {
    assertThat(HttpBatchSender.failure(new java.net.ConnectException("refused")).error())
        .isEqualTo("connect");
    assertThat(
            HttpBatchSender.failure(new java.net.http.HttpConnectTimeoutException("slow")).error())
        .isEqualTo("connect");
    assertThat(HttpBatchSender.failure(new java.net.http.HttpTimeoutException("slow")).error())
        .isEqualTo("timeout");
    assertThat(HttpBatchSender.failure(new java.io.IOException("reset")).error()).isEqualTo("io");
    assertThat(HttpBatchSender.failure(new IllegalStateException("bug")).error())
        .isEqualTo("unexpected");
  }

  @Test
  void failureOutcomes() {
    java.io.IOException refused = new java.net.ConnectException("refused");
    assertThat(HttpBatchSender.failure(refused).outcome()).isEqualTo(HttpBatchSender.Outcome.RETRY);
    assertThat(HttpBatchSender.failure(new java.util.concurrent.CompletionException(refused)))
        .as("unwrapped")
        .extracting(HttpBatchSender.Result::outcome, HttpBatchSender.Result::reason)
        .containsExactly(HttpBatchSender.Outcome.RETRY, refused.toString());
    assertThat(HttpBatchSender.failure(new IllegalStateException("bug")))
        .extracting(HttpBatchSender.Result::outcome, HttpBatchSender.Result::reason)
        .containsExactly(
            HttpBatchSender.Outcome.GIVE_UP, "unexpected java.lang.IllegalStateException: bug");
  }

  /// A header the HTTP client refuses is a configuration error, reported at construction
  @Test
  void invalidHeaderIsRejected() {
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> textSender(UNREACHABLE, Map.of("Content-Length", "1")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageStartingWith("Request log destination text: invalid header Content-Length");
  }

  /// A thread that sets up its batcher after the sender finished gets a closed one, which refuses
  /// records and takes no batch
  @Test
  void batcherCreatedAfterFinishIsClosed() {
    HttpBatchSender<TextBatch> sender = textSender(UNREACHABLE, Map.of());
    sender.finish();
    ThreadBatcher<TextBatch> batcher = sender.newBatcher();
    assertThat(batcher.log(request("/late"))).isFalse();
    assertThat(batcher.records()).isZero();
    assertThat(sender.pooledBatches()).isZero();
  }

  /// The flush timer takes a partly filled batch only if it has records and the waiting batches
  /// have room for it. Each line is 3 bytes, a batch of 4 bytes is full with the second.
  @Test
  void partialBatchNeedsRecordsAndRoom() throws Exception {
    BlockingQueue<String> bodies = new LinkedBlockingQueue<>();
    HttpServer server = lineServer(bodies);
    try {
      HttpBatchSender<TextBatch> roomy = textSender(endpoint(server), Map.of(), 4, 1_000, 1);
      ThreadBatcher<TextBatch> batcher = roomy.newBatcher();
      assertThat(batcher.takePartialAndQueue()).as("no batch yet").isNull();
      batcher.log(request("/a"));
      assertThat(batcher.takePartialAndQueue().records).isOne();
      assertThat(batcher.takePartialAndQueue()).as("taken already").isNull();
      batcher.log(request("/b"));
      batcher.log(request("/c"));
      assertThat(batcher.records()).as("full batch handed off").isZero();
      assertThat(batcher.takePartialAndQueue()).as("empty batch").isNull();
      roomy.endThread();
      roomy.finish().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertThat(bodies.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEqualTo("/a\n");
      assertThat(bodies.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEqualTo("/b\n/c\n");

      HttpBatchSender<TextBatch> full = textSender(endpoint(server), Map.of(), 4, 4, 1);
      batcher = full.newBatcher();
      batcher.log(request("/a"));
      batcher.log(request("/b"));
      assertThat(batcher.records()).as("hand-off refused, the full batch is kept").isEqualTo(2);
      assertThat(batcher.takePartialAndQueue()).as("no room").isNull();
      full.endThread();
      full.finish().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertThat(bodies.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEqualTo("/a\n/b\n");
      assertThat(bodies).isEmpty();
    } finally {
      TestingParameters.stop(server);
    }
  }

  /// A batch taken by the flush is queued at once. A full batch this thread hands over right after
  /// it cannot overtake it, so one request at a time delivers the records in order. Collecting the
  /// partials first and submitting them after let the next full batch go out ahead of them; the
  /// controller saw records 898-981 arrive before 850-897 in `OtlpLogDestinationIT`.
  @Test
  void flushPartialKeepsItsPlaceBeforeLaterFullBatches() throws Exception {
    BlockingQueue<String> bodies = new LinkedBlockingQueue<>();
    HttpServer server = lineServer(bodies);
    try {
      HttpBatchSender<TextBatch> sender = textSender(endpoint(server), Map.of(), 8, 1_000, 1);
      ThreadBatcher<TextBatch> batcher = sender.newBatcher();
      batcher.log(request("/a"));
      assertThat(batcher.takePartialAndQueue()).as("taken by the flush").isNotNull();
      batcher.log(request("/b"));
      batcher.log(request("/c"));
      batcher.log(request("/d"));
      batcher.log(request("/e"));
      assertThat(batcher.takePartialAndQueue()).as("a later partial").isNotNull();
      batcher.close();
      sender.finish().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertThat(bodies.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEqualTo("/a\n");
      assertThat(bodies.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEqualTo("/b\n/c\n/d\n");
      assertThat(bodies.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEqualTo("/e\n");
      assertThat(bodies).isEmpty();
    } finally {
      TestingParameters.stop(server);
    }
  }

  /// The sender stops once it is finishing, all its batchers are closed and all is sent. A
  /// batcher may close before or after the sender finishes, and more than once.
  @Test
  void stopsAfterTheEndMarkersOfAllThreads() throws Exception {
    BlockingQueue<String> bodies = new LinkedBlockingQueue<>();
    HttpServer server = lineServer(bodies);
    try {
      HttpBatchSender<TextBatch> sender = textSender(endpoint(server), Map.of());
      assertThat(sender.finish()).as("no batchers, stops at once").isDone();
      HttpBatchSender<TextBatch> busy = textSender(endpoint(server), Map.of());
      ThreadBatcher<TextBatch> early = busy.newBatcher();
      ThreadBatcher<TextBatch> late = busy.newBatcher();
      early.log(request("/early"));
      late.log(request("/late"));
      early.close();
      CompletableFuture<Void> stopped = busy.finish();
      assertThat(bodies.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEqualTo("/early\n");
      assertThat(stopped).as("one batcher still open").isNotDone();
      assertThat(late.log(request("/accepted"))).as("open batchers still log").isTrue();
      late.close();
      late.close();
      stopped.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertThat(bodies.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEqualTo("/late\n/accepted\n");
      assertThat(busy.droppedCount()).isZero();
    } finally {
      TestingParameters.stop(server);
    }
  }

  static HttpServer lineServer(BlockingQueue<String> bodies) throws java.io.IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/lines",
        exchange -> {
          bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          exchange.sendResponseHeaders(204, -1);
          exchange.close();
        });
    server.start();
    return server;
  }

  static String endpoint(HttpServer server) {
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/lines";
  }

  @Test
  void textLinesArePosted() throws Exception {
    BlockingQueue<String> bodies = new LinkedBlockingQueue<>();
    BlockingQueue<String> contentTypes = new LinkedBlockingQueue<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/lines",
        exchange -> {
          bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          contentTypes.add(exchange.getRequestHeaders().getFirst("Content-Type"));
          exchange.sendResponseHeaders(204, -1);
          exchange.close();
        });
    server.start();
    try {
      HttpBatchSender<TextBatch> sender =
          textSender("http://127.0.0.1:" + server.getAddress().getPort() + "/lines", Map.of());
      BatchingRequestLogger<TextBatch> logger = new BatchingRequestLogger<>(sender);
      for (String path : List.of("/a", "/b", "/c")) {
        logger.logRequest(request(path));
      }
      logger.close();
      sender.stopped().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertThat(bodies.poll()).isEqualTo("/a\n/b\n/c\n");
      assertThat(contentTypes.poll()).isEqualTo("text/plain");
      assertThat(sender.droppedCount()).isZero();
    } finally {
      TestingParameters.stop(server);
    }
  }
}
