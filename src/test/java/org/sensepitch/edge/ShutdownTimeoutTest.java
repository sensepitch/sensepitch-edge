package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.sensepitch.edge.CompleteTest.COMMON_CONFIG;
import static org.sensepitch.edge.TestingParameters.EVENT_TIMEOUT_SECONDS;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/// Shutdown timeout defaults and drain budgets. [ProxyRejectedConfigBDDTest] covers invalid values.
///
/// @author Jens Wilke
class ShutdownTimeoutTest {

  @RegisterExtension LogCapture logs = new LogCapture();

  private Proxy proxy;

  @ParameterizedTest
  @CsvSource({"40, 60", "125, 0"})
  void drainPreparationConsumesShutdownBudget(long preparationMillis, long remainingMillis) {
    ProxyConfig config =
        COMMON_CONFIG.toBuilder()
            .shutdownTimeoutMillis(100)
            .requestLog(RequestLogConfig.builder().drainTimeoutMillis(30L).build())
            .build();
    List<Long> waitBudgets = new ArrayList<>();
    proxy =
        new Proxy(
            config,
            new RequestLogCapture(),
            // Model elapsed time at a lifecycle boundary, independently of clock read counts.
            () ->
                proxy.eventLoopGroup().isShuttingDown()
                    ? TimeUnit.MILLISECONDS.toNanos(preparationMillis)
                    : 0,
            ignored -> {},
            (future, millis) -> {
              waitBudgets.add(millis);
              return completes(future);
            },
            ignored -> {});
    proxy.shutdown();
    assertThat(waitBudgets).containsExactly(70L, remainingMillis);
  }

  /// A request log not drained in time is reported, and so are the event loops if they still run.
  /// The test holds both cases: a loop blocked on a latch of the test, or the loops ended before
  /// the drain reports its timeout. Before, the budget test above returned `isDone()` right after
  /// the loops were told to stop, so whether this branch ran depended on their speed, and its
  /// coverage varied between runs.
  @ParameterizedTest(name = "event loops still running {0}")
  @ValueSource(booleans = {true, false})
  void undrainedRequestLogIsReportedWithRunningLoops(boolean loopsStillRunning) throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger waits = new AtomicInteger();
    proxy =
        new Proxy(
            COMMON_CONFIG,
            new RequestLogCapture(),
            () -> 0,
            ignored -> {},
            (future, millis) -> {
              if (waits.incrementAndGet() == 1) {
                // the connections, there are none
                return completes(future);
              }
              if (!loopsStillRunning) {
                assertThat(
                        proxy
                            .eventLoopGroup()
                            .terminationFuture()
                            .awaitUninterruptibly(EVENT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .as("event loops ended")
                    .isTrue();
              }
              // the request log drain times out
              return false;
            },
            ignored -> {});
    if (loopsStillRunning) {
      proxy.eventLoopGroup().next().execute(() -> awaitRelease(release));
    }
    try {
      proxy.shutdown();
    } finally {
      release.countDown();
    }
    assertThat(logs.messages(Proxy.class))
        .anyMatch(message -> message.startsWith("Shutdown: request log not drained"));
    if (loopsStillRunning) {
      assertThat(logs.messages(Proxy.class)).contains("Shutdown: event loop group still running");
    } else {
      assertThat(logs.messages(Proxy.class))
          .noneMatch(message -> message.contains("event loop group still running"));
    }
  }

  /// Waits for a shutdown future as the production wait does, so the outcome does not depend on
  /// how fast the event loops end
  private static boolean completes(CompletableFuture<?> future) {
    future.orTimeout(EVENT_TIMEOUT_SECONDS, TimeUnit.SECONDS).join();
    return true;
  }

  /// Blocks an event loop until the test releases it, so the loops are still running when the
  /// drain reports its timeout
  private static void awaitRelease(CountDownLatch release) {
    try {
      release.await(EVENT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Test
  void drainTimeoutDefaultsToAThirdOfTheShutdown() {
    ProxyConfig defaults = ProxyConfig.builder().build();
    assertThat(defaults.shutdownTimeoutMillis()).isEqualTo(5_000);
    assertThat(defaults.requestLog().drainTimeoutMillis()).isEqualTo(1_666);
    ProxyConfig longer = ProxyConfig.builder().shutdownTimeoutMillis(30_000).build();
    assertThat(longer.requestLog().drainTimeoutMillis()).isEqualTo(10_000);
    ProxyConfig tiny = ProxyConfig.builder().shutdownTimeoutMillis(2).build();
    assertThat(tiny.requestLog().drainTimeoutMillis()).as("never zero").isEqualTo(1);
  }

  /// The derived drain timeout is set in the configured request log, which keeps its other values
  @Test
  void drainTimeoutIsDerivedInTheConfiguredRequestLog() {
    ProxyConfig config =
        ProxyConfig.builder()
            .shutdownTimeoutMillis(9_000)
            .requestLog(RequestLogConfig.builder().plainText(true).build())
            .build();
    assertThat(config.requestLog())
        .isEqualTo(RequestLogConfig.builder().plainText(true).drainTimeoutMillis(3_000L).build());
  }

  @Test
  void configuredDrainTimeoutIsKept() {
    ProxyConfig config =
        ProxyConfig.builder()
            .requestLog(RequestLogConfig.builder().drainTimeoutMillis(5_000L).build())
            .build();
    assertThat(config.requestLog().drainTimeoutMillis()).as("may take all of it").isEqualTo(5_000);
    config.checkShutdownBudget();
  }

  /// A complete record keeps its values in `toBuilder()`, the derived drain timeout included
  @Test
  void toBuilderKeepsTheDerivedDrainTimeout() {
    ProxyConfig config = ProxyConfig.builder().build();
    ProxyConfig longer = config.toBuilder().shutdownTimeoutMillis(30_000).build();
    assertThat(longer.requestLog().drainTimeoutMillis()).isEqualTo(1_666);
    assertThat(config.toBuilder().build()).isEqualTo(config);
  }
}
