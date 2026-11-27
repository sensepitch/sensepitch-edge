package org.sensepitch.edge;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.sensepitch.edge.TestingParameters.EVENT_TIMEOUT_SECONDS;
import static org.sensepitch.edge.TestingParameters.OUT_OF_REACH;
import static org.sensepitch.edge.TestingParameters.TEST_TIMEOUT_SECONDS;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;

/// Waiting for a log record, see [LogCapture#await] and [TestingLogSink#awaitAdded]
@Timeout(TEST_TIMEOUT_SECONDS)
class LogCaptureTest {

  private static final ProxyLogger LOG = ProxyLogger.get(LogCaptureTest.class);

  @RegisterExtension LogCapture logs = new LogCapture();

  @Test
  void recordLoggedBeforeIsFoundAtOnce() throws Exception {
    LOG.info("ready on port 1");
    assertThat(logs.awaitMessage(LogCaptureTest.class, "ready")).isEqualTo("ready on port 1");
  }

  @Test
  void missingRecordFailsWithTheDescription() {
    LOG.info("something else");
    assertThatThrownBy(() -> logs.await("listener started", e -> false, Duration.ZERO))
        .isInstanceOf(AssertionError.class)
        .hasMessage("listener started not reached within 0 seconds");
  }

  /// The waiter would wait until [TestingParameters#OUT_OF_REACH], so it can only return within
  /// [TestingParameters#EVENT_TIMEOUT] if the record woke it. A record of another test running
  /// meanwhile only wakes it early to check again.
  @Test
  void recordOfAnotherThreadWakesTheWaiter() throws Exception {
    AtomicBoolean logged = new AtomicBoolean();
    CompletableFuture<Boolean> woken = new CompletableFuture<>();
    Thread waiter =
        Thread.ofPlatform()
            .name("log-waiter")
            .start(
                () -> {
                  try {
                    woken.complete(
                        TestingLogSink.awaitAdded(
                            logged::get, System.nanoTime() + OUT_OF_REACH.toNanos()));
                  } catch (InterruptedException e) {
                    woken.completeExceptionally(e);
                  }
                });
    TestingParameters.await(
        "waiter waits for a record", () -> waiter.getState() == Thread.State.TIMED_WAITING);
    logged.set(true);
    LOG.info("the awaited record");
    assertThat(woken.get(EVENT_TIMEOUT_SECONDS, SECONDS)).isTrue();
  }
}
