package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.sensepitch.edge.OtlpLogDestinationTest.framedSize;
import static org.sensepitch.edge.OtlpTestSupport.decode;
import static org.sensepitch.edge.TestRequestLogInfo.request;
import static org.sensepitch.edge.TestingParameters.EVENT_TIMEOUT_SECONDS;
import static org.sensepitch.edge.TestingParameters.OUT_OF_REACH_MILLIS;

import com.sun.net.httpserver.HttpServer;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
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
    int maxConcurrentRequests = 8;
    AtomicInteger arrivals = new AtomicInteger();
    CountDownLatch initialWindow = new CountDownLatch(maxConcurrentRequests);
    Semaphore failures = new Semaphore(0);
    AtomicInteger inFlight = new AtomicInteger();
    var delivered = new ConcurrentLinkedQueue<String>();
    CountDownLatch probeArrived = new CountDownLatch(1);
    CountDownLatch twoRequestsInFlight = new CountDownLatch(1);
    Semaphore answers = new Semaphore(0);
    HttpServer held = Loopback.httpServer();
    var workers = Executors.newVirtualThreadPerTaskExecutor();
    held.setExecutor(workers);
    held.createContext(
        "/v1/logs",
        exchange -> {
          byte[] body = exchange.getRequestBody().readAllBytes();
          if (arrivals.incrementAndGet() <= maxConcurrentRequests) {
            initialWindow.countDown();
            failures.acquireUninterruptibly();
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
          delivered.addAll(OtlpTestSupport.paths(decode(exchange, body)));
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
                  .maxConcurrentRequests(maxConcurrentRequests)
                  .timeoutMillis(OUT_OF_REACH_MILLIS)
                  .flushIntervalMillis(OUT_OF_REACH_MILLIS)
                  .initialRetryDelayMillis(10)
                  .maxRetryDelayMillis(50)
                  .build());
      OtlpRequestLogger logger = new OtlpRequestLogger(destination);
      int total = 10 * (batchBytes / framedSize(request("/0")) + 1);
      var expected = new ArrayList<String>(total);
      for (int i = 0; i < total; i++) {
        String path = "/" + i;
        expected.add(path);
        logger.logRequest(request(path));
      }
      // Hold the entire initial window before returning any 503. Every later arrival is a retry
      // or a queued post, even if the HTTP client delivers its initial requests out of order.
      assertThat(initialWindow.await(EVENT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
          .as("the full initial window arrived")
          .isTrue();
      failures.release(maxConcurrentRequests);
      // the probe alone
      assertThat(probeArrived.await(EVENT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
          .as("the recovery probe arrived")
          .isTrue();
      assertThat(destination.window()).as("while the probe is held").isOne();
      assertThat(inFlight.get()).as("only the probe is held").isOne();
      // its success allows two
      answers.release();
      assertThat(twoRequestsInFlight.await(EVENT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
          .as("two requests followed the successful probe")
          .isTrue();
      assertThat(destination.window()).as("after the probe succeeds").isEqualTo(2);
      assertThat(inFlight.get()).as("two requests are held after the probe").isEqualTo(2);
      answers.release(1_000);
      HttpBatchSenderTest.close(destination);
      assertThat(delivered).containsExactlyInAnyOrderElementsOf(expected);
      assertThat(destination.droppedCount()).isZero();
      assertThat(destination.window()).as("back at the maximum").isEqualTo(maxConcurrentRequests);
    } finally {
      failures.release(maxConcurrentRequests);
      answers.release(1_000);
      if (destination != null) {
        destination.endThread();
        destination.finish();
      }
      TestingParameters.stop(held);
      workers.close();
    }
  }
}
