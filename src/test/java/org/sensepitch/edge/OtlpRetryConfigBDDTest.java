package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import net.serenitybdd.annotations.Step;
import net.serenitybdd.junit5.SerenityJUnit5Extension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

/// Retry delays of a request log destination: defaults, built values and rejected values, from
/// the configuration record to the [Backoff] of the sender, without requests or timers. The retry
/// itself is covered by the sender and breaker tests. Also the explicit `0` of the other numbers
/// of [OtlpConfig], which is rejected and no placeholder for the default, and the other rejected
/// values of the destination; new ones are rows of [#invalidValues()]. Defaults and derived values
/// of the other settings are in [OtlpConfigTest].
@ExtendWith(SerenityJUnit5Extension.class)
class OtlpRetryConfigBDDTest {

  static final String ENDPOINT = "http://127.0.0.1:1/v1/logs";

  @Test
  void omittedDelaysKeepTheDefaults() {
    new Steps()
        .givenConfig(OtlpConfig.builder().endpoint(ENDPOINT).build())
        .thenBuiltDelaysAre(1_000, 30_000)
        .thenProductionBackoffIs(1_000, 30_000);
  }

  /// An empty column is an omitted delay
  @ParameterizedTest
  @CsvSource({
    "250, , 250, 30000",
    ", 60000, 1000, 60000",
    ", 1000, 1000, 1000",
    "30000, , 30000, 30000",
    "1, 1, 1, 1",
    "40000, 60000, 40000, 60000",
    "2147483647, 2147483647, 2147483647, 2147483647"
  })
  void configuredDelaysReachTheSender(
      Integer initial, Integer max, int builtInitial, int builtMax) {
    new Steps()
        .givenDelays(initial, max)
        .thenBuiltDelaysAre(builtInitial, builtMax)
        .thenProductionBackoffIs(builtInitial, builtMax);
  }

  /// An empty column is an omitted delay
  @ParameterizedTest
  @CsvSource({
    "-1, , initialRetryDelayMillis must be positive",
    "0, , initialRetryDelayMillis must be positive",
    ", -1, maxRetryDelayMillis must be positive",
    ", 0, maxRetryDelayMillis must be positive",
    "0, 0, initialRetryDelayMillis must be positive",
    "2000, 1000, maxRetryDelayMillis must be at least initialRetryDelayMillis",
    ", 999, maxRetryDelayMillis must be at least initialRetryDelayMillis",
    "30001, , maxRetryDelayMillis must be at least initialRetryDelayMillis"
  })
  void invalidDelaysAreRejected(Integer initial, Integer max, String reason) {
    new Steps().givenDelays(initial, max).thenDestinationIsRejected(reason);
  }

  static Stream<Arguments> zeroValues() {
    return Stream.of(
        rejected("timeoutMillis", b -> b.timeoutMillis(0), "timeoutMillis must be positive"),
        rejected(
            "flushIntervalMillis",
            b -> b.flushIntervalMillis(0),
            "flushIntervalMillis must be positive"),
        rejected(
            "batchBytes",
            b -> b.batchBytes(0),
            "batchBytes must be at least " + OtlpConfig.MIN_BATCH_BYTES),
        rejected(
            "maxConcurrentRequests",
            b -> b.maxConcurrentRequests(0),
            "maxConcurrentRequests must be positive"),
        rejected(
            "maximumUnsentLogAgeMillis",
            b -> b.maximumUnsentLogAgeMillis(0),
            "maximumUnsentLogAgeMillis must be positive"));
  }

  /// Values the destination rejects other than an explicit `0`. A rule with a bound is checked
  /// on both sides of it.
  static Stream<Arguments> invalidValues() {
    String compressionLevelRange =
        "compressionLevel must be between "
            + OtlpConfig.MIN_COMPRESSION_LEVEL
            + " and "
            + OtlpConfig.MAX_COMPRESSION_LEVEL
            + ", not ";
    return Stream.of(
        rejected("endpoint omitted", b -> b.endpoint(null), "endpoint missing"),
        rejected("endpoint blank", b -> b.endpoint(" "), "endpoint missing"),
        rejected(
            "endpoint not http",
            b -> b.endpoint("ftp://example.com"),
            "endpoint must be an http or https URL"),
        rejected(
            "unknown compression",
            b -> b.compression("zstd"),
            "compression must be gzip or none, not zstd"),
        // 0 is taken as the default level, see OtlpConfigTest, so the value below is negative
        rejected(
            "compressionLevel below", b -> b.compressionLevel(-1), compressionLevelRange + "-1"),
        rejected(
            "compressionLevel above",
            b -> b.compressionLevel(OtlpConfig.MAX_COMPRESSION_LEVEL + 1),
            compressionLevelRange + (OtlpConfig.MAX_COMPRESSION_LEVEL + 1)));
  }

  static Arguments rejected(String key, UnaryOperator<OtlpConfig.Builder> change, String reason) {
    return Arguments.of(key, change, reason);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("zeroValues")
  void explicitZeroIsRejected(String key, UnaryOperator<OtlpConfig.Builder> change, String reason) {
    new Steps().givenChangedConfig(change).thenDestinationIsRejected(reason);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidValues")
  void invalidValueIsRejected(String key, UnaryOperator<OtlpConfig.Builder> change, String reason) {
    new Steps().givenChangedConfig(change).thenDestinationIsRejected(reason);
  }

  static class Steps {
    OtlpConfig config;

    @Step("Configure initial delay {0} ms and maximum delay {1} ms, null if omitted")
    Steps givenDelays(Integer initial, Integer max) {
      OtlpConfig.Builder builder = OtlpConfig.builder().endpoint(ENDPOINT);
      if (initial != null) {
        builder.initialRetryDelayMillis(initial);
      }
      if (max != null) {
        builder.maxRetryDelayMillis(max);
      }
      return givenConfig(builder.build());
    }

    @Step("Change one value of an otherwise valid configuration")
    Steps givenChangedConfig(UnaryOperator<OtlpConfig.Builder> change) {
      return givenConfig(change.apply(OtlpConfig.builder().endpoint(ENDPOINT)).build());
    }

    @Step("Use the destination configuration")
    Steps givenConfig(OtlpConfig value) {
      config = value;
      return this;
    }

    @Step("Built delays are {0} ms and {1} ms")
    Steps thenBuiltDelaysAre(int initial, int max) {
      assertThat(config.initialRetryDelayMillis()).isEqualTo(initial);
      assertThat(config.maxRetryDelayMillis()).isEqualTo(max);
      return this;
    }

    @Step("The sender backs off from {0} ms up to {1} ms with jitter")
    Steps thenProductionBackoffIs(int initial, int max) {
      assertThat(OtlpLogDestination.settings("configured", config).backoff())
          .isEqualTo(new Backoff(initial, max));
      return this;
    }

    @Step("Destination validation rejects the configuration: {0}")
    Steps thenDestinationIsRejected(String reason) {
      assertThatThrownBy(() -> new OtlpLogDestination("invalid", config))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("Request log destination invalid: " + reason);
      return this;
    }
  }
}
