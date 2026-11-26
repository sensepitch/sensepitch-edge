package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// Reads the flat IPinfo records from one database written by [TinyMmdb], one case per quarter
/// of the IPv4 space. The integration into the combined lookup and the reload is tested in
/// `ConfigReloadTest`.
///
/// @author Jens Wilke
public class IpInfoCountryAndAsnLookupTest {

  /// In `0.0.0.0/2`: country and ASN
  static final String COUNTRY_AND_ASN = "10.0.0.1";

  /// In `64.0.0.0/2`: a record without fields
  static final String EMPTY_RECORD = "70.0.0.1";

  /// In `128.0.0.0/2`: an ASN without a number
  static final String ASN_WITHOUT_NUMBER = "130.0.0.1";

  /// In `192.0.0.0/2`: no data
  static final String NOT_IN_DATABASE = "192.0.2.1";

  @TempDir static Path dir;

  static IpInfoCountryAndAsnLookup lookup;

  @BeforeAll
  static void writeAndOpenDatabase() throws Exception {
    Path database = dir.resolve("ipinfo.mmdb");
    TinyMmdb.writeIpInfoQuarters(
        database,
        Arrays.asList(
            Map.of("country_code", "BE", "asn", "AS15169"), Map.of(), Map.of("asn", "AS"), null));
    lookup = new IpInfoCountryAndAsnLookup(database.toString());
  }

  @AfterAll
  static void close() throws Exception {
    lookup.close();
  }

  /// The `AS` prefix of the IPinfo ASN is removed
  @Test
  public void countryAndAsn() throws Exception {
    IpTraits traits = lookup(COUNTRY_AND_ASN);
    assertThat(traits.isoCountry()).isEqualTo("BE");
    assertThat(traits.asn()).isEqualTo(15169);
  }

  /// A network without country and ASN, or with an ASN without a number, leaves the traits unset
  @Test
  public void missingValuesLeaveTraitsUnset() throws Exception {
    assertUnset(lookup(EMPTY_RECORD));
    assertUnset(lookup(ASN_WITHOUT_NUMBER));
  }

  @Test
  public void addressNotInDatabaseLeavesTraitsUnset() throws Exception {
    assertUnset(lookup(NOT_IN_DATABASE));
  }

  private static IpTraits lookup(String address) throws Exception {
    IpTraits.Builder builder = IpTraits.builder();
    lookup.collect(builder, InetAddress.getByName(address));
    return builder.build();
  }

  private static void assertUnset(IpTraits traits) {
    assertThat(traits.isoCountry()).isNull();
    assertThat(traits.isAsnKnown()).isFalse();
  }
}
