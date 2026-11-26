package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/// @author Jens Wilke
public class CombinedIpTraitsLookupTest {

  @RegisterExtension LogCapture logs = new LogCapture();

  ProxyMetrics metrics = new ProxyMetrics();

  /// The shipped crawler lists, IPv4 and IPv6. The only test of the lists, the others pass
  /// [#crawlerIpLists()], so the lists are not parsed for each of them.
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
    assertThat(lookup(lookup, "2001:4860:4801:10::1").crawlerName()).isEqualTo("google:googlebot");
    assertThat(lookup(lookup, "2001:db8::1").isCrawler()).isFalse();
  }

  /// A crawler address of the test lists from [#crawlerIpLists()]
  static final String CRAWLER_ADDRESS = "192.0.2.1";

  static final String CRAWLER_NAME = "test:bot";

  /// Crawler lists with one network, [#CRAWLER_ADDRESS] is in it
  static IpLabelLookup crawlerIpLists() {
    TrieIpLabelLookup lists = new TrieIpLabelLookup();
    lists.insertIpv4("192.0.2.0/24", CombinedIpTraitsLookup.CRAWLER_LABEL_PREFIX + CRAWLER_NAME);
    return lists;
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
            metrics,
            crawlerIpLists());
    IpTraits traits = lookup(lookup, CRAWLER_ADDRESS);
    assertThat(traits.asn()).isEqualTo(15169);
    assertThat(traits.isoCountry()).isEqualTo("US");
    assertThat(traits.crawlerName()).isEqualTo(CRAWLER_NAME);
  }

  /// Addresses not in a database, e.g. private ranges, leave the traits unset.
  @Test
  public void notFoundLeavesTraitsUnset() throws IOException {
    var lookup =
        new CombinedIpTraitsLookup(
            List.of(
                CombinedIpTraitsLookup.asnTraits(addr -> -1),
                CombinedIpTraitsLookup.countryTraits(addr -> null)),
            metrics,
            crawlerIpLists());
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
            metrics,
            crawlerIpLists());
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
            metrics,
            crawlerIpLists());
    IpTraits traits = lookup(lookup, CRAWLER_ADDRESS);
    assertThat(traits.isAsnKnown()).isFalse();
    assertThat(traits.isoCountry()).isEqualTo("US");
    assertThat(traits.crawlerName()).isEqualTo(CRAWLER_NAME);
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

  /// A lookup built without databases has nothing to close
  @Test
  public void closeWithoutDatabasesDoesNothing() throws Exception {
    var lookup = new CombinedIpTraitsLookup(List.of(), metrics, crawlerIpLists());
    lookup.close();
  }

  /// A failing reader does not keep the others open: all are closed, the first failure is thrown
  /// with the later ones suppressed
  @Test
  public void closeClosesAllReadersAndThrowsFirstFailure() throws Exception {
    List<String> closed = new ArrayList<>();
    var lookup =
        new CombinedIpTraitsLookup(
            List.of(),
            List.of(
                () -> {
                  closed.add("a");
                  throw new IOException("a failed");
                },
                () -> closed.add("b"),
                () -> {
                  closed.add("c");
                  throw new IOException("c failed");
                }),
            metrics,
            crawlerIpLists());
    assertThatThrownBy(lookup::close)
        .hasMessage("a failed")
        .satisfies(
            e ->
                assertThat(e.getSuppressed())
                    .singleElement()
                    .extracting(Throwable::getMessage)
                    .isEqualTo("c failed"));
    assertThat(closed).containsExactly("a", "b", "c");
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
  /// country, leaves the trait unset. The close releases both readers, a lookup afterwards counts
  /// an error per database.
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
    lookup.close();
    IpTraits closed = lookup(lookup, "81.2.69.142");
    assertThat(closed.isoCountry()).isNull();
    assertThat(closed.isAsnKnown()).isFalse();
    assertThat(metrics.ipLookupErrorCounter.collect().getDataPoints().getFirst().getValue())
        .isEqualTo(2);
  }

  /// An ASN-only GeoIP2 configuration opens only the ASN database, the country database is not
  /// opened because it is not configured.
  @Test
  public void asnOnlyGeoIp2Database() throws Exception {
    Path asn = maxmindDatabase("GeoLite2-ASN-Test.mmdb");
    ConfigFiles files = new ConfigFiles();
    var lookup =
        new CombinedIpTraitsLookup(
            IpLookupConfig.builder()
                .geoIp2(GeoIp2Config.builder().asnDbPath(asn.toString()).build())
                .build(),
            metrics,
            files);
    assertThat(files.snapshot().files()).containsKey(asn);
    assertThat(lookup(lookup, "1.128.0.1").asn()).isEqualTo(1221);
  }
}
