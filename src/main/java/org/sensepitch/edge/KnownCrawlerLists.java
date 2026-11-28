package org.sensepitch.edge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/// The crawler IP lists of the Sensepitch Known Crawlers release, read from the tier zips in a
/// directory, see [#read(Path)]. [#lookup(InetAddress)] returns every feed an address matches.
/// The format is described in the README of
/// [sensepitch-known-crawlers](https://github.com/sensepitch/sensepitch-known-crawlers): each zip
/// has a `meta.json` with the format `version` and the `tier`, a list per feed in
/// `lists/<vendor>/<listname>.json` and its description in `info/<vendor>/<listname>.json`.
/// The optional `tags` field in the description must be an array of strings.
///
/// Configured with [KnownCrawlersConfig], [#readAdmitted(KnownCrawlersConfig)] gives the lists
/// the IP lookup takes the `crawler` trait from. Immutable once read; a rebuilt IP lookup reads
/// the directory again, and a reload whose read fails is rejected, so the previous lists stay.
///
/// @author Jens Wilke
public final class KnownCrawlerLists {

  /// The only archive format `version` of `meta.json` this reader understands
  static final int SUPPORTED_VERSION = 1;

  static final String ZIP_SUFFIX = ".zip";
  static final String META_ENTRY = "meta.json";
  static final String LISTS_DIRECTORY = "lists/";
  static final String INFO_DIRECTORY = "info/";
  static final String JSON_SUFFIX = ".json";

  private static final int IPV4_BITS = 32;
  private static final int IPV6_BITS = 128;

  /// One feed of the release. The `tier` is the one of the zip's `meta.json`, the tier in the
  /// `info` file is not used.
  ///
  /// @param tags the feed's types, in registry order
  public record Feed(String vendor, String listName, int tier, List<String> tags) {

    /// The order of [KnownCrawlerLists#lookup(InetAddress)]: tier, then vendor, then list name
    public static final Comparator<Feed> ORDER =
        Comparator.comparingInt(Feed::tier)
            .thenComparing(Feed::vendor)
            .thenComparing(Feed::listName);

    public Feed {
      tags = List.copyOf(tags);
    }

    /// `<vendor>:<list name>`, the crawler name in [IpTraits#crawlerName()]
    public String name() {
      return vendor + ":" + listName;
    }
  }

  /// The feeds, the index is the label in [#trie]
  private final List<Feed> feeds;

  private final TrieIpLabelLookup trie = new TrieIpLabelLookup();

  /// @param prefixesByFeed the CIDR prefixes of each feed, all with a length, see [#withLength]
  private KnownCrawlerLists(List<Feed> feeds, Map<Feed, List<String>> prefixesByFeed) {
    this.feeds = List.copyOf(feeds);
    for (int i = 0; i < this.feeds.size(); i++) {
      String label = Integer.toString(i);
      for (String cidr : prefixesByFeed.get(this.feeds.get(i))) {
        if (cidr.indexOf(':') < 0) {
          trie.insertIpv4(cidr, label);
        } else {
          trie.insertIpv6(cidr, label);
        }
      }
    }
  }

  /// Reads the configured directory, see [#read(Path)], and keeps the admitted feeds, see
  /// [#admitted(int, Collection)].
  ///
  /// @throws IOException if [#read(Path)] fails, or the directory holds no feed, so a configured
  ///   directory that is empty or wrong refuses the start instead of detecting no crawler
  public static CrawlerIpLists readAdmitted(KnownCrawlersConfig config) throws IOException {
    Path directory = Path.of(config.directory());
    KnownCrawlerLists lists = read(directory);
    if (lists.feeds.isEmpty()) {
      throw new IOException("No known crawler lists in " + directory + ", expected *" + ZIP_SUFFIX);
    }
    return lists.admitted(config.admittedTierLevel(), config.excludeTypes());
  }

  /// Reads every `*.zip` file in `directory`, in the order of the file names. Other files, e.g. a
  /// zip being written under a temporary name, are ignored, and an empty directory gives no
  /// lists. A feed contained in several zips with the same tier and tags is one feed.
  ///
  /// @throws IOException if the directory cannot be listed, or a zip is broken, has no
  ///   `meta.json`, an unknown `version`, a list without `info` or an invalid prefix. The message
  ///   names the zip, so the operator can fix it.
  public static KnownCrawlerLists read(Path directory) throws IOException {
    List<Path> zips;
    try (Stream<Path> files = Files.list(directory)) {
      zips =
          files
              .filter(Files::isRegularFile)
              .filter(path -> path.getFileName().toString().endsWith(ZIP_SUFFIX))
              .sorted()
              .toList();
    }
    ObjectMapper mapper = new ObjectMapper();
    Map<Feed, List<String>> prefixesByFeed = new LinkedHashMap<>();
    for (Path zip : zips) {
      try (ZipFile zipFile = new ZipFile(zip.toFile())) {
        readZip(mapper, zipFile, prefixesByFeed);
      } catch (IOException | RuntimeException e) {
        throw new IOException("Cannot read known crawler zip " + zip + ": " + e.getMessage(), e);
      }
    }
    return new KnownCrawlerLists(new ArrayList<>(prefixesByFeed.keySet()), prefixesByFeed);
  }

  /// Adds the feeds of one zip, a helper of [#read(Path)]
  private static void readZip(
      ObjectMapper mapper, ZipFile zipFile, Map<Feed, List<String>> prefixesByFeed)
      throws IOException {
    JsonNode meta = readJson(mapper, zipFile, META_ENTRY);
    if (meta == null) {
      throw new IOException("no " + META_ENTRY);
    }
    JsonNode version = meta.path("version");
    if (!version.isInt() || version.intValue() != SUPPORTED_VERSION) {
      throw new IOException(
          "unsupported " + META_ENTRY + " version " + version + ", expected " + SUPPORTED_VERSION);
    }
    JsonNode tierNode = meta.path("tier");
    if (!tierNode.isInt() || tierNode.intValue() < 1) {
      throw new IOException("invalid " + META_ENTRY + " tier " + tierNode);
    }
    int tier = tierNode.intValue();
    List<String> listEntries =
        zipFile.stream()
            .map(ZipEntry::getName)
            .filter(name -> name.startsWith(LISTS_DIRECTORY) && name.endsWith(JSON_SUFFIX))
            .toList();
    for (String listEntry : listEntries) {
      String infoEntry = INFO_DIRECTORY + listEntry.substring(LISTS_DIRECTORY.length());
      JsonNode info = readJson(mapper, zipFile, infoEntry);
      if (info == null) {
        throw new IOException("no " + infoEntry + " for " + listEntry);
      }
      Feed feed =
          new Feed(
              requiredText(info, "vendor"),
              requiredText(info, "listname"),
              tier,
              readTags(info, infoEntry));
      List<String> prefixes = prefixesByFeed.computeIfAbsent(feed, _ -> new ArrayList<>());
      for (JsonNode prefix : readJson(mapper, zipFile, listEntry).path("prefixes")) {
        if (prefix.has("ipv4Prefix")) {
          prefixes.add(withLength(prefix.get("ipv4Prefix").asText(), false));
        } else if (prefix.has("ipv6Prefix")) {
          prefixes.add(withLength(prefix.get("ipv6Prefix").asText(), true));
        }
      }
    }
  }

  /// Reads the optional feed types in registry order for [#readZip].
  private static List<String> readTags(JsonNode info, String infoEntry) throws IOException {
    JsonNode tags = info.get("tags");
    if (tags == null) {
      return List.of();
    }
    if (!tags.isArray()) {
      throw new IOException(infoEntry + ": tags must be an array of strings");
    }
    List<String> result = new ArrayList<>();
    for (JsonNode tag : tags) {
      if (!tag.isTextual()) {
        throw new IOException(infoEntry + ": tags contains a non-string element");
      }
      result.add(tag.textValue());
    }
    return result;
  }

  /// Parses a zip entry, a helper of [#readZip]
  ///
  /// @return `null` if the zip has no such entry
  private static JsonNode readJson(ObjectMapper mapper, ZipFile zipFile, String name)
      throws IOException {
    ZipEntry entry = zipFile.getEntry(name);
    if (entry == null) {
      return null;
    }
    try (InputStream in = zipFile.getInputStream(entry)) {
      return mapper.readTree(in);
    }
  }

  /// A mandatory text field of an `info` file, a helper of [#readZip]
  private static String requiredText(JsonNode info, String field) throws IOException {
    JsonNode value = info.path(field);
    if (!value.isTextual() || value.asText().isEmpty()) {
      throw new IOException("info without " + field + ": " + info);
    }
    return value.asText();
  }

  /// Validates a prefix and adds the host length to a single address, as the lists use both. A
  /// helper of [#readZip], so [IpTrie#insert] only sees prefixes it inserts: it would silently
  /// drop a length out of range, an IPv6 address in an IPv4 list, and a `/0`, which would match
  /// every address.
  ///
  /// @throws IllegalArgumentException if the prefix is invalid, [#read(Path)] reports it with the
  ///   zip
  static String withLength(String prefix, boolean ipv6) {
    int slash = prefix.indexOf('/');
    String literal = slash < 0 ? prefix : prefix.substring(0, slash);
    InetAddress address = InetAddress.ofLiteral(literal);
    if ((address instanceof Inet4Address) == ipv6) {
      throw new IllegalArgumentException(
          "prefix " + prefix + " is not an " + (ipv6 ? "IPv6" : "IPv4") + " address");
    }
    int bits = ipv6 ? IPV6_BITS : IPV4_BITS;
    if (slash < 0) {
      return prefix + "/" + bits;
    }
    int length = Integer.parseInt(prefix.substring(slash + 1));
    if (length < 1 || length > bits) {
      throw new IllegalArgumentException("prefix " + prefix + " has an invalid length");
    }
    return prefix;
  }

  /// The crawler name of an address is the one of its first admitted feed in [Feed#ORDER]: a
  /// feed up to `admittedTierLevel` without a tag of `excludeTypes`, see [KnownCrawlersConfig].
  /// The first feed that is not admitted is reported as [IpTraits#notAdmittedCrawlerName()].
  public CrawlerIpLists admitted(int admittedTierLevel, Collection<String> excludeTypes) {
    return new Admitted(this, admittedTierLevel, Set.copyOf(excludeTypes));
  }

  /// The lists with the admission rules of [KnownCrawlersConfig], see
  /// [#admitted(int, Collection)]
  private record Admitted(KnownCrawlerLists lists, int admittedTierLevel, Set<String> excludeTypes)
      implements CrawlerIpLists {

    boolean isAdmitted(Feed feed) {
      return feed.tier() <= admittedTierLevel && Collections.disjoint(feed.tags(), excludeTypes);
    }

    @Override
    public String crawlerName(InetAddress address) {
      for (Feed feed : lists.lookup(address)) {
        if (isAdmitted(feed)) {
          return feed.name();
        }
      }
      return null;
    }

    /// Looks the address up once and sets the first admitted and the first not admitted match
    @Override
    public void collect(IpTraits.Builder builder, InetAddress address) {
      String admitted = null;
      String notAdmitted = null;
      for (Feed feed : lists.lookup(address)) {
        if (isAdmitted(feed)) {
          if (admitted == null) {
            admitted = feed.name();
          }
        } else if (notAdmitted == null) {
          notAdmitted = feed.name();
        }
      }
      builder.crawlerName(admitted).notAdmittedCrawlerName(notAdmitted);
    }
  }

  /// Every feed `address` matches, each once, in [Feed#ORDER]. The best one comes first.
  ///
  /// @return an empty list if no feed matches
  public List<Feed> lookup(InetAddress address) {
    byte[] bytes = address.getAddress();
    List<String> labels =
        bytes.length == IPV4_BITS / Byte.SIZE ? trie.lookupIpv4(bytes) : trie.lookupIpv6(bytes);
    if (labels == null) {
      return List.of();
    }
    return labels.stream()
        .distinct()
        .map(label -> feeds.get(Integer.parseInt(label)))
        .sorted(Feed.ORDER)
        .toList();
  }
}
