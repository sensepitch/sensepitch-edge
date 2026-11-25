package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.sensepitch.edge.TestingParameters.EVENT_TIMEOUT_SECONDS;
import static org.sensepitch.edge.TestingParameters.OUT_OF_REACH_MILLIS;
import static org.sensepitch.edge.TestingParameters.TEST_TIMEOUT_SECONDS;

import com.sun.management.ThreadMXBean;
import com.sun.net.httpserver.HttpServer;
import io.netty.handler.codec.http.HttpMethod;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/// Replacing the request log destinations on a configuration reload. Reloads under concurrent
/// logging and redundant server checks are in [ReloadableRequestLoggerIT], which uses this class
/// as its fixture. New scenarios belong here.
///
/// @author Jens Wilke
@Timeout(TEST_TIMEOUT_SECONDS)
class ReloadableRequestLoggerTest {

  final RequestLogCapture stdout = new RequestLogCapture();
  final RequestLogMetrics metrics = new RequestLogMetrics();

  /// Records accepted by the OTLP server, per request
  final BlockingQueue<Integer> received = new LinkedBlockingQueue<>();

  /// When set, the log server holds each export on this latch before answering it, so a
  /// test can prove a reload does not wait for the server.
  volatile CountDownLatch answerGate;

  /// Counted down when an export is held on `answerGate`, so a test can wait until the
  /// server is actually blocked.
  final CountDownLatch exportHeld = new CountDownLatch(1);

  HttpServer server;
  ReloadableRequestLogger logger;

  @BeforeEach
  void startServer() throws IOException {
    server = Loopback.httpServer();
    server.createContext(
        "/v1/logs",
        exchange -> {
          ExportLogsServiceRequest request = OtlpTestSupport.decode(exchange);
          CountDownLatch gate = answerGate;
          if (gate != null) {
            exportHeld.countDown();
            try {
              gate.await();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              exchange.close();
              throw new IOException("Interrupted while holding an OTLP export", e);
            }
          }
          received.add(request.getResourceLogs(0).getScopeLogs(0).getLogRecordsCount());
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    server.start();
  }

  @AfterEach
  void stop() {
    if (logger != null) {
      logger.close();
    }
    TestingParameters.stop(server);
  }

  RequestLogDestinationConfig otlp(String name, int flushIntervalMillis) {
    return RequestLogDestinationConfig.builder()
        .type("otlp")
        .name(name)
        .otlp(
            OtlpConfig.builder()
                .endpoint("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/logs")
                .flushIntervalMillis(flushIntervalMillis)
                // short retries, the outage tests need not wait the production backoff of 1 s
                .initialRetryDelayMillis(10)
                .maxRetryDelayMillis(50)
                .build())
        .build();
  }

  /// OTLP destination for simulated time: uncompressed so every post runs in the test thread, with
  /// an unreachable endpoint because [FakeLogServer] takes the posts
  static RequestLogDestinationConfig simulatedOtlp(String name, OtlpConfig.Builder otlpConfig) {
    return RequestLogDestinationConfig.builder()
        .type("otlp")
        .name(name)
        .otlp(
            otlpConfig
                .endpoint(HttpBatchSenderTest.UNREACHABLE)
                .compression(OtlpConfig.NONE)
                .build())
        .build();
  }

  static RequestLoggerFactory.SenderSeams simulatedSeams(FakeTime clock, FakeLogServer logServer) {
    return new RequestLoggerFactory.SenderSeams(clock, clock.scheduler()::schedule, logServer);
  }

  static RequestLogDestinationConfig stdout(boolean plainText) {
    return RequestLogDestinationConfig.builder().type("stdout").plainText(plainText).build();
  }

  static RequestLogConfig config(RequestLogDestinationConfig... destinations) {
    return RequestLogConfig.builder().destinations(List.of(destinations)).build();
  }

  void reload(RequestLogConfig config) {
    logger.prepare(config, new ConfigFiles()).apply();
  }

  void logOneRequest() {
    logger.logRequest(TestRequestLogInfo.of(HttpMethod.GET, 200));
  }

  /// Destinations with a queue gauge, from the metrics
  List<String> queuedDestinations() {
    PrometheusRegistry registry = new PrometheusRegistry();
    metrics.registerCollectors(registry::register);
    return new ScrapedMetrics(registry.scrape())
        .containsSnapshot("sensepitch_request_log_queued").getDataPoints().stream()
            .map(point -> point.getLabels().get(RequestLogMetrics.DESTINATION_LABEL))
            .toList();
  }

  /// An unchanged destination is kept, an OTLP destination with its buffered records
  @Test
  void unchangedDestinationsAreKept() {
    logger =
        RequestLoggerFactory.create(
            config(stdout(false), otlp("vl", OUT_OF_REACH_MILLIS)), stdout, metrics);
    List<ReloadableRequestLogger.Destination> before = logger.destinations();
    logOneRequest();
    reload(config(stdout(false), otlp("vl", OUT_OF_REACH_MILLIS)));
    assertThat(logger.destinations()).containsExactlyElementsOf(before);
    assertThat(received).as("nothing sent, the buffer is kept").isEmpty();
  }

  /// A destination is kept while its secret file is unchanged, and replaced when the secret
  /// changed, e.g. a rotated token. The files of a kept destination are recorded as well, so the
  /// watcher keeps watching them.
  @Test
  void changedSecretFileReplacesTheDestination(@TempDir Path dir) throws IOException {
    Path token = Files.writeString(dir.resolve("token"), "Bearer one");
    RequestLogDestinationConfig plain = otlp("vl", OUT_OF_REACH_MILLIS);
    RequestLogConfig config =
        config(
            plain.toBuilder()
                .otlp(
                    plain.otlp().toBuilder()
                        .secretHeaders(
                            List.of(
                                SecretHeaderConfig.builder()
                                    .name("Authorization")
                                    .secret(SecretConfig.builder().file(token.toString()).build())
                                    .build()))
                        .build())
                .build());
    ConfigFiles files = new ConfigFiles();
    logger = RequestLoggerFactory.create(config, stdout, metrics, List.of(Runnable::run), files);
    assertThat(files.snapshot().files()).containsOnlyKeys(token.toAbsolutePath());
    var before = logger.destinations().getFirst();
    ConfigFiles unchanged = new ConfigFiles();
    logger.prepare(config, unchanged).apply();
    assertThat(logger.destinations().getFirst()).isSameAs(before);
    assertThat(unchanged.snapshot()).isEqualTo(files.snapshot());
    Path tmp = Files.writeString(dir.resolve("tmp"), "Bearer two");
    Files.move(tmp, token, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    ConfigFiles rotated = new ConfigFiles();
    logger.prepare(config, rotated).apply();
    assertThat(logger.destinations().getFirst()).isNotSameAs(before);
    assertThat(rotated.snapshot()).isNotEqualTo(files.snapshot());
  }

  /// A changed destination is replaced, the old one sends what it buffered when closed
  @Test
  void changedDestinationIsReplaced() throws Exception {
    logger = RequestLoggerFactory.create(config(otlp("vl", OUT_OF_REACH_MILLIS)), stdout, metrics);
    var before = logger.destinations().getFirst();
    logOneRequest();
    // a changed flush interval, still out of reach
    reload(config(otlp("vl", 2 * OUT_OF_REACH_MILLIS)));
    assertThat(logger.destinations().getFirst()).isNotSameAs(before);
    assertThat(received.poll(EVENT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        .as("buffered record sent by the old destination after the end marker")
        .isOne();
    logOneRequest();
    logger.close();
    assertThat(received.poll(EVENT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        .as("the new destination logs")
        .isOne();
  }

  /// A removed destination finishes once its threads wrote their end markers; nothing is buffered,
  /// so its gauge disappears right away
  @Test
  void removedDestinationIsClosed() {
    FakeTime clock = new FakeTime();
    FakeLogServer logServer = new FakeLogServer();
    logger =
        RequestLoggerFactory.create(
            config(
                stdout(false),
                simulatedOtlp("vl", OtlpConfig.builder().flushIntervalMillis(OUT_OF_REACH_MILLIS))),
            stdout,
            metrics,
            simulatedSeams(clock, logServer));
    assertThat(queuedDestinations()).contains("vl");
    reload(config(stdout(true)));
    assertThat(queuedDestinations()).doesNotContain("vl");
    assertThat(logServer.requests()).isEmpty();
    logOneRequest();
    assertThat(stdout.lines()).singleElement().asString().startsWith("RQ0 ");
  }

  /// At shutdown, finish also covers a destination still draining from a reload. The unsent count
  /// includes the records of both the active and the draining destination.
  @Test
  void finishCoversDrainingDestinations() {
    int retryMillis = 1_000;
    FakeTime clock = new FakeTime();
    FakeLogServer logServer = new FakeLogServer();
    RequestLogDestinationConfig oldDestination =
        simulatedOtlp(
            "old",
            OtlpConfig.builder()
                .flushIntervalMillis(OUT_OF_REACH_MILLIS)
                .initialRetryDelayMillis(retryMillis)
                .maxRetryDelayMillis(retryMillis));
    RequestLogDestinationConfig newDestination =
        simulatedOtlp(
            "new",
            OtlpConfig.builder()
                .flushIntervalMillis(OUT_OF_REACH_MILLIS)
                .initialRetryDelayMillis(retryMillis)
                .maxRetryDelayMillis(retryMillis));
    logger =
        RequestLoggerFactory.create(
            config(oldDestination), stdout, metrics, simulatedSeams(clock, logServer));
    logger.logRequest(TestRequestLogInfo.request("/old"));
    reload(config(newDestination));
    logger.logRequest(TestRequestLogInfo.request("/new"));
    logger.endThread();
    CompletableFuture<Void> finished = logger.finish();
    assertThat(logServer.requests()).as("old and new posted their records").hasSize(2);
    logServer.succeed(503);
    logServer.succeed(503);
    assertThat(finished).isNotDone();
    assertThat(logger.unsent()).isEqualTo(2);
    clock.advanceMillis(retryMillis);
    assertThat(logServer.requests()).as("both retried").hasSize(4);
    logServer.succeed(200);
    assertThat(finished).as("waits until both draining and active finished").isNotDone();
    logServer.succeed(200);
    assertThat(finished).isDone();
    assertThat(logger.unsent()).isZero();
    assertThat(
            List.of(
                OtlpTestSupport.paths(logServer.requests().get(2)),
                OtlpTestSupport.paths(logServer.requests().get(3))))
        .containsExactlyInAnyOrder(List.of("/old"), List.of("/new"));
    assertThat(dropped("old")).isZero();
    assertThat(dropped("new")).isZero();
  }

  /// A removed destination whose log server does not come back ends once its records are older
  /// than the maximum age, they are counted as dropped. On the simulated clock: the test posts the
  /// first export and answers it, so the records cannot age out before an attempt.
  @Test
  void finishedDestinationEndsWhenItsRecordsAgedOut() {
    int ageMillis = 300;
    int records = 10;
    FakeTime clock = new FakeTime();
    FakeLogServer logServer = new FakeLogServer();
    RequestLogDestinationConfig shortAge =
        simulatedOtlp(
            "vl",
            OtlpConfig.builder()
                .flushIntervalMillis(1)
                .maximumUnsentLogAgeMillis(ageMillis)
                // the breaker lets no retry through, only the last attempt of finishing
                .initialRetryDelayMillis(OUT_OF_REACH_MILLIS)
                .maxRetryDelayMillis(OUT_OF_REACH_MILLIS));
    logger =
        RequestLoggerFactory.create(
            config(shortAge), stdout, metrics, simulatedSeams(clock, logServer));
    CompletableFuture<Void> removedStopped = stoppedOfActiveDestination();
    for (int i = 0; i < records; i++) {
      logOneRequest();
    }
    clock.advanceMillis(1);
    assertThat(logServer.requests()).as("the first flush posts the export").hasSize(1);
    logServer.succeed(503);
    reload(config(stdout(false)));
    assertThat(logServer.requests()).as("finishing makes one last attempt").hasSize(2);
    logServer.succeed(503);
    clock.advanceMillis(ageMillis - 1);
    assertThat(removedStopped).as("the records are not too old yet").isNotDone();
    assertThat(queuedDestinations()).contains("vl");
    assertThat(dropped("vl")).isZero();
    clock.advanceMillis(1);
    assertThat(removedStopped).as("the destination ended").isDone();
    assertThat(queuedDestinations()).doesNotContain("vl");
    assertThat(dropped("vl")).isEqualTo(records);
    assertThat(logServer.requests()).as("no further attempt").hasSize(2);
  }

  /// A configuration that fails closes only the destinations created for it, the active ones stay
  @Test
  void failingPrepareKeepsTheActiveDestinations() {
    logger = RequestLoggerFactory.create(config(otlp("vl", OUT_OF_REACH_MILLIS)), stdout, metrics);
    List<ReloadableRequestLogger.Destination> before = logger.destinations();
    RequestLogDestinationConfig noEndpoint =
        RequestLogDestinationConfig.builder()
            .type("otlp")
            .name("broken")
            .otlp(OtlpConfig.builder().build())
            .build();
    assertThatThrownBy(
            () ->
                logger.prepare(
                    config(otlp("vl", OUT_OF_REACH_MILLIS), otlp("new", 1), noEndpoint),
                    new ConfigFiles()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("endpoint missing");
    assertThat(queuedDestinations())
        .as("the created one is closed, the active one is not")
        .containsExactly("vl");
    assertThat(logger.destinations()).containsExactlyElementsOf(before);
  }

  long dropped(String destination) {
    return (long) metrics.dropped.labelValues(destination).get();
  }

  /// Sum of the records received, after `stopped` completed. The server adds a request to
  /// `received` before it answers, and a destination stops only when all its requests were
  /// answered, so everything its senders posted, a duplicate as well, is counted.
  ///
  /// @param stopped completed when the senders stopped, e.g. [ReloadableRequestLogger#finish()]
  int receivedAfter(CompletableFuture<?> stopped) throws Exception {
    stopped.get(EVENT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    int sum = 0;
    Integer records;
    while ((records = received.poll()) != null) {
      sum += records;
    }
    return sum;
  }

  /// Ends the batchers of the calling thread, finishes all destinations, and returns the sum of
  /// the records received once they stopped, see [#receivedAfter(CompletableFuture)]
  int receivedAfterFinish() throws Exception {
    logger.endThread();
    return receivedAfter(logger.finish());
  }

  /// Completed when the only active destination stopped, e.g. after a reload replaced it.
  /// Waiting for it does not finish the destination, unlike [ReloadableRequestLogger#finish()].
  CompletableFuture<Void> stoppedOfActiveDestination() {
    return logger.destinations().getFirst().batching().sender().stopped();
  }

  String endpoint() {
    return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/logs";
  }

  /// A record an event loop logs into a replaced destination after the switch, before it ran its
  /// end marker, is sent by it like its buffered records
  @Test
  void lateRecordGoesToTheOldDestination() throws Exception {
    OtlpConfig otlpConfig =
        OtlpConfig.builder().endpoint(endpoint()).flushIntervalMillis(OUT_OF_REACH_MILLIS).build();
    OtlpRequestLogger old =
        new OtlpRequestLogger(new OtlpLogDestination("old", otlpConfig, metrics));
    old.logRequest(TestRequestLogInfo.of(HttpMethod.GET, 200));
    CompletableFuture<Void> stopped = old.finish();
    old.logRequest(TestRequestLogInfo.of(HttpMethod.GET, 200));
    assertThat(stopped).as("waits for the end marker").isNotDone();
    old.endThread();
    assertThat(receivedAfter(stopped)).isEqualTo(2);
    assertThat(dropped("old")).isZero();
  }

  /// A record logged into a finished destination is dropped without taking a batch. The thread
  /// would keep that batch as long as it lives, a batch per reload.
  @Test
  void finishedDestinationAllocatesNoBatch() throws Exception {
    OtlpConfig otlpConfig =
        OtlpConfig.builder().endpoint(endpoint()).flushIntervalMillis(OUT_OF_REACH_MILLIS).build();
    OtlpLogDestination destination = new OtlpLogDestination("old", otlpConfig, metrics);
    OtlpRequestLogger old = new OtlpRequestLogger(destination);
    destination.finish();
    RequestLogInfo info = TestRequestLogInfo.of(HttpMethod.GET, 200);
    ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    long before = threads.getCurrentThreadAllocatedBytes();
    old.logRequest(info);
    long allocated = threads.getCurrentThreadAllocatedBytes() - before;
    assertThat(allocated).as("bytes allocated").isLessThan(otlpConfig.batchBytes());
    assertThat(dropped("old")).isOne();
  }

  @Test
  void closeClosesAll() {
    logger =
        RequestLoggerFactory.create(
            config(otlp("a", OUT_OF_REACH_MILLIS), otlp("b", OUT_OF_REACH_MILLIS)),
            stdout,
            metrics);
    logger.close();
    assertThat(queuedDestinations()).isEmpty();
    logger = null;
  }
}
