package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// Reads the flat IPinfo records from a database written by [TinyMmdb]. The integration into the
/// combined lookup and the reload is tested in `ConfigReloadTest`.
///
/// @author Jens Wilke
public class IpInfoCountryAndAsnLookupTest {

  /// In the lower half of the IPv4 space, which [TinyMmdb#writeIpInfoLowerHalf] maps to the record
  static final String IN_DATABASE = "10.0.0.1";

  /// In the upper half, which has no data
  static final String NOT_IN_DATABASE = "192.0.2.1";

  @TempDir Path dir;

  /// The `AS` prefix of the IPinfo ASN is removed
  @Test
  public void countryAndAsn() throws Exception {
    IpTraits traits = lookup(Map.of("country_code", "BE", "asn", "AS15169"), IN_DATABASE);
    assertThat(traits.isoCountry()).isEqualTo("BE");
    assertThat(traits.asn()).isEqualTo(15169);
  }

  /// A network without country and ASN, or with an ASN without a number, leaves the traits unset
  @Test
  public void missingValuesLeaveTraitsUnset() throws Exception {
    assertUnset(lookup(Map.of(), IN_DATABASE));
    assertUnset(lookup(Map.of("asn", "AS"), IN_DATABASE));
  }

  @Test
  public void addressNotInDatabaseLeavesTraitsUnset() throws Exception {
    assertUnset(lookup(Map.of("country_code", "BE", "asn", "AS15169"), NOT_IN_DATABASE));
  }

  private IpTraits lookup(Map<String, String> fields, String address) throws Exception {
    Path database = dir.resolve("ipinfo.mmdb");
    TinyMmdb.writeIpInfoLowerHalf(database, fields);
    try (var lookup = new IpInfoCountryAndAsnLookup(database.toString())) {
      IpTraits.Builder builder = IpTraits.builder();
      lookup.collect(builder, InetAddress.getByName(address));
      return builder.build();
    }
  }

  private static void assertUnset(IpTraits traits) {
    assertThat(traits.isoCountry()).isNull();
    assertThat(traits.isAsnKnown()).isFalse();
  }
}
