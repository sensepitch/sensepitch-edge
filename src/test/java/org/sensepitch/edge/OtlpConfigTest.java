package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.sensepitch.edge.OtlpRetryConfigBDDTest.ENDPOINT;

import java.time.Duration;
import java.util.List;
import java.util.zip.Deflater;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/// Defaults and derived values of [OtlpConfig], and how they reach the [HttpBatchSender.Settings]
/// of the destination, without requests or timers. Rejected values and the retry delays are in
/// [OtlpRetryConfigBDDTest].
class OtlpConfigTest {

  @RegisterExtension LogCapture logs = new LogCapture();

  static OtlpConfig.Builder config() {
    return OtlpConfig.builder().endpoint(ENDPOINT);
  }

  @Test
  void defaults() {
    int eventLoops = ProxyEnvironment.current().eventLoopThreads();
    OtlpConfig config = OtlpConfig.builder().build();
    assertThat(config.timeoutMillis()).isEqualTo(OtlpConfig.DEFAULT_TIMEOUT_MILLIS);
    assertThat(config.flushIntervalMillis()).isEqualTo(1_000);
    assertThat(config.batchBytes()).isEqualTo(OtlpConfig.DEFAULT_BATCH_BYTES);
    assertThat(config.compression()).isEqualTo(OtlpConfig.GZIP);
    assertThat(config.maxConcurrentRequests())
        .as("every event loop can have a full batch in flight")
        .isEqualTo(eventLoops);
    assertThat(config.maxBufferedBytes())
        .as("the minimum, two batches per event loop")
        .isEqualTo(2L * eventLoops * OtlpConfig.DEFAULT_BATCH_BYTES)
        .isEqualTo(config.minMaxBufferedBytes());
  }

  /// The default buffer limit is taken without a warning, derived again after `toBuilder()`, and
  /// a larger value is kept
  @Test
  void bufferLimitDefaultsToTheMinimum() {
    OtlpConfig defaults = config().build();
    new OtlpLogDestination("otlp", defaults).finish();
    assertThat(logs.messages(OtlpLogDestination.class))
        .noneSatisfy(m -> assertThat(m).contains("not respected"));
    assertThat(defaults.toBuilder().maxBufferedBytes(0).build())
        .as("derived again after toBuilder(), for this JVM")
        .isEqualTo(defaults);
    int larger = (int) defaults.minMaxBufferedBytes() + 1;
    assertThat(
            OtlpLogDestination.settings("otlp", config().maxBufferedBytes(larger).build())
                .maxBufferedBytes())
        .isEqualTo(larger);
  }

  /// A buffer limit below the minimum is raised to it, with a warning that explains the minimum
  @Test
  void bufferLimitBelowTheMinimumIsRaised() {
    OtlpConfig tooSmall =
        config()
            .batchBytes(OtlpConfig.MIN_BATCH_BYTES)
            .maxBufferedBytes(OtlpConfig.MIN_BATCH_BYTES - 1)
            .build();
    assertThat(OtlpLogDestination.settings("otlp", tooSmall).maxBufferedBytes())
        .isEqualTo(tooSmall.minMaxBufferedBytes());
    new OtlpLogDestination("otlp", tooSmall).finish();
    assertThat(logs.messages(OtlpLogDestination.class))
        .anySatisfy(
            m ->
                assertThat(m)
                    .contains(
                        "maxBufferedBytes "
                            + (OtlpConfig.MIN_BATCH_BYTES - 1)
                            + " not respected, using the minimum "
                            + tooSmall.minMaxBufferedBytes()
                            + " = 2 × "
                            + ProxyEnvironment.current().eventLoopThreads()
                            + " event loops × "
                            + OtlpConfig.MIN_BATCH_BYTES
                            + " batchBytes"));
  }

  /// The default preserves the former `BEST_SPEED`, the configured level reaches the sender
  @Test
  void compressionLevelDefaultsAndReachesTheSender() {
    OtlpConfig defaults = config().build();
    assertThat(defaults.compressionLevel()).isEqualTo(Deflater.BEST_SPEED);
    assertThat(config().compressionLevel(0).build().compressionLevel())
        .isEqualTo(Deflater.BEST_SPEED);
    assertThat(OtlpLogDestination.settings("otlp", defaults).compressionLevel())
        .isEqualTo(Deflater.BEST_SPEED);
    for (int level : List.of(Deflater.BEST_SPEED, Deflater.BEST_COMPRESSION)) {
      assertThat(
              OtlpLogDestination.settings("otlp", config().compressionLevel(level).build())
                  .compressionLevel())
          .isEqualTo(level);
    }
  }

  /// An https endpoint is accepted, the timeout and the header sampling reach the destination
  @Test
  void valuesReachTheDestination() {
    new OtlpLogDestination("otlp", config().endpoint("https://127.0.0.1:1/v1/logs").build())
        .finish();
    assertThat(OtlpLogDestination.settings("otlp", config().timeoutMillis(1234).build()).timeout())
        .isEqualTo(Duration.ofMillis(1234));
    OtlpLogDestination destination =
        new OtlpLogDestination("otlp", config().build(), new RequestLogMetrics(), 27);
    assertThat(destination.sampleHeadersInterval()).isEqualTo(27);
    destination.finish();
  }
}
