package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/// The [IpTraits] record: label validation and the header it renders. How the labels reach a
/// request is in [IpTraitsLabelsBDDTest].
class IpTraitsTest {

  /// A label must not break the `key=value, ...` format or pose as a built-in trait.
  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "crawler | google:googlebot",
        "crawler-not-admitted | example-seo:audit",
        "asn | 15169",
        "country | US",
        "'' | low",
        "risk | ''",
        "risk | 'low, crawler=x'",
        "risk | low=high",
        "ri sk | low",
        "risk | 'low\u0000'"
      })
  void invalidLabelsAreRejected(String key, String value) {
    assertThatThrownBy(() -> IpTraits.builder().addLabel(key, value))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /// The best match that is not admitted follows the crawler name, before the custom labels
  @Test
  void notAdmittedCrawlerFollowsTheCrawler() {
    IpTraits.Builder builder =
        IpTraits.builder()
            .asn(64496)
            .crawlerName("example-search:crawler")
            .notAdmittedCrawlerName("example-ai:user-fetcher");
    builder.addLabel("network", "hosting");
    IpTraits traits = builder.build();
    assertThat(traits.header())
        .isEqualTo(
            "asn=64496, crawler=example-search:crawler,"
                + " crawler-not-admitted=example-ai:user-fetcher, network=hosting");
    assertThat(traits.isCrawler()).isTrue();
    IpTraits notAdmitted = IpTraits.builder().notAdmittedCrawlerName("example-seo:audit").build();
    assertThat(notAdmitted.header()).isEqualTo("crawler-not-admitted=example-seo:audit");
    assertThat(notAdmitted.isCrawler()).isFalse();
  }

  @Test
  void headerAndLabelsStayStableAfterConstruction() {
    Map<String, String> labels = new HashMap<>();
    labels.put("z", "last");
    labels.put("a", "first");
    IpTraits traits = new IpTraits(-1, null, null, null, labels);
    labels.put("a", "changed");
    assertThat(traits.header()).isEqualTo("a=first, z=last");
    assertThat(traits.keyValue()).containsEntry("a", "first");
    assertThatThrownBy(() -> traits.keyValue().put("x", "y"))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
