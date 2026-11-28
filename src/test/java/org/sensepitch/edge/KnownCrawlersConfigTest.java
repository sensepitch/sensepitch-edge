package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/// The defaults, built values and rejected values of [KnownCrawlersConfig]
///
/// @author Jens Wilke
class KnownCrawlersConfigTest {

  static final String DIRECTORY = "/var/lib/sensepitch/known-crawlers";

  @Test
  void defaults() {
    KnownCrawlersConfig config = KnownCrawlersConfig.builder().directory(DIRECTORY).build();
    assertThat(config.directory()).isEqualTo(DIRECTORY);
    assertThat(config.admittedTierLevel()).isEqualTo(1);
    assertThat(config.excludeTypes()).isEmpty();
  }

  /// The tags are copied, so a later change of the given list does not change the configuration
  @Test
  void builtValues() {
    List<String> tags = new ArrayList<>(List.of("ai-training-crawler"));
    KnownCrawlersConfig config =
        KnownCrawlersConfig.builder()
            .directory(DIRECTORY)
            .admittedTierLevel(3)
            .excludeTypes(tags)
            .build();
    tags.add("cdn");
    assertThat(config.admittedTierLevel()).isEqualTo(3);
    assertThat(config.excludeTypes()).containsExactly("ai-training-crawler");
    assertThat(config.toBuilder().build()).isEqualTo(config);
  }

  @Test
  void directoryIsRequired() {
    assertThatThrownBy(() -> KnownCrawlersConfig.builder().build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("ipLookup.knownCrawlers.directory is missing");
  }

  @Test
  void tierLevelBelowOneIsRejected() {
    assertThatThrownBy(
            () -> KnownCrawlersConfig.builder().directory(DIRECTORY).admittedTierLevel(0).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("ipLookup.knownCrawlers.admittedTierLevel must be at least 1");
  }

  /// `null` is not read as no tags
  @Test
  void missingExcludeTypesAreRejected() {
    assertThatThrownBy(
            () -> KnownCrawlersConfig.builder().directory(DIRECTORY).excludeTypes(null).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("ipLookup.knownCrawlers.excludeTypes is missing");
  }
}
