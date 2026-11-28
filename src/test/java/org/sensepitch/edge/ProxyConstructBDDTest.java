package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.channel.MultithreadEventLoopGroup;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import net.serenitybdd.annotations.Step;
import net.serenitybdd.junit5.SerenityJUnit5Extension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/// @author Jens Wilke
@ExtendWith(SerenityJUnit5Extension.class)
class ProxyConstructBDDTest {

  Steps steps = new Steps();

  static final ListenConfig SSL_LISTEN =
      ListenConfig.builder()
          .ssl(
              SslConfig.builder()
                  .keyPath("classpath:ssl/test.key")
                  .certPath("classpath:ssl/test.crt")
                  .build())
          .build();

  static final MetricsConfig NO_METRICS = MetricsConfig.builder().disabled(true).build();

  static final UpstreamConfig LOCAL = UpstreamConfig.builder().target("localhost:8080").build();

  static UpstreamConfig ref(String name) {
    return UpstreamConfig.builder().ref(name).build();
  }

  static PathRouteConfig path(UpstreamConfig upstream) {
    return PathRouteConfig.builder().upstream(upstream).build();
  }

  /// Configuration with named upstreams `api` and `beta` and a single site.
  static ProxyConfig routingConfig(SiteConfig site) {
    return ProxyConfig.builder()
        .listen(SSL_LISTEN)
        .upstreams(Map.of("api", LOCAL, "beta", LOCAL))
        .sites(
            Map.of(
                "example.com",
                site.toBuilder()
                    .protection(ProtectionConfig.builder().disable(true).build())
                    .build()))
        .metrics(NO_METRICS)
        .build();
  }

  /// Single site with SSL, but without the routing setup of [#routingConfig(SiteConfig)]
  static ProxyConfig siteConfig(SiteConfig site) {
    return ProxyConfig.builder()
        .listen(SSL_LISTEN)
        .sites(Map.of("example.com", site))
        .metrics(NO_METRICS)
        .build();
  }

  @Test
  void testWithOneSiteWithResponseDisabledProtection() {
    ProxyConfig config =
        siteConfig(
            SiteConfig.builder()
                .response(ResponseConfig.builder().text("demo").build())
                .protection(ProtectionConfig.builder().disable(true).build())
                .build());
    steps.given_the_configuration(config).then_expect_initialized_without_exception();
  }

  @Test
  void routingWithPathsOverridesAndNamedUpstreams() {
    SiteConfig site =
        SiteConfig.builder()
            .upstream(ref("api"))
            .paths(Map.of("/api/*", path(ref("api")), "/api/v2/*", path(LOCAL)))
            .overrides(
                List.of(
                    RoutingOverrideConfig.builder()
                        .whenCookie("beta")
                        .upstream(ref("beta"))
                        .paths(Map.of("/api/*", path(ref("beta"))))
                        .build()))
            .build();
    steps.given_the_configuration(routingConfig(site)).then_expect_initialized_without_exception();
  }

  /// A site without own upstream uses the global `upstream`.
  @Test
  void siteUsesGlobalUpstream() {
    ProxyConfig config =
        routingConfig(SiteConfig.builder().build()).toBuilder().upstream(LOCAL).build();
    steps.given_the_configuration(config).then_expect_initialized_without_exception();
  }

  /// A wrong database path fails at startup with the path in the message, not at the first request.
  @Test
  void missingIpLookupDatabaseFailsStartup() {
    ProxyConfig config =
        routingConfig(SiteConfig.builder().upstream(LOCAL).build()).toBuilder()
            .ipLookup(
                IpLookupConfig.builder()
                    .geoIp2(
                        GeoIp2Config.builder()
                            .countryDbPath("/does/not/exist/country.mmdb")
                            .build())
                    .build())
            .build();
    steps
        .given_the_configuration(config)
        .then_expect_construction_failure_containing("/does/not/exist/country.mmdb");
  }

  /// A configured directory of known crawler lists that cannot be used refuses the start, the
  /// message names the directory or the zip. The other problems of a zip are tested in
  /// [KnownCrawlerListsTest].
  @ParameterizedTest
  @ValueSource(strings = {"missing", "empty", "broken zip"})
  void unusableKnownCrawlerListsFailStartup(String problem, @TempDir Path dir) throws IOException {
    Path directory = dir.resolve("known-crawlers");
    String expectedMessage =
        switch (problem) {
          case "missing" -> directory.toString();
          case "empty" -> {
            Files.createDirectory(directory);
            yield "No known crawler lists in " + directory;
          }
          default -> {
            Files.createDirectory(directory);
            Path zip = Files.writeString(directory.resolve("tier1.zip"), "not a zip");
            yield "Cannot read known crawler zip " + zip;
          }
        };
    ProxyConfig config =
        routingConfig(SiteConfig.builder().upstream(LOCAL).build()).toBuilder()
            .ipLookup(
                IpLookupConfig.builder()
                    .knownCrawlers(
                        KnownCrawlersConfig.builder().directory(directory.toString()).build())
                    .build())
            .build();
    steps
        .given_the_configuration(config)
        .then_expect_construction_failure_containing(expectedMessage);
  }

  /// The proxy has as many event loops as its environment says, the one the configuration
  /// defaults are derived from, e.g. [OtlpConfig#maxConcurrentRequests()]
  @Test
  void eventLoopsFollowTheEnvironment() {
    steps
        .given_the_configuration(routingConfig(SiteConfig.builder().upstream(LOCAL).build()))
        .then_expect_initialized_without_exception()
        .then_expect_the_event_loops_of_the_environment();
  }

  @Test
  void exactPathAccepted() {
    SiteConfig site =
        SiteConfig.builder()
            .upstream(LOCAL)
            .paths(Map.of("/api", path(LOCAL), "/api/*", path(LOCAL)))
            .build();
    steps.given_the_configuration(routingConfig(site)).then_expect_initialized_without_exception();
  }

  static class Steps extends ExtendableSteps<Steps> {}

  /// Steps of a proxy built from a configuration, with its HTTP handlers on an `EmbeddedChannel`,
  /// see `Proxy.addHttpHandlers`. The base of the step classes of other proxy level BDD tests,
  /// which extend it with their own steps; `T` is the extending class, so a chain keeps its type.
  @SuppressWarnings({"unchecked", "UnusedReturnValue"})
  static class ExtendableSteps<T extends ExtendableSteps<?>> {

    Proxy proxy;
    Throwable constructionFailure;
    final RequestLogCapture requestLog = new RequestLogCapture();

    @Step("Given a common configuration:")
    T given_the_configuration(ProxyConfig proxyConfig) {
      try {
        proxy = new Proxy(proxyConfig, requestLog);
      } catch (Throwable throwable) {
        constructionFailure = throwable;
      }
      return (T) this;
    }

    @Step()
    T then_expect_exception_with_message(String expectedMessage) {
      if (constructionFailure == null) {
        throw new AssertionError("Exception expected: " + IllegalArgumentException.class);
      }
      String message = constructionFailure.getMessage();
      if (constructionFailure instanceof IllegalArgumentException
          && message != null
          && message.contains(expectedMessage)) {
        return (T) this;
      }
      // chain
      throw new AssertionError("Unexpected exception: " + constructionFailure, constructionFailure);
    }

    /// Construction fails with any exception, e.g. an I/O error on a configured file
    @Step
    T then_expect_construction_failure_containing(String expectedMessage) {
      assertThat(constructionFailure).isNotNull().hasMessageContaining(expectedMessage);
      return (T) this;
    }

    @Step
    T then_expect_initialized_without_exception() {
      assertThat(constructionFailure).isNull();
      return (T) this;
    }

    @Step("The proxy has the event loops of the current environment")
    T then_expect_the_event_loops_of_the_environment() {
      try {
        assertThat(proxy.environment()).isEqualTo(ProxyEnvironment.current());
        assertThat(((MultithreadEventLoopGroup) proxy.eventLoopGroup()).executorCount())
            .isEqualTo(proxy.environment().eventLoopThreads());
      } finally {
        proxy.shutdown();
      }
      return (T) this;
    }
  }
}
