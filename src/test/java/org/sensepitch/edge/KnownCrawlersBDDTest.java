package org.sensepitch.edge;

import static io.netty.handler.codec.http.HttpResponseStatus.FORBIDDEN;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import net.serenitybdd.junit5.SerenityJUnit5Extension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/// The admission rules of the crawler IP lists of the Sensepitch Known Crawlers release through
/// the whole ingress pipeline, with the fixture release in
/// `src/test/resources/sensepitch-known-crawlers` (addresses from its `expectations.json`): an
/// admitted match bypasses the protection, a match that is not admitted is reported in the IP
/// traits but gets the challenge, and an IP match wins over a User-Agent match. The matching of
/// the lists is covered by [KnownCrawlerListsTest], the bypass order by [DetectCrawlerTest].
@ExtendWith(SerenityJUnit5Extension.class)
class KnownCrawlersBDDTest {

  static final String SEARCH = "crawler=example-search:crawler";
  static final String SEO = "example-seo:audit";
  static final String AI = "example-ai:user-fetcher";
  static final String NOT_ADMITTED = "crawler-not-admitted=";
  static final String IP_MATCH = BypassCheck.REASON_CRAWLER_IP_MATCH;

  static final Map<String, String> BROWSER =
      Map.of(HttpHeaderNames.USER_AGENT.toString(), "Mozilla/5.0");

  /// A User-Agent of the built-in `crawlers.tsv`
  static final Map<String, String> LISTED_AGENT =
      Map.of(HttpHeaderNames.USER_AGENT.toString(), "Twitterbot/1.0");

  static final Map<String, String> NO_AGENT = Map.of();

  CompleteTest.Steps steps;

  /// Tier level, excluded tags, client address, request headers, expected status, IP traits and
  /// bypass reason, `null` for none
  static Stream<Arguments> scenarios() {
    List<String> none = List.of();
    return Stream.of(
        // an admitted tier 1 address
        Arguments.of(1, none, "192.0.2.1", BROWSER, OK, SEARCH, IP_MATCH),
        // tier 2 is above the default level: reported, not admitted
        Arguments.of(1, none, "198.51.100.1", BROWSER, FORBIDDEN, NOT_ADMITTED + SEO, null),
        // raising the level admits it
        Arguments.of(2, none, "198.51.100.1", BROWSER, OK, "crawler=" + SEO, IP_MATCH),
        // an excluded tag: reported, not admitted
        Arguments.of(
            1,
            List.of("user-triggered-fetcher"),
            "192.0.2.200",
            BROWSER,
            FORBIDDEN,
            NOT_ADMITTED + AI,
            null),
        // the excluded feed of an overlap is reported beside the admitted one
        Arguments.of(
            1,
            List.of("ai-search-crawler"),
            "192.0.2.64",
            BROWSER,
            OK,
            SEARCH + ", " + NOT_ADMITTED + AI,
            IP_MATCH),
        // address and User-Agent both match: the address wins
        Arguments.of(1, none, "192.0.2.1", LISTED_AGENT, OK, SEARCH, IP_MATCH),
        // a listed address without a User-Agent is suspicious
        Arguments.of(1, none, "192.0.2.1", NO_AGENT, FORBIDDEN, SEARCH, null),
        // a not admitted address keeps the User-Agent match
        Arguments.of(
            1,
            none,
            "198.51.100.1",
            LISTED_AGENT,
            OK,
            NOT_ADMITTED + SEO,
            BypassCheck.REASON_CRAWLER_AGENT_MATCH),
        // no match at all, the traits are empty
        Arguments.of(1, none, "8.8.8.8", BROWSER, FORBIDDEN, "", null));
  }

  @ParameterizedTest
  @MethodSource("scenarios")
  void admissionByTheKnownCrawlerLists(
      int admittedTierLevel,
      List<String> excludeTypes,
      String clientIp,
      Map<String, String> headers,
      HttpResponseStatus expectedStatus,
      String expectedTraits,
      String expectedBypass) {
    steps =
        new CompleteTest.Steps()
            .given_initialized_proxy_with(configWithKnownCrawlers(admittedTierLevel, excludeTypes))
            .given_the_client_address(clientIp)
            .when_requesting_with_headers("withprotection.com", "/", headers)
            .then_the_response_status_is(expectedStatus)
            .then_the_request_log_has_traits_and_bypass(expectedTraits, expectedBypass);
  }

  @AfterEach
  void release() {
    if (steps != null) {
      steps.finish_and_check_for_leaks();
    }
  }

  /// [CompleteTest#COMMON_CONFIG] with the fixture release and crawler detection
  static ProxyConfig configWithKnownCrawlers(int admittedTierLevel, List<String> excludeTypes) {
    ProxyConfig common = CompleteTest.COMMON_CONFIG;
    return common.toBuilder()
        .ipLookup(
            IpLookupConfig.builder()
                .knownCrawlers(
                    KnownCrawlersConfig.builder()
                        .directory(KnownCrawlerListsTest.fixtureDirectory().toString())
                        .admittedTierLevel(admittedTierLevel)
                        .excludeTypes(excludeTypes)
                        .build())
                .build())
        .protection(
            common.protection().toBuilder()
                .deflector(
                    common.protection().deflector().toBuilder()
                        .detectCrawler(DetectCrawlerConfig.builder().build())
                        .build())
                .build())
        .build();
  }
}
