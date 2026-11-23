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

  @Test
  void headerAndLabelsStayStableAfterConstruction() {
    Map<String, String> labels = new HashMap<>();
    labels.put("z", "last");
    labels.put("a", "first");
    IpTraits traits = new IpTraits(-1, null, null, labels);
    labels.put("a", "changed");
    assertThat(traits.header()).isEqualTo("a=first, z=last");
    assertThat(traits.keyValue()).containsEntry("a", "first");
    assertThatThrownBy(() -> traits.keyValue().put("x", "y"))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
