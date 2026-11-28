package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/// Matching against the release fixture in `src/test/resources/sensepitch-known-crawlers/`, with
/// its `expectations.json` as the oracle, and the rejected zips, built in a temporary directory.
///
/// @author Jens Wilke
public class KnownCrawlerListsTest {

  static final String FIXTURE_DIRECTORY = "/sensepitch-known-crawlers";

  static final String VALID_META = "{\"version\": 1, \"tier\": 2}";

  static final String VALID_INFO =
      "{\"vendor\": \"example-v\", \"listname\": \"bot\", \"tier\": 1, \"tags\": [\"t1\", \"t2\"]}";

  static final ObjectMapper MAPPER = new ObjectMapper();

  /// Read once for the class, see the testing guidelines on fixtures
  static KnownCrawlerLists fixture;

  @TempDir Path dir;

  @BeforeAll
  static void readFixture() throws IOException {
    fixture = KnownCrawlerLists.read(fixtureDirectory());
  }

  static Path fixtureDirectory() {
    try {
      return Path.of(KnownCrawlerListsTest.class.getResource(FIXTURE_DIRECTORY).toURI());
    } catch (URISyntaxException e) {
      throw new IllegalStateException(e);
    }
  }

  /// One case per address of `expectations.json`: every feed it matches, in order
  static Stream<Arguments> expectations() throws IOException {
    JsonNode root = MAPPER.readTree(fixtureDirectory().resolve("expectations.json").toFile());
    List<Arguments> cases = new ArrayList<>();
    for (JsonNode expectation : root) {
      List<String> matches = new ArrayList<>();
      for (JsonNode match : expectation.path("matches")) {
        matches.add(
            match.path("tier").asInt()
                + " "
                + match.path("vendor").asText()
                + "/"
                + match.path("listname").asText());
      }
      cases.add(Arguments.of(expectation.path("ip").asText(), matches));
    }
    return cases.stream();
  }

  @ParameterizedTest
  @MethodSource("expectations")
  void matchesTheExpectations(String ip, List<String> expectedMatches) {
    assertThat(names(fixture.lookup(InetAddress.ofLiteral(ip))))
        .containsExactlyElementsOf(expectedMatches);
  }

  /// The oracle names the feeds only, the tags come from the `info` files
  @Test
  void feedsCarryTheTagsOfTheirInfo() {
    assertThat(fixture.lookup(InetAddress.ofLiteral("192.0.2.64")))
        .containsExactly(
            new KnownCrawlerLists.Feed(
                "example-ai",
                "user-fetcher",
                1,
                List.of("user-triggered-fetcher", "ai-search-crawler")),
            new KnownCrawlerLists.Feed(
                "example-search",
                "crawler",
                1,
                List.of("search-engine-crawler", "mixed-pool", "seo-tool")));
  }

  /// The tier of `meta.json` counts, not the one of the `info` file; a single address is a host
  /// route; a list can hold overlapping prefixes and entries of other kinds, an address in two of
  /// them still matches the feed once.
  @Test
  void tierFromMetaHostRoutesAndOverlapsWithinAFeed() throws IOException {
    writeZip(
        "a.zip",
        Map.of(
            "meta.json",
            VALID_META,
            "info/example-v/bot.json",
            VALID_INFO,
            "lists/example-v/bot.json",
            prefixes(
                "{\"ipv4Prefix\": \"192.0.2.7\"}",
                "{\"ipv6Prefix\": \"2001:db8::7\"}",
                "{\"ipv4Prefix\": \"198.51.100.0/24\"}",
                "{\"ipv4Prefix\": \"198.51.100.0/25\"}",
                "{\"service\": \"other\"}"),
            "lists/README.md",
            "not a list",
            "other.json",
            "{}"));
    var lists = KnownCrawlerLists.read(dir);
    var feed = new KnownCrawlerLists.Feed("example-v", "bot", 2, List.of("t1", "t2"));
    assertThat(lists.lookup(InetAddress.ofLiteral("192.0.2.7"))).containsExactly(feed);
    assertThat(lists.lookup(InetAddress.ofLiteral("192.0.2.8"))).isEmpty();
    assertThat(lists.lookup(InetAddress.ofLiteral("2001:db8::7"))).containsExactly(feed);
    assertThat(lists.lookup(InetAddress.ofLiteral("2001:db8::8"))).isEmpty();
    assertThat(lists.lookup(InetAddress.ofLiteral("198.51.100.1"))).containsExactly(feed);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"vendor\":\"v\",\"listname\":\"bot\"}",
        "{\"vendor\":\"v\",\"listname\":\"bot\",\"tags\":[]}"
      })
  void missingOrEmptyTagsGiveAnUntaggedFeed(String info) throws IOException {
    var entries = validZip(prefixes("{\"ipv4Prefix\": \"192.0.2.7\"}"));
    entries.put("info/example-v/bot.json", info);
    writeZip("tier.zip", entries);
    assertThat(KnownCrawlerLists.read(dir).lookup(InetAddress.ofLiteral("192.0.2.7")))
        .containsExactly(new KnownCrawlerLists.Feed("v", "bot", 2, List.of()));
  }

  /// The same feed in two zips is one feed with the prefixes of both, so it matches once
  @Test
  void sameFeedInTwoZipsIsOneFeed() throws IOException {
    writeZip("a.zip", validZip(prefixes("{\"ipv4Prefix\": \"192.0.2.0/24\"}")));
    writeZip("b.zip", validZip(prefixes("{\"ipv4Prefix\": \"192.0.2.0/25\"}")));
    assertThat(names(KnownCrawlerLists.read(dir).lookup(InetAddress.ofLiteral("192.0.2.1"))))
        .containsExactly("2 example-v/bot");
  }

  /// Only regular files named `*.zip` are read: a zip still being written under another name and
  /// a directory are ignored. An empty directory has no lists.
  @Test
  void emptyDirectoryAndOtherFilesMatchNothing() throws IOException {
    assertThat(KnownCrawlerLists.read(dir).lookup(InetAddress.ofLiteral("192.0.2.1"))).isEmpty();
    Files.writeString(dir.resolve("next.zip.tmp"), "partial");
    Files.createDirectory(dir.resolve("old.zip"));
    assertThat(KnownCrawlerLists.read(dir).lookup(InetAddress.ofLiteral("192.0.2.1"))).isEmpty();
  }

  @Test
  void missingDirectoryIsRejected() {
    assertThatThrownBy(() -> KnownCrawlerLists.read(dir.resolve("missing")))
        .isInstanceOf(IOException.class);
  }

  @Test
  void corruptZipIsRejected() throws IOException {
    Path zip = dir.resolve("tier1.zip");
    Files.writeString(zip, "this is not a zip file");
    assertThatThrownBy(() -> KnownCrawlerLists.read(dir))
        .isInstanceOf(IOException.class)
        .hasMessageStartingWith("Cannot read known crawler zip " + zip + ": ");
  }

  /// Tier level, excluded tags, address, the expected crawler name and the expected best match
  /// that is not admitted, `null` for none
  static Stream<Arguments> admittedNames() {
    List<String> none = List.of();
    String search = "example-search:crawler";
    String ai = "example-ai:user-fetcher";
    String seo = "example-seo:audit";
    String train = "example-train:bot";
    return Stream.of(
        Arguments.of(1, none, "192.0.2.1", search, null),
        // two feeds of tier 1, the vendor decides, an excluded tag skips to the next one
        Arguments.of(1, none, "192.0.2.64", ai, null),
        Arguments.of(1, List.of("ai-search-crawler"), "192.0.2.64", search, ai),
        Arguments.of(1, List.of("user-triggered-fetcher", "seo-tool"), "192.0.2.64", null, ai),
        Arguments.of(1, List.of("ai-search-crawler"), "2001:db8:1:5::1", search, ai),
        Arguments.of(1, none, "192.0.2.128", null, null),
        // a higher tier is admitted only up to the level, the lower tier wins
        Arguments.of(1, none, "198.51.100.1", null, seo),
        Arguments.of(2, none, "198.51.100.1", seo, null),
        Arguments.of(1, none, "198.51.100.128", null, seo),
        Arguments.of(3, none, "198.51.100.128", seo, null),
        Arguments.of(3, List.of("seo-tool"), "198.51.100.128", "example-hooks:webhooks", seo),
        Arguments.of(2, none, "2001:db8:4::abcd", null, train),
        Arguments.of(3, none, "2001:db8:4::abcd", train, null),
        Arguments.of(3, List.of("ai-training-crawler"), "2001:db8:4::abcd", null, train));
  }

  /// The crawler name is the one of the first admitted feed in [KnownCrawlerLists.Feed#ORDER],
  /// the first feed that is not admitted is reported beside it
  @ParameterizedTest
  @MethodSource("admittedNames")
  void admittedFeedNamesTheCrawler(
      int admittedTierLevel,
      List<String> excludeTypes,
      String ip,
      String expectedName,
      String expectedNotAdmitted) {
    CrawlerIpLists lists = fixture.admitted(admittedTierLevel, excludeTypes);
    InetAddress address = InetAddress.ofLiteral(ip);
    assertThat(lists.crawlerName(address)).isEqualTo(expectedName);
    IpTraits.Builder builder = IpTraits.builder();
    lists.collect(builder, address);
    IpTraits traits = builder.build();
    assertThat(traits.crawlerName()).isEqualTo(expectedName);
    assertThat(traits.notAdmittedCrawlerName()).isEqualTo(expectedNotAdmitted);
  }

  /// The configured directory, with the defaults: tier 1, no tag excluded
  @Test
  void readAdmittedFromTheConfiguredDirectory() throws IOException {
    CrawlerIpLists lists =
        KnownCrawlerLists.readAdmitted(
            KnownCrawlersConfig.builder().directory(fixtureDirectory().toString()).build());
    assertThat(lists.crawlerName(InetAddress.ofLiteral("192.0.2.1")))
        .isEqualTo("example-search:crawler");
    assertThat(lists.crawlerName(InetAddress.ofLiteral("198.51.100.1"))).isNull();
  }

  /// A configured directory without lists is an error, not a configuration that detects no
  /// crawler
  @Test
  void readAdmittedRejectsADirectoryWithoutLists() throws IOException {
    Files.writeString(dir.resolve("next.zip.tmp"), "partial");
    assertThatThrownBy(
            () ->
                KnownCrawlerLists.readAdmitted(
                    KnownCrawlersConfig.builder().directory(dir.toString()).build()))
        .isInstanceOf(IOException.class)
        .hasMessage("No known crawler lists in " + dir + ", expected *.zip");
  }

  /// Each zip is valid except for one entry, `null` removes it
  static Stream<Arguments> rejectedZips() {
    return Stream.of(
        Arguments.of("meta.json", null, "no meta.json"),
        Arguments.of(
            "meta.json", "{\"version\": 2, \"tier\": 1}", "unsupported meta.json version 2"),
        Arguments.of(
            "meta.json",
            "{\"version\": \"1\", \"tier\": 1}",
            "unsupported meta.json version \"1\""),
        Arguments.of("meta.json", "{\"tier\": 1}", "unsupported meta.json version"),
        Arguments.of("meta.json", "{\"version\": 1, \"tier\": 0}", "invalid meta.json tier 0"),
        Arguments.of(
            "meta.json", "{\"version\": 1, \"tier\": \"1\"}", "invalid meta.json tier \"1\""),
        Arguments.of("meta.json", "{\"version\": 1, ", "Unexpected end-of-input"),
        Arguments.of(
            "info/example-v/bot.json",
            null,
            "no info/example-v/bot.json for lists/example-v/bot.json"),
        Arguments.of(
            "info/example-v/bot.json",
            "{\"vendor\":\"v\",\"listname\":\"bot\",\"tags\":[\"bot\", 4]}",
            "info/example-v/bot.json: tags contains a non-string element"),
        Arguments.of(
            "info/example-v/bot.json",
            "{\"vendor\":\"v\",\"listname\":\"bot\",\"tags\":null}",
            "info/example-v/bot.json: tags must be an array of strings"),
        Arguments.of(
            "info/example-v/bot.json",
            "{\"vendor\":\"v\",\"listname\":\"bot\",\"tags\":\"bot\"}",
            "info/example-v/bot.json: tags must be an array of strings"),
        Arguments.of("info/example-v/bot.json", "{\"listname\": \"bot\"}", "info without vendor"),
        Arguments.of(
            "info/example-v/bot.json",
            "{\"vendor\": 1, \"listname\": \"bot\"}",
            "info without vendor"),
        Arguments.of(
            "info/example-v/bot.json",
            "{\"vendor\": \"\", \"listname\": \"bot\"}",
            "info without vendor"),
        Arguments.of("info/example-v/bot.json", "{\"vendor\": \"v\"}", "info without listname"),
        Arguments.of(
            "lists/example-v/bot.json",
            prefixes("{\"ipv4Prefix\": \"2001:db8::/32\"}"),
            "prefix 2001:db8::/32 is not an IPv4 address"),
        Arguments.of(
            "lists/example-v/bot.json",
            prefixes("{\"ipv6Prefix\": \"192.0.2.0/24\"}"),
            "prefix 192.0.2.0/24 is not an IPv6 address"),
        Arguments.of(
            "lists/example-v/bot.json",
            prefixes("{\"ipv4Prefix\": \"0.0.0.0/0\"}"),
            "prefix 0.0.0.0/0 has an invalid length"),
        Arguments.of(
            "lists/example-v/bot.json",
            prefixes("{\"ipv4Prefix\": \"192.0.2.0/33\"}"),
            "prefix 192.0.2.0/33 has an invalid length"),
        Arguments.of(
            "lists/example-v/bot.json",
            prefixes("{\"ipv6Prefix\": \"2001:db8::/129\"}"),
            "prefix 2001:db8::/129 has an invalid length"),
        Arguments.of(
            "lists/example-v/bot.json",
            prefixes("{\"ipv4Prefix\": \"192.0.2.0/x\"}"),
            "For input string: \"x\""),
        Arguments.of(
            "lists/example-v/bot.json",
            prefixes("{\"ipv4Prefix\": \"crawler.example\"}"),
            "crawler.example"));
  }

  /// The message names the zip and the problem, so the operator can fix it. A reload that fails
  /// this way keeps the lists it has.
  @ParameterizedTest
  @MethodSource("rejectedZips")
  void invalidZipIsRejected(String entry, String content, String expectedProblem)
      throws IOException {
    Map<String, String> entries = validZip(prefixes("{\"ipv4Prefix\": \"192.0.2.0/24\"}"));
    if (content == null) {
      entries.remove(entry);
    } else {
      entries.put(entry, content);
    }
    Path zip = writeZip("tier.zip", entries);
    assertThatThrownBy(() -> KnownCrawlerLists.read(dir))
        .isInstanceOf(IOException.class)
        .hasMessageStartingWith("Cannot read known crawler zip " + zip + ": ")
        .hasMessageContaining(expectedProblem);
  }

  /// A zip of tier 2 with the feed `example-v/bot` and the given list
  static Map<String, String> validZip(String list) {
    Map<String, String> entries = new LinkedHashMap<>();
    entries.put("meta.json", VALID_META);
    entries.put("lists/example-v/bot.json", list);
    entries.put("info/example-v/bot.json", VALID_INFO);
    return entries;
  }

  /// A list in the release format with the given prefix entries
  static String prefixes(String... entries) {
    return "{\"prefixes\": [" + String.join(", ", entries) + "]}";
  }

  Path writeZip(String name, Map<String, String> entries) throws IOException {
    Path zip = dir.resolve(name);
    try (OutputStream out = Files.newOutputStream(zip);
        ZipOutputStream zipOut = new ZipOutputStream(out)) {
      for (var entry : entries.entrySet()) {
        zipOut.putNextEntry(new ZipEntry(entry.getKey()));
        zipOut.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
        zipOut.closeEntry();
      }
    }
    return zip;
  }

  /// `<tier> <vendor>/<listname>`, as the oracle names the feeds
  static List<String> names(List<KnownCrawlerLists.Feed> feeds) {
    return feeds.stream().map(f -> f.tier() + " " + f.vendor() + "/" + f.listName()).toList();
  }
}
