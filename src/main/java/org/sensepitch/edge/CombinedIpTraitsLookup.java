package org.sensepitch.edge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/// Combines the IP databases and the crawler IP lists. A database that throws on a lookup is a
/// request level event: it is counted in [ProxyMetrics#ipLookupErrorCounter] and logged at debug,
/// the first one at warning, see [ProxyLogger] for the levels, and the other databases are still
/// queried. The database readers it opened are released with [#close()]; a reload replaces the
/// lookup and closes the old one a few seconds later, see [EdgeState#closeReplacedBy].
///
/// @author Jens Wilke
public class CombinedIpTraitsLookup implements IpTraitsLookup, AutoCloseable {

  static final String CRAWLER_IPS_DIRECTORY = "crawler-ips/";
  static final String CRAWLER_LABEL_PREFIX = "crawler:";
  static final String UNKNOWN_CRAWLER_NAME = "unknown";

  private static final ProxyLogger LOG = ProxyLogger.get(CombinedIpTraitsLookup.class);

  private final List<IpTraitsCollector> ipAttributesLookups;
  private final List<AutoCloseable> closeables;
  private final IpLabelLookup ipLabelLookup;
  private final ProxyMetrics metrics;

  /// @param files records the database files, which the database readers open themselves
  public CombinedIpTraitsLookup(
      IpLookupConfig ipLookupConfig, ProxyMetrics metrics, ConfigFiles files) throws IOException {
    this(databaseLookups(ipLookupConfig, files), metrics, readCrawlerIpLists());
  }

  /// @param ipAttributesLookups queried in order for each address, a later one overwrites what an
  ///   earlier one found. Tests pass fakes here instead of databases, so there is nothing to close.
  /// @param crawlerIpLists the labels of the crawler addresses, tests pass their own, so they do
  ///   not parse the shipped lists each time
  CombinedIpTraitsLookup(
      List<IpTraitsCollector> ipAttributesLookups,
      ProxyMetrics metrics,
      IpLabelLookup crawlerIpLists) {
    this(ipAttributesLookups, List.of(), metrics, crawlerIpLists);
  }

  private CombinedIpTraitsLookup(
      DatabaseLookups lookups, ProxyMetrics metrics, TrieIpLabelLookup crawlerIpLists) {
    this(lookups.collectors(), lookups.closeables(), metrics, crawlerIpLists);
    LOG.info("IP lookup nodes: " + crawlerIpLists.getNodeCount());
  }

  /// @param closeables closed by [#close()], tests pass fakes to check the close
  CombinedIpTraitsLookup(
      List<IpTraitsCollector> ipAttributesLookups,
      List<AutoCloseable> closeables,
      ProxyMetrics metrics,
      IpLabelLookup crawlerIpLists) {
    this.ipAttributesLookups = List.copyOf(ipAttributesLookups);
    this.closeables = List.copyOf(closeables);
    this.metrics = metrics;
    ipLabelLookup = crawlerIpLists;
  }

  /// The database readers opened for a configuration, and the collectors that query them
  private record DatabaseLookups(
      List<IpTraitsCollector> collectors, List<AutoCloseable> closeables) {}

  /// Opens the databases configured
  private static DatabaseLookups databaseLookups(IpLookupConfig cfg, ConfigFiles files)
      throws IOException {
    List<IpTraitsCollector> lookups = new ArrayList<>();
    List<AutoCloseable> closeables = new ArrayList<>();
    if (cfg.geoIp2() != null) {
      if (cfg.geoIp2().asnDbPath() != null) {
        files.record(Path.of(cfg.geoIp2().asnDbPath()));
        GeoIp2AsnLookup asn = new GeoIp2AsnLookup(cfg.geoIp2().asnDbPath());
        lookups.add(asnTraits(asn));
        closeables.add(asn);
      }
      if (cfg.geoIp2().countryDbPath() != null) {
        files.record(Path.of(cfg.geoIp2().countryDbPath()));
        GeoIp2CountryLookup country = new GeoIp2CountryLookup(cfg.geoIp2().countryDbPath());
        lookups.add(countryTraits(country));
        closeables.add(country);
      }
    }
    if (cfg.ipInfoPath() != null) {
      files.record(Path.of(cfg.ipInfoPath()));
      IpInfoCountryAndAsnLookup ipInfo = new IpInfoCountryAndAsnLookup(cfg.ipInfoPath());
      lookups.add(ipInfo);
      closeables.add(ipInfo);
    }
    return new DatabaseLookups(lookups, closeables);
  }

  /// Reads the published crawler IP lists named in `crawler-ips/index.txt`. Each address is labeled
  /// `crawler:<list name>`. The list name is the path below `crawler-ips/` without `.json`, with
  /// the directory as namespace separated by `:`, e.g. `google/googlebot.json` becomes
  /// `google:googlebot`. Entries without a prefix length are single addresses.
  public static TrieIpLabelLookup readCrawlerIpLists() throws IOException {
    TrieIpLabelLookup lookup = new TrieIpLabelLookup();
    ObjectMapper mapper = new ObjectMapper();
    for (var file : ResourceLoader.getFileList(CRAWLER_IPS_DIRECTORY)) {
      String label = CRAWLER_LABEL_PREFIX + crawlerListName(file);
      JsonNode root = mapper.readTree(Proxy.class.getResource("/" + file));
      for (JsonNode prefix : root.path("prefixes")) {
        if (prefix.has("ipv4Prefix")) {
          lookup.insertIpv4(withPrefixLength(prefix.get("ipv4Prefix").asText(), 32), label);
        } else if (prefix.has("ipv6Prefix")) {
          lookup.insertIpv6(withPrefixLength(prefix.get("ipv6Prefix").asText(), 128), label);
        }
      }
    }
    return lookup;
  }

  static String crawlerListName(String file) {
    String name = file.substring(CRAWLER_IPS_DIRECTORY.length());
    if (name.endsWith(".json")) {
      name = name.substring(0, name.length() - ".json".length());
    }
    return name.replace('/', ':');
  }

  private static String withPrefixLength(String prefix, int addressBits) {
    return prefix.contains("/") ? prefix : prefix + "/" + addressBits;
  }

  /// Sets the ASN, if found
  static IpTraitsCollector asnTraits(AsnLookup asnLookup) {
    return (builder, address) -> {
      long asn = asnLookup.lookupAsn(address);
      if (asn >= 0) {
        builder.asn(asn);
      }
    };
  }

  /// Sets the country, if found
  static IpTraitsCollector countryTraits(CountryLookup countryLookup) {
    return (builder, address) -> {
      var country = countryLookup.lookupCountry(address);
      if (country != null) {
        builder.isoCountry(country);
      }
    };
  }

  private void lookupException(Exception e) {
    LOG.incrementAndWarnOnce(
        metrics.ipLookupErrorCounter, ProxyLogger.NO_LABELS, null, "IP database lookup failed", e);
  }

  @Override
  public IpTraits lookup(InetAddress address) {
    var builder = IpTraits.builder();
    for (IpTraitsCollector db : ipAttributesLookups) {
      try {
        db.collect(builder, address);
      } catch (Exception e) {
        lookupException(e);
      }
    }
    byte[] addressBytes = address.getAddress();
    List<String> labelList;
    if (addressBytes.length == 4) {
      labelList = ipLabelLookup.lookupIpv4(addressBytes);
    } else {
      labelList = ipLabelLookup.lookupIpv6(addressBytes);
    }
    if (labelList != null) {
      for (String label : labelList) {
        if (label.startsWith(CRAWLER_LABEL_PREFIX)) {
          String name = label.substring(CRAWLER_LABEL_PREFIX.length());
          builder.crawlerName(name.isEmpty() ? UNKNOWN_CRAWLER_NAME : name);
        }
      }
    }
    return builder.build();
  }

  /// Closes the database readers this lookup owns, so a reload that replaces the lookup releases
  /// them. Called delayed by [EdgeState#closeReplacedBy]. A lookup after the close finds its
  /// reader closed, which [#lookup(InetAddress)] counts as an IP lookup error. Every reader is
  /// closed even if one fails; the first failure is thrown, later ones are suppressed in it.
  @Override
  public void close() throws Exception {
    Exception failure = null;
    for (AutoCloseable closeable : closeables) {
      try {
        closeable.close();
      } catch (Exception e) {
        if (failure == null) {
          failure = e;
        } else {
          failure.addSuppressed(e);
        }
      }
    }
    if (failure != null) {
      throw failure;
    }
  }
}
