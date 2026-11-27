package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.sensepitch.edge.CompleteTest.COMMON_CONFIG;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/// Shutdown timeout defaults and drain budgets. [ProxyRejectedConfigBDDTest] covers invalid values.
///
/// @author Jens Wilke
class ShutdownTimeoutTest {

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
              return future.isDone();
            },
            ignored -> {});
    proxy.shutdown();
    assertThat(waitBudgets).containsExactly(70L, remainingMillis);
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
