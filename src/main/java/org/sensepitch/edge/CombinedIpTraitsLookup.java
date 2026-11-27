package org.sensepitch.edge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
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

  private final List<IpTraitsCollector> collectors;
  private final IpLabelLookup ipLabelLookup;
  private final ProxyMetrics metrics;

  /// Opens the configured databases. If construction fails, closes the readers opened so far and
  /// suppresses cleanup failures on the original.
  ///
  /// @param files records the database files, which the database readers open themselves
  /// @param crawlerIpLists the parsed crawler IP lists, see [#readCrawlerIpLists()]; a reload
  ///   passes those of the lookup it replaces, see [EdgeState#build]
  public static CombinedIpTraitsLookup create(
      IpLookupConfig cfg, ProxyMetrics metrics, ConfigFiles files, IpLabelLookup crawlerIpLists)
      throws IOException {
    return create(cfg, metrics, files, crawlerIpLists, new ArrayList<>());
  }

  /// Accumulates the readers owned by this attempt until the completed lookup takes ownership.
  static CombinedIpTraitsLookup create(
      IpLookupConfig cfg,
      ProxyMetrics metrics,
      ConfigFiles files,
      IpLabelLookup crawlerIpLists,
      List<IpTraitsCollector> collectors)
      throws IOException {
    try {
      if (cfg.geoIp2() != null) {
        if (cfg.geoIp2().asnDbPath() != null) {
          files.record(Path.of(cfg.geoIp2().asnDbPath()));
          collectors.add(new GeoIp2AsnLookup(cfg.geoIp2().asnDbPath()));
        }
        if (cfg.geoIp2().countryDbPath() != null) {
          files.record(Path.of(cfg.geoIp2().countryDbPath()));
          collectors.add(new GeoIp2CountryLookup(cfg.geoIp2().countryDbPath()));
        }
      }
      if (cfg.ipInfoPath() != null) {
        files.record(Path.of(cfg.ipInfoPath()));
        collectors.add(new IpInfoCountryAndAsnLookup(cfg.ipInfoPath()));
      }
      return new CombinedIpTraitsLookup(collectors, metrics, crawlerIpLists);
    } catch (Throwable failure) {
      closeAfterFailure(collectors.iterator(), failure);
      throw failure;
    }
  }

  /// @param collectors queried in order for each address, a later one overwrites what an earlier
  ///   one found, and closed by [#close()]. Tests pass fakes here instead of databases.
  /// @param crawlerIpLists the labels of the crawler addresses, tests pass their own, so they do
  ///   not parse the shipped lists each time
  CombinedIpTraitsLookup(
      List<IpTraitsCollector> collectors, ProxyMetrics metrics, IpLabelLookup crawlerIpLists) {
    this.collectors = List.copyOf(collectors);
    this.metrics = metrics;
    ipLabelLookup = crawlerIpLists;
  }

  /// The crawler IP lists, which a rebuilt lookup takes over instead of parsing them again, see
  /// [EdgeState#build]. They are immutable and not released by [#close()].
  IpLabelLookup crawlerIpLists() {
    return ipLabelLookup;
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
    LOG.info("IP lookup nodes: " + lookup.getNodeCount());
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

  @Override
  public IpTraits lookup(InetAddress address) {
    var builder = IpTraits.builder();
    for (IpTraitsCollector db : collectors) {
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

  /// Counts a failed database lookup, a request level event, see the class doc
  private void lookupException(Exception e) {
    LOG.incrementAndWarnOnce(
        metrics.ipLookupErrorCounter, ProxyLogger.NO_LABELS, null, "IP database lookup failed", e);
  }

  /// Closes the database readers this lookup owns, so a reload that replaces the lookup releases
  /// them. Called delayed by [EdgeState#closeReplacedBy]. A lookup after the close finds its
  /// reader closed, which [#lookup(InetAddress)] counts as an IP lookup error. Every reader is
  /// closed even if one fails; the first failure is thrown, later ones are suppressed in it.
  @Override
  public void close() throws Exception {
    var remaining = collectors.iterator();
    try {
      while (remaining.hasNext()) {
        remaining.next().close();
      }
    } catch (Throwable failure) {
      closeAfterFailure(remaining, failure);
      throw failure;
    }
  }

  /// Releases every remaining reader while preserving the failure that triggered cleanup.
  private static void closeAfterFailure(Iterator<IpTraitsCollector> remaining, Throwable failure) {
    while (remaining.hasNext()) {
      try {
        remaining.next().close();
      } catch (Throwable cleanup) {
        if (cleanup != failure) {
          failure.addSuppressed(cleanup);
        }
      }
    }
  }
}
