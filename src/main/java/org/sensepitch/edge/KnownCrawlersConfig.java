package org.sensepitch.edge;

import java.util.List;
import lombok.Builder;

/// The crawler IP lists of the
/// [Sensepitch Known Crawlers](https://github.com/sensepitch/sensepitch-known-crawlers) release,
/// read from a directory instead of the lists embedded in the edge. An address on an admitted
/// list gets the `crawler` IP trait, `<vendor>:<list name>`, which lets a request with a
/// `User-Agent` bypass the protection. The best admitted list decides: the lowest tier, then
/// vendor and list name in alphabetical order. See [KnownCrawlerLists] for the format.
///
/// @param directory the directory with the release's zip files, e.g.
///   `sensepitch-known-crawlers-tier1.zip`; other files in it are ignored. Required. The start is
///   refused if the directory is missing, holds no list, a broken zip or a zip of an unknown
///   format version; a reload with such a directory is rejected and the active lists stay.
/// @param admittedTierLevel lists of tier 1 up to this tier are admitted as crawlers. Tier 1 are
///   the most trustworthy lists. At least 1, the default is 1.
/// @param excludeTypes tags, e.g. `ai-training-crawler`; a list with any of these tags is not
///   admitted. The default is none.
/// @author Jens Wilke
@Builder(toBuilder = true)
public record KnownCrawlersConfig(
    String directory, int admittedTierLevel, List<String> excludeTypes) {

  /// Only the lists of the most trustworthy tier
  static final int DEFAULT_ADMITTED_TIER_LEVEL = 1;

  /// Builder with the defaults preset
  public static Builder builder() {
    return new Builder().admittedTierLevel(DEFAULT_ADMITTED_TIER_LEVEL).excludeTypes(List.of());
  }

  /// Completed by Lombok, which generates everything but [#build()]
  public static class Builder {

    /// @throws IllegalArgumentException if `directory` or `excludeTypes` is missing or
    ///   `admittedTierLevel` is below 1
    public KnownCrawlersConfig build() {
      if (directory == null) {
        throw new IllegalArgumentException("ipLookup.knownCrawlers.directory is missing");
      }
      if (admittedTierLevel < 1) {
        throw new IllegalArgumentException(
            "ipLookup.knownCrawlers.admittedTierLevel must be at least 1");
      }
      if (excludeTypes == null) {
        throw new IllegalArgumentException("ipLookup.knownCrawlers.excludeTypes is missing");
      }
      return new KnownCrawlersConfig(directory, admittedTierLevel, List.copyOf(excludeTypes));
    }
  }
}
