package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.sensepitch.edge.OtlpLogDestinationTest.framedSize;
import static org.sensepitch.edge.OtlpTestSupport.paths;
import static org.sensepitch.edge.TestRequestLogInfo.request;
import static org.sensepitch.edge.TestingParameters.OUT_OF_REACH_MILLIS;

import java.net.http.HttpRequest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import net.serenitybdd.annotations.Step;
import net.serenitybdd.junit5.SerenityJUnit5Extension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

/// An outage of the OTLP log server on the simulated clock: waiting batches age out, a batch that
/// meets an open breaker waits, and the breaker lets one probe through per backoff. The sender
/// runs on [FakeTime] with a [FakeLogServer],
/// so the flush timer, the probes and the answers run in the test thread, without a log server,
/// real timers or waits. Each deadline is checked 1 ms before and at the moment it is due.
///
/// Uncompressed, so no compression thread is involved. The wire format and real timers are
/// covered by [OtlpLogDestinationTest] and [OtlpLogDestinationIT].
@ExtendWith(SerenityJUnit5Extension.class)
class OtlpOutageBDDTest {

  static final String ENDPOINT = "http://127.0.0.1:1/v1/logs";
  static final String NAME = "otlp";
  static final int AGE_MILLIS = 200;
  static final int UNAVAILABLE = 503;
  static final int OK = 200;
  static final String AGE_DROP_MESSAGE = "older than maximumUnsentLogAge";

  @RegisterExtension LogCapture logs = new LogCapture();

  /// A batch whose first record is older than the maximum age is dropped at the next flush, the
  /// retries end. Each outage logs that once, further drops are only counted.
  @Test
  void agedBatchesAreDroppedAndLoggedOncePerOutage() {
    int probeMillis = 1_000;
    new Steps(logs)
        .givenDestination(
            config()
                .flushIntervalMillis(1)
                .maximumUnsentLogAgeMillis(AGE_MILLIS)
                .initialRetryDelayMillis(probeMillis)
                .maxRetryDelayMillis(probeMillis)
                .build())
        .whenLogged("/old", 5)
        .whenClockAdvances(1)
        .thenRequestsSent(1)
        .whenAnswered(UNAVAILABLE)
        .whenClockAdvances(AGE_MILLIS - 1)
        .thenDropped(0)
        .whenClockAdvances(1)
        .thenDropped(5)
        .thenBufferedBytesGaugeIs(0)
        .thenAgeDropsLogged(1)
        // a second round in the same outage is dropped as well, but not logged again
        .whenLogged("/older", 3)
        .whenClockAdvances(AGE_MILLIS)
        .thenDropped(5)
        .whenClockAdvances(1)
        .thenDropped(8)
        .thenRequestsSent(1)
        .thenAgeDropsLogged(1)
        // the backoff is over, at most `probeMillis` after the failure
        .whenClockAdvances(probeMillis)
        .whenLogged("/new", 1)
        .whenClockAdvances(1)
        .thenRequestsSent(2)
        .whenAnswered(OK)
        .thenDeliveredPaths("/new0")
        .thenDropped(8)
        // the next outage logs its first aged batch again
        .whenLogged("/again", 1)
        .whenClockAdvances(1)
        .thenRequestsSent(3)
        .whenAnswered(UNAVAILABLE)
        .whenClockAdvances(AGE_MILLIS + 1)
        .thenDropped(9)
        .thenAgeDropsLogged(2);
  }

  /// After the last waiting batch aged out, the queue is empty and the breaker is still open. A
  /// new full batch then asks the breaker in `HttpBatchSender.submit` and waits, it is not sent.
  /// The aged batches are only found at a flush, which also ends a finishing sender after its last
  /// attempt failed.
  @Test
  void batchLoggedAfterAnAgedOneWaitsForTheBreaker() {
    int flushMillis = 500;
    int firstTotal = recordsOverOneBatch("/0000");
    int secondTotal = recordsOverOneBatch("/a0000");
    new Steps(logs)
        .givenDestination(
            config()
                .maxConcurrentRequests(1)
                // a full batch is handed over before the first flush, so submit asks the breaker
                .flushIntervalMillis(flushMillis)
                .maximumUnsentLogAgeMillis(AGE_MILLIS)
                // the breaker cannot let a probe through during the test
                .initialRetryDelayMillis(OUT_OF_REACH_MILLIS)
                .maxRetryDelayMillis(OUT_OF_REACH_MILLIS)
                .build())
        .whenLoggedNumbered("/", firstTotal)
        .thenRequestsSent(1)
        .whenAnswered(UNAVAILABLE)
        .whenClockAdvances(flushMillis - 1)
        .thenDropped(0)
        .whenClockAdvances(1)
        .thenDropped(firstTotal)
        .thenQueueSizeIs(0)
        .whenLoggedNumbered("/a", secondTotal)
        .thenRequestsSent(1)
        .thenQueueSizeIs(secondTotal)
        .thenBufferedBytesGaugeIsPositive()
        // finishing skips the backoff for one last attempt
        .whenFinished()
        .thenRequestsSent(2)
        .whenAnswered(UNAVAILABLE)
        .whenClockAdvances(flushMillis - 1)
        .thenStopped(false)
        .whenClockAdvances(1)
        .thenStopped(true)
        .thenDropped(firstTotal + secondTotal)
        .thenRequestsSent(2);
  }

  /// During an outage the breaker lets one probe through per backoff, not a retry per batch, and
  /// sends all waiting batches once the server is back
  @Test
  void breakerSendsOneProbePerBackoff() {
    int backoffMillis = 200;
    int perBatch = recordsOverOneBatch("/0000");
    int total = 11 * perBatch;
    new Steps(logs)
        .givenDestination(
            config()
                .maxConcurrentRequests(4)
                .flushIntervalMillis(OUT_OF_REACH_MILLIS)
                .initialRetryDelayMillis(backoffMillis)
                .maxRetryDelayMillis(backoffMillis)
                .build())
        .whenLoggedNumbered("/", perBatch)
        .thenRequestsSent(1)
        .whenAnswered(UNAVAILABLE)
        // 10 more batches wait behind the open breaker
        .whenLoggedNumbered("/", perBatch, total)
        .thenRequestsSent(1)
        .thenOneProbeAfterTheBackoff(backoffMillis)
        .thenRequestsSent(2)
        .whenAnswered(UNAVAILABLE)
        .thenOneProbeAfterTheBackoff(backoffMillis)
        .thenRequestsSent(3)
        .whenAnswered(UNAVAILABLE)
        .thenOneProbeAfterTheBackoff(backoffMillis)
        .thenRequestsSent(4)
        .whenAnswered(OK)
        .whenFinished()
        .whenAllAnswered(OK)
        .thenStopped(true)
        .thenDeliveredOnce(total)
        .thenDropped(0);
  }

  static OtlpConfig.Builder config() {
    return OtlpConfig.builder()
        .endpoint(ENDPOINT)
        .compression(OtlpConfig.NONE)
        .batchBytes(OtlpConfig.MIN_BATCH_BYTES)
        .maxBufferedBytes(64 << 20);
  }

  /// Records with paths as long as `sample` that fill one batch and start the next, so the full
  /// batch is handed over and one record stays with the thread
  static int recordsOverOneBatch(String sample) {
    int framed = framedSize(request(sample));
    return (OtlpConfig.MIN_BATCH_BYTES + framed - 1) / framed + 1;
  }

  static class Steps {
    final LogCapture logs;
    final FakeTime clock = new FakeTime();
    final FakeLogServer logServer = new FakeLogServer();
    final List<String> delivered = new ArrayList<>();
    int answered;
    OtlpLogDestination destination;
    OtlpRequestLogger logger;

    Steps(LogCapture logs) {
      this.logs = logs;
    }

    @Step("Destination on the simulated clock with the configuration {0}")
    Steps givenDestination(OtlpConfig config) {
      destination =
          new OtlpLogDestination(
              NAME, config, new RequestLogMetrics(), clock, clock.scheduler()::schedule, logServer);
      logger = new OtlpRequestLogger(destination);
      return this;
    }

    @Step("Log {1} records with the path prefix {0}")
    Steps whenLogged(String prefix, int count) {
      for (int i = 0; i < count; i++) {
        logger.logRequest(request(prefix + i));
      }
      return this;
    }

    @Step("Log {1} records with the paths {0}0000 and up")
    Steps whenLoggedNumbered(String prefix, int count) {
      return whenLoggedNumbered(prefix, 0, count);
    }

    @Step("Log the records {1} to {2}, exclusive, with the path prefix {0}")
    Steps whenLoggedNumbered(String prefix, int from, int to) {
      for (int i = from; i < to; i++) {
        logger.logRequest(request(String.format("%s%04d", prefix, i)));
      }
      return this;
    }

    @Step("The clock advances {0} ms")
    Steps whenClockAdvances(long millis) {
      clock.advanceMillis(millis);
      return this;
    }

    @Step("The log server answers the oldest open request with status {0}")
    Steps whenAnswered(int status) {
      HttpRequest request = logServer.requests().get(answered++);
      if (status / 100 == 2) {
        delivered.addAll(paths(request));
      }
      logServer.succeed(status);
      return this;
    }

    @Step("The log server answers all open requests, also those that follow, with status {0}")
    Steps whenAllAnswered(int status) {
      while (answered < logServer.requests().size()) {
        whenAnswered(status);
      }
      return this;
    }

    @Step("The thread ends and the destination finishes")
    Steps whenFinished() {
      destination.endThread();
      destination.finish();
      return this;
    }

    @Step("{0} requests were posted")
    Steps thenRequestsSent(int count) {
      assertThat(logServer.requests()).as("requests posted").hasSize(count);
      return this;
    }

    @Step("The probe goes out when the backoff is over, not 1 ms before, at most {0} ms")
    Steps thenOneProbeAfterTheBackoff(long backoffMillis) {
      long delayMillis =
          TimeUnit.NANOSECONDS.toMillis(
              destination
                  .metrics()
                  .circuitBreakers
                  .find(HttpBatchSender.BREAKER_COMPONENT, NAME)
                  .retryDelayNanos());
      assertThat(delayMillis).as("backoff with jitter").isBetween(backoffMillis / 2, backoffMillis);
      int before = logServer.requests().size();
      clock.advanceMillis(delayMillis - 1);
      assertThat(logServer.requests())
          .as("requests 1 ms before the backoff is over")
          .hasSize(before);
      clock.advanceMillis(1);
      assertThat(logServer.requests()).as("one probe when it is over").hasSize(before + 1);
      return this;
    }

    @Step("{0} records were dropped")
    Steps thenDropped(long count) {
      assertThat(destination.droppedCount()).as("dropped records").isEqualTo(count);
      return this;
    }

    @Step("The buffered bytes gauge is {0}")
    Steps thenBufferedBytesGaugeIs(long bytes) {
      assertThat(bufferedBytesGauge()).isEqualTo(bytes);
      return this;
    }

    @Step("The buffered bytes gauge shows waiting batches")
    Steps thenBufferedBytesGaugeIsPositive() {
      assertThat(bufferedBytesGauge()).isPositive();
      return this;
    }

    @Step("{0} records wait, in batches and with the thread")
    Steps thenQueueSizeIs(int records) {
      assertThat(destination.queueSize()).isEqualTo(records);
      return this;
    }

    @Step("Dropping aged batches was logged {0} times")
    Steps thenAgeDropsLogged(int times) {
      assertThat(logs.messages(OtlpLogDestination.class))
          .filteredOn(message -> message.contains(AGE_DROP_MESSAGE))
          .as("logged once per outage")
          .hasSize(times);
      return this;
    }

    @Step("The records delivered with success are {0}")
    Steps thenDeliveredPaths(String... paths) {
      assertThat(delivered).containsExactly(paths);
      return this;
    }

    @Step("{0} different records were delivered, each once")
    Steps thenDeliveredOnce(int total) {
      assertThat(delivered).hasSize(total);
      assertThat(new HashSet<>(delivered)).hasSize(total);
      return this;
    }

    @Step("The destination stopped: {0}")
    Steps thenStopped(boolean stopped) {
      assertThat(destination.stopped().isDone()).as("stopped").isEqualTo(stopped);
      return this;
    }

    long bufferedBytesGauge() {
      return OtlpTestSupport.bufferedBytesGauge(destination);
    }
  }
}
