package org.sensepitch.edge;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.sensepitch.edge.TestingParameters.EVENT_TIMEOUT_SECONDS;
import static org.sensepitch.edge.TestingParameters.OUT_OF_REACH;
import static org.sensepitch.edge.TestingParameters.TEST_TIMEOUT_SECONDS;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/// Waiting for a request log line, see [RequestLogCapture#awaitLine]
@Timeout(TEST_TIMEOUT_SECONDS)
class RequestLogCaptureTest {

  private final RequestLogCapture requestLog = new RequestLogCapture();

  @Test
  void lineWrittenBeforeIsFoundAtOnce() throws Exception {
    requestLog.writeLine("GET /a 200");
    requestLog.writeLine("GET /b 404");
    assertThat(requestLog.awaitLine("404 is logged", line -> line.endsWith("404")))
        .isEqualTo("GET /b 404");
  }

  @Test
  void missingLineFailsWithTheDescription() {
    requestLog.writeLine("GET /a 200");
    assertThatThrownBy(() -> requestLog.awaitLine("404 is logged", line -> false, Duration.ZERO))
        .isInstanceOf(AssertionError.class)
        .hasMessage("404 is logged not reached within 0 seconds");
  }

  /// The waiter would wait until [TestingParameters#OUT_OF_REACH], so it can only return within
  /// [TestingParameters#EVENT_TIMEOUT] if the written line woke it
  @Test
  void lineOfAnotherThreadWakesTheWaiter() throws Exception {
    CompletableFuture<String> found = new CompletableFuture<>();
    Thread waiter =
        Thread.ofPlatform()
            .name("request-log-waiter")
            .start(
                () -> {
                  try {
                    found.complete(requestLog.awaitLine("any line", line -> true, OUT_OF_REACH));
                  } catch (InterruptedException e) {
                    found.completeExceptionally(e);
                  }
                });
    TestingParameters.await(
        "waiter waits for a line", () -> waiter.getState() == Thread.State.TIMED_WAITING);
    requestLog.writeLine("GET / 200");
    assertThat(found.get(EVENT_TIMEOUT_SECONDS, SECONDS)).isEqualTo("GET / 200");
  }
}
