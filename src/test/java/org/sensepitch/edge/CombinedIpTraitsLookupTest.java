package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/// @author Jens Wilke
public class CombinedIpTraitsLookupTest {

  @RegisterExtension LogCapture logs = new LogCapture();

  ProxyMetrics metrics = new ProxyMetrics();

  @Test
  public void test() throws IOException {
    var x =
        new CombinedIpTraitsLookup(IpLookupConfig.builder().build(), metrics, new ConfigFiles());
  }

  @Test
  public void crawlerName() throws IOException {
    var lookup =
        new CombinedIpTraitsLookup(IpLookupConfig.builder().build(), metrics, new ConfigFiles());
    IpTraits amazonbot = lookup(lookup, "3.210.223.61");
    assertThat(amazonbot.crawlerName()).isEqualTo("amazon:amazonbot");
    IpTraits googlebot = lookup(lookup, "192.178.4.1");
    assertThat(googlebot.crawlerName()).isEqualTo("google:googlebot");
    IpTraits other = lookup(lookup, "1.2.3.4");
    assertThat(other.isCrawler()).isFalse();
    assertThat(other.crawlerName()).isNull();
  }

  @Test
  public void crawlerNameIpv6() throws IOException {
    var lookup =
        new CombinedIpTraitsLookup(IpLookupConfig.builder().build(), metrics, new ConfigFiles());
    assertThat(lookup(lookup, "2001:4860:4801:10::1").crawlerName()).isEqualTo("google:googlebot");
    assertThat(lookup(lookup, "2001:db8::1").isCrawler()).isFalse();
  }

  @Test
  public void crawlerListName() {
    assertThat(CombinedIpTraitsLookup.crawlerListName("crawler-ips/google/googlebot.json"))
        .isEqualTo("google:googlebot");
    assertThat(CombinedIpTraitsLookup.crawlerListName("crawler-ips/plain")).isEqualTo("plain");
  }

  /// Country and ASN from the databases are combined with the crawler label from the lists.
  @Test
  public void databaseResultsAreCombined() throws IOException {
    var lookup =
        new CombinedIpTraitsLookup(
            List.of(
                CombinedIpTraitsLookup.asnTraits(addr -> 15169),
                CombinedIpTraitsLookup.countryTraits(addr -> "US")),
            metrics);
    IpTraits traits = lookup(lookup, "192.178.4.1");
    assertThat(traits.asn()).isEqualTo(15169);
    assertThat(traits.isoCountry()).isEqualTo("US");
    assertThat(traits.crawlerName()).isEqualTo("google:googlebot");
  }

  /// Addresses not in a database, e.g. private ranges, leave the traits unset.
  @Test
  public void notFoundLeavesTraitsUnset() throws IOException {
    var lookup =
        new CombinedIpTraitsLookup(
            List.of(
                CombinedIpTraitsLookup.asnTraits(addr -> -1),
                CombinedIpTraitsLookup.countryTraits(addr -> null)),
            metrics);
    IpTraits traits = lookup(lookup, "10.0.0.1");
    assertThat(traits.isAsnKnown()).isFalse();
    assertThat(traits.isoCountry()).isNull();
  }

  /// A later database overwrites what an earlier one found, the configuration order decides.
  @Test
  public void laterLookupWins() throws IOException {
    var lookup =
        new CombinedIpTraitsLookup(
            List.of(
                CombinedIpTraitsLookup.countryTraits(addr -> "DE"),
                CombinedIpTraitsLookup.countryTraits(addr -> "AT")),
            metrics);
    assertThat(lookup(lookup, "1.2.3.4").isoCountry()).isEqualTo("AT");
  }

  /// A broken database must not fail the request: the error is counted and logged at debug, the
  /// other lookups and the crawler lists still apply.
  @Test
  public void failingLookupIsLoggedAndSkipped() throws IOException {
    var lookup =
        new CombinedIpTraitsLookup(
            List.of(
                CombinedIpTraitsLookup.asnTraits(
                    addr -> {
                      throw new IOException("database corrupt");
                    }),
                CombinedIpTraitsLookup.countryTraits(addr -> "US")),
            metrics);
    IpTraits traits = lookup(lookup, "192.178.4.1");
    assertThat(traits.isAsnKnown()).isFalse();
    assertThat(traits.isoCountry()).isEqualTo("US");
    assertThat(traits.crawlerName()).isEqualTo("google:googlebot");
    assertThat(metrics.ipLookupErrorCounter.collect().getDataPoints().getFirst().getValue())
        .isOne();
    assertThat(logs.errors()).isEmpty();
    assertThat(logs.entries())
        .filteredOn(e -> e.info().level() == System.Logger.Level.DEBUG)
        .singleElement()
        .satisfies(
            e -> {
              assertThat(e.source()).isEqualTo(CombinedIpTraitsLookup.class.getName());
              assertThat(e.error()).hasMessage("database corrupt");
            });
  }

  private static IpTraits lookup(CombinedIpTraitsLookup lookup, String address) throws IOException {
    return lookup.lookup(InetAddress.getByName(address));
  }

  /// The MaxMind test databases, see `src/test/resources/maxmind/README.md`
  static Path maxmindDatabase(String name) throws Exception {
    return Path.of(CombinedIpTraitsLookupTest.class.getResource("/maxmind/" + name).toURI());
  }

  /// The configured GeoIP2 databases are read and recorded, so the configuration file watcher
  /// reloads when one is replaced. An address the databases do not know, or a network without a
  /// country, leaves the trait unset.
  @Test
  public void geoIp2Databases() throws Exception {
    Path country = maxmindDatabase("GeoIP2-Country-Test.mmdb");
    Path asn = maxmindDatabase("GeoLite2-ASN-Test.mmdb");
    ConfigFiles files = new ConfigFiles();
    var lookup =
        new CombinedIpTraitsLookup(
            IpLookupConfig.builder()
                .geoIp2(
                    GeoIp2Config.builder()
                        .countryDbPath(country.toString())
                        .asnDbPath(asn.toString())
                        .build())
                .build(),
            metrics,
            files);
    assertThat(files.snapshot().files()).containsKeys(country, asn);
    assertThat(lookup(lookup, "81.2.69.142").isoCountry()).isEqualTo("GB");
    assertThat(lookup(lookup, "1.128.0.1").asn()).isEqualTo(1221);
    IpTraits unknown = lookup(lookup, "10.0.0.1");
    assertThat(unknown.isoCountry()).isNull();
    assertThat(unknown.isAsnKnown()).isFalse();
    // only registered_country and traits in the database
    assertThat(lookup(lookup, "214.1.1.1").isoCountry()).isNull();
    assertThat(logs.messages()).noneMatch(m -> m.contains("lookup failed"));
  }
}
