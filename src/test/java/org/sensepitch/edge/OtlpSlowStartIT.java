package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.sensepitch.edge.OtlpLogDestinationTest.framedSize;
import static org.sensepitch.edge.OtlpTestSupport.decode;
import static org.sensepitch.edge.TestRequestLogInfo.request;
import static org.sensepitch.edge.TestingParameters.OUT_OF_REACH_MILLIS;
import static org.sensepitch.edge.TestingParameters.TIMEOUT_SECONDS;
import static org.sensepitch.edge.TestingParameters.await;

import com.sun.net.httpserver.HttpServer;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/// The slow start of [HttpBatchSender] after an outage, against a real HTTP server.
///
/// Tagged `excessive`, so it runs only nightly and with `-Dexcessive`: it depends on the timing of
/// real threads and sockets and failed now and then among the unit tests (`condition not reached
/// within 10 seconds`, 2 of 9 runs on 2026-11-16). It adds no coverage, the window is checked
/// deterministically by `OtlpLogDestinationTest.windowIsOneAfterAFailureAndGrowsBack`.
///
/// @author Jens Wilke
@Tag("excessive")
class OtlpSlowStartIT {

  /// After an outage the backlog is sent with a slow start: the probe alone, then two requests in
  /// flight, one more with each success, up to `maxConcurrentRequests`
  @Test
  void slowStartAfterAnOutage() throws Exception {
    AtomicBoolean available = new AtomicBoolean();
    AtomicInteger inFlight = new AtomicInteger();
    AtomicInteger records = new AtomicInteger();
    CountDownLatch probeArrived = new CountDownLatch(1);
    CountDownLatch twoRequestsInFlight = new CountDownLatch(1);
    Semaphore answers = new Semaphore(0);
    HttpServer held = Loopback.httpServer();
    held.setExecutor(Executors.newCachedThreadPool());
    held.createContext(
        "/v1/logs",
        exchange -> {
          byte[] body = exchange.getRequestBody().readAllBytes();
          if (!available.get()) {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
            return;
          }
          if (inFlight.incrementAndGet() == 2) {
            twoRequestsInFlight.countDown();
          }
          probeArrived.countDown();
          answers.acquireUninterruptibly();
          inFlight.decrementAndGet();
          records.addAndGet(
              decode(exchange, body).getResourceLogs(0).getScopeLogs(0).getLogRecordsCount());
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    held.start();
    OtlpLogDestination destination = null;
    try {
      int batchBytes = OtlpConfig.MIN_BATCH_BYTES;
      // short retry delays, as in OtlpLogDestinationTest.fastDestination
      destination =
          new OtlpLogDestination(
              "otlp",
              OtlpConfig.builder()
                  .endpoint("http://127.0.0.1:" + held.getAddress().getPort() + "/v1/logs")
                  .batchBytes(batchBytes)
                  .maxBufferedBytes(64 << 20)
                  .maxConcurrentRequests(8)
                  .flushIntervalMillis(OUT_OF_REACH_MILLIS)
                  .initialRetryDelayMillis(10)
                  .maxRetryDelayMillis(50)
                  .build());
      OtlpLogDestination sender = destination;
      OtlpRequestLogger logger = new OtlpRequestLogger(destination);
      int total = 10 * (batchBytes / framedSize(request("/0")) + 1);
      for (int i = 0; i < total; i++) {
        logger.logRequest(request("/" + i));
      }
      await("sender retries after the outage", () -> retries(sender) >= 1);
      assertThat(destination.window()).as("after a failure").isOne();
      available.set(true);
      // the probe alone
      assertThat(probeArrived.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
          .as("the recovery probe arrived")
          .isTrue();
      assertThat(inFlight.get()).as("only the probe is held").isOne();
      // its success allows two
      answers.release();
      assertThat(twoRequestsInFlight.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
          .as("two requests followed the successful probe")
          .isTrue();
      assertThat(inFlight.get()).as("two requests are held after the probe").isEqualTo(2);
      answers.release(1_000);
      HttpBatchSenderTest.close(destination);
      assertThat(records.get()).isEqualTo(total);
      assertThat(destination.droppedCount()).isZero();
      assertThat(destination.window()).as("back at the maximum").isEqualTo(8);
    } finally {
      answers.release(1_000);
      if (destination != null) {
        destination.endThread();
        destination.finish();
      }
      TestingParameters.stop(held);
    }
  }

  /// Export retries of the destination, to wait for the first failed request
  static long retries(OtlpLogDestination destination) {
    PrometheusRegistry registry = new PrometheusRegistry();
    destination.metrics().registerCollectors(registry::register);
    return (long)
        new ScrapedMetrics(registry.scrape())
            .counter(
                "sensepitch_request_log_export_retries", "destination", destination.destination())
            .getValue();
  }
}
