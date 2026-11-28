package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.sensepitch.edge.OtlpLogDestinationTest.awaitQuietly;
import static org.sensepitch.edge.OtlpLogDestinationTest.framedSize;
import static org.sensepitch.edge.OtlpTestSupport.decode;
import static org.sensepitch.edge.TestRequestLogInfo.request;
import static org.sensepitch.edge.TestingParameters.EVENT_TIMEOUT;
import static org.sensepitch.edge.TestingParameters.EVENT_TIMEOUT_SECONDS;
import static org.sensepitch.edge.TestingParameters.OUT_OF_REACH_MILLIS;
import static org.sensepitch.edge.TestingParameters.TEST_TIMEOUT_SECONDS;
import static org.sensepitch.edge.TestingParameters.await;

import com.sun.net.httpserver.HttpServer;
import io.netty.handler.codec.http.HttpMethod;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.parallel.Isolated;

/// Real HTTP, timer and concurrency checks that repeat coverage of [OtlpLogDestinationTest].
/// New scenarios belong in that unit test, which also supplies this class's server and helpers.
@Timeout(TEST_TIMEOUT_SECONDS)
// alone: captures the log of all threads, the sender logs from its own
@Isolated
class OtlpLogDestinationIT {

  final OtlpLogDestinationTest fixture = new OtlpLogDestinationTest();

  @RegisterExtension LogCapture logs = LogCapture.ofAllThreads();

  @BeforeEach
  void startServer() throws IOException {
    fixture.startServer();
  }

  @AfterEach
  void stop() {
    fixture.stop();
  }

  /// Full batches are posted concurrently, at most `maxConcurrentRequests` at a time
  @Test
  void concurrentRequestsUpToTheLimit() throws Exception {
    int limit = 3;
    AtomicInteger inFlight = new AtomicInteger();
    AtomicInteger maxInFlight = new AtomicInteger();
    AtomicInteger records = new AtomicInteger();
    CountDownLatch limitReached = new CountDownLatch(limit);
    CountDownLatch release = new CountDownLatch(1);
    HttpServer slow = Loopback.httpServer();
    slow.setExecutor(Executors.newCachedThreadPool());
    slow.createContext(
        "/v1/logs",
        exchange -> {
          byte[] body = exchange.getRequestBody().readAllBytes();
          maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
          limitReached.countDown();
          awaitQuietly(release);
          inFlight.decrementAndGet();
          records.addAndGet(
              decode(exchange, body).getResourceLogs(0).getScopeLogs(0).getLogRecordsCount());
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    slow.start();
    try {
      int batchBytes = OtlpConfig.MIN_BATCH_BYTES;
      fixture.destination =
          new OtlpLogDestination(
              "otlp",
              OtlpConfig.builder()
                  .endpoint("http://127.0.0.1:" + slow.getAddress().getPort() + "/v1/logs")
                  .batchBytes(batchBytes)
                  .maxBufferedBytes(64 << 20)
                  .maxConcurrentRequests(limit)
                  .flushIntervalMillis(OUT_OF_REACH_MILLIS)
                  .build());
      OtlpRequestLogger logger = new OtlpRequestLogger(fixture.destination);
      int total = 6 * batchBytes / framedSize(request("/0")) + 1;
      for (int i = 0; i < total; i++) {
        logger.logRequest(request("/" + i));
      }
      assertThat(limitReached.await(EVENT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
          .as("the concurrent request limit was reached")
          .isTrue();
      assertThat(inFlight.get()).as("requests held at the server").isEqualTo(limit);
      release.countDown();
      HttpBatchSenderTest.close(fixture.destination);
      assertThat(maxInFlight.get()).as("peak concurrent requests").isEqualTo(limit);
      assertThat(records.get()).isEqualTo(total);
      assertThat(fixture.destination.droppedCount()).isZero();
    } finally {
      release.countDown();
      // Without grace: once the sender is closed, every post has its answer and nothing logs late.
      // A grace stop took the whole 10 s in 5 of 50 runs: the closing sender drops its connections
      // while the JDK server stops, which then misses that the last one ended (2026-11-27).
      slow.stop(0);
    }
  }

  /// During an outage the breaker holds the batches and sends all of them once the server is
  /// back, with real timers. [OtlpOutageBDDTest] checks the probe deadlines on the simulated
  /// clock.
  @Test
  void breakerSendsWaitingBatchesAfterTheOutage() throws Exception {
    fixture.status = 503;
    int batchBytes = OtlpConfig.MIN_BATCH_BYTES;
    // a backoff of 100 to 200 ms with jitter
    fixture.destination =
        new OtlpLogDestination(
            "otlp",
            fixture
                .config()
                .batchBytes(batchBytes)
                .maxBufferedBytes(64 << 20)
                .flushIntervalMillis(OUT_OF_REACH_MILLIS)
                .initialRetryDelayMillis(200)
                .maxRetryDelayMillis(200)
                .build());
    OtlpRequestLogger logger = new OtlpRequestLogger(fixture.destination);
    int perBatch = batchBytes / framedSize(request("/0000")) + 1;
    int index = 0;
    for (; index < perBatch; index++) {
      logger.logRequest(request(String.format("/%04d", index)));
    }
    // the first batch failed, the breaker is open
    await("outage retried", () -> fixture.counter("sensepitch_request_log_export_retries") >= 1);
    int total = 11 * perBatch;
    for (; index < total; index++) {
      logger.logRequest(request(String.format("/%04d", index)));
    }
    fixture.status = 200;
    await("all records delivered", () -> fixture.deliveredPaths().size() >= total);
    assertThat(new HashSet<>(fixture.deliveredPaths())).hasSize(total);
    assertThat(fixture.destination.droppedCount()).isZero();
  }

  /// The log server is unavailable for a while: first it answers 503, then it is down, then it is
  /// back. Every record arrives exactly once and in order, nothing is dropped.
  @Test
  void outageLosesNothing() throws Exception {
    fixture.status = 503;
    // one request at a time, so the batches arrive in order. The whole backlog must fit: the
    // default limit depends on the number of processors, with two of them records were dropped
    fixture.destination =
        fixture.fastDestination(
            fixture
                .config()
                .batchBytes(OtlpConfig.MIN_BATCH_BYTES)
                .maxBufferedBytes(64 * 1024 * 1024)
                .maxConcurrentRequests(1)
                .build());
    OtlpRequestLogger logger = new OtlpRequestLogger(fixture.destination);
    int total = 1_000;
    for (int i = 0; i < total / 2; i++) {
      logger.logRequest(request("/" + i));
    }
    // a few retries answered with 503
    await("three retry attempts received", () -> fixture.received.size() >= 3);
    fixture.server.stop(0);
    for (int i = total / 2; i < total; i++) {
      logger.logRequest(request("/" + i));
    }
    // retries against the stopped server
    long retries = fixture.counter("sensepitch_request_log_export_retries");
    await(
        "three retries during outage",
        () -> fixture.counter("sensepitch_request_log_export_retries") >= retries + 3);
    assertThat(fixture.bufferedBytesGauge()).as("backlog waits").isPositive();
    fixture.status = 200;
    fixture.restartServer();
    await("all records delivered", () -> fixture.deliveredPaths().size() >= total);
    List<String> expected = new ArrayList<>();
    for (int i = 0; i < total; i++) {
      expected.add("/" + i);
    }
    assertThat(fixture.deliveredPaths()).as("all, once, in order").isEqualTo(expected);
    assertThat(fixture.destination.droppedCount()).isZero();
    assertThat(fixture.counter("sensepitch_request_log_export_failed")).isZero();
    assertThat(logs.messages(OtlpLogDestination.class))
        .anySatisfy(m -> assertThat(m).contains("Buffering records and retrying"))
        .anySatisfy(m -> assertThat(m).contains("recovered after"));
    await("buffered batches released", () -> fixture.bufferedBytesGauge() == 0);
  }

  /// Several threads log at the same time, every record arrives exactly once and intact. While
  /// the sender is busy, the pool provides more batches, so a burst is not dropped.
  @Test
  void concurrentThreads() throws Exception {
    fixture.destination =
        new OtlpLogDestination("otlp", fixture.config().flushIntervalMillis(5).build());
    OtlpRequestLogger logger = new OtlpRequestLogger(fixture.destination);
    int threads = 4;
    int perThread = 500;
    List<Thread> started = new ArrayList<>();
    for (int t = 0; t < threads; t++) {
      int thread = t;
      Thread worker =
          new Thread(
              () -> {
                for (int i = 0; i < perThread; i++) {
                  logger.logRequest(request("/" + thread + "/" + i));
                }
              });
      worker.start();
      started.add(worker);
    }
    for (Thread worker : started) {
      assertThat(worker.join(EVENT_TIMEOUT)).as("logging worker finished").isTrue();
    }
    HttpBatchSenderTest.close(fixture.destination);
    List<String> paths = fixture.deliveredPaths();
    Set<String> unique = new HashSet<>(paths);
    assertThat(fixture.destination.droppedCount()).isZero();
    assertThat(paths).hasSize(threads * perThread);
    assertThat(unique).as("no record twice").hasSize(threads * perThread);
  }

  /// A server asking for a delay via `Retry-After` is not asked again meanwhile, although the
  /// backoff of this destination is only milliseconds. The exact delay on the simulated clock is
  /// checked by [OtlpLogDestinationTest#retryAfterWinsOverTheBackoff]. At shutdown the batch gets
  /// a last attempt, regardless of the requested delay, before its records would be dropped.
  @Test
  void retryAfterIsHonoured() throws Exception {
    fixture.answers.add(new OtlpLogDestinationTest.Answer(429, "30"));
    fixture.destination = fixture.fastDestination(fixture.config().build());
    new OtlpRequestLogger(fixture.destination)
        .logRequest(TestRequestLogInfo.of(HttpMethod.GET, 200));
    assertThat(fixture.receive().status()).isEqualTo(429);
    await("the retry counted", () -> fixture.counter("sensepitch_request_log_export_retries") >= 1);
    assertThat(fixture.counter("sensepitch_request_log_export_retries")).isOne();
    assertThat(fixture.bufferedBytesGauge()).as("the batch waits for the delay").isPositive();
    HttpBatchSenderTest.close(fixture.destination);
    assertThat(fixture.receive().status()).as("last attempt at shutdown").isEqualTo(200);
    assertThat(fixture.counter("sensepitch_request_log_export_retries"))
        .as("no retry in between")
        .isOne();
    assertThat(fixture.destination.droppedCount()).isZero();
  }
}
