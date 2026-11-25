package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.channel.ChannelHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.CharsetUtil;
import java.util.ArrayList;
import java.util.List;
import net.serenitybdd.annotations.Step;
import net.serenitybdd.junit5.SerenityJUnit5Extension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/// The challenge page names the host the visitor requested, and nothing of the host can add markup.
@ExtendWith(SerenityJUnit5Extension.class)
class ChallengePageHostBDDTest {

  private static final String CONNECTING_TO = "Connecting to:";

  /// Placeholder text sent in a host, it must arrive verbatim, escaped after an `&`.
  private static final String ESCAPED_HOST_PLACEHOLDER = "&amp;{{CHALLENGE}}";

  private final Steps steps = new Steps();

  @AfterEach
  void releaseMessages() {
    steps.channel.finishAndReleaseAll();
  }

  @Test
  void eachSiteNamesItsOwnHost() {
    steps
        .given_sanitized_hosts("shop.example", "*.blog.example")
        .when_challenge_is_requested("shop.example")
        .then_page_names_host("shop.example")
        .when_challenge_is_requested("News.Blog.Example.:443")
        .then_page_names_host("news.blog.example")
        .then_page_does_not_contain("shop.example");
  }

  @ParameterizedTest
  @ValueSource(strings = {"other.example", "bad host", ""})
  void unservicedHostIsNotNamed(String host) {
    steps
        .given_sanitized_hosts("shop.example")
        .when_challenge_is_requested(host)
        .then_page_names_no_host();
  }

  @Test
  void missingHostIsNotNamed() {
    steps
        .given_sanitized_hosts("shop.example")
        .when_challenge_is_requested(null)
        .then_page_names_no_host();
  }

  @Test
  void hostIsEscapedWithoutSanitizing() {
    steps
        .given_no_host_sanitizing()
        .when_challenge_is_requested("a.example\"><script>alert('x')</script>&{{CHALLENGE}}")
        .then_page_names_host(
            "a.example&quot;&gt;&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;"
                + ESCAPED_HOST_PLACEHOLDER)
        .then_page_does_not_contain("<script>alert");
  }

  @ParameterizedTest
  @ValueSource(strings = {SanitizeHostHandler.UNKNOWN_HOST, SanitizeHostHandler.NIL_HOST, ""})
  void specialHostIsNotNamedWithoutSanitizing(String host) {
    steps.given_no_host_sanitizing().when_challenge_is_requested(host).then_page_names_no_host();
  }

  public static class Steps {

    private EmbeddedChannel channel;
    private String page;

    @Step
    public Steps given_sanitized_hosts(String... hosts) {
      return given_protection(new SanitizeHostHandler(List.of(hosts)));
    }

    @Step
    public Steps given_no_host_sanitizing() {
      return given_protection();
    }

    private Steps given_protection(ChannelHandler... before) {
      DeflectorConfig config =
          DeflectorConfig.builder()
              .serverIpv4Address("127.0.0.1")
              .tokenGenerators(
                  List.of(
                      AdmissionTokenGeneratorConfig.builder()
                          .prefix("X")
                          .secret(SecretConfig.builder().value("host-test-secret").build())
                          .build()))
              .build();
      List<ChannelHandler> handlers = new ArrayList<>(List.of(before));
      handlers.add(
          new ProtectionHandler(
              new DeflectorHandler(new Deflector(config, new ConfigFiles()), new ProxyMetrics())));
      channel = new EmbeddedChannel(handlers.toArray(ChannelHandler[]::new));
      return this;
    }

    /// @param host value of the `Host` header, `null` to send none
    @Step
    public Steps when_challenge_is_requested(String host) {
      HttpRequest request =
          new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/protected");
      if (host != null) {
        request.headers().set(HttpHeaderNames.HOST, host);
      }
      channel.writeInbound(request);
      FullHttpResponse response = channel.readOutbound();
      try {
        assertThat(response.status()).isEqualTo(HttpResponseStatus.FORBIDDEN);
        page = response.content().toString(CharsetUtil.UTF_8);
      } finally {
        response.release();
      }
      return this;
    }

    @Step
    public Steps then_page_names_host(String html) {
      assertThat(page)
          .contains("<div class=\"center\">" + CONNECTING_TO + "<br/>" + html + "<br/><br/>");
      return then_page_is_complete();
    }

    @Step
    public Steps then_page_names_no_host() {
      assertThat(page).doesNotContain(CONNECTING_TO).contains("<div class=\"center\">\n");
      return then_page_is_complete();
    }

    @Step
    public Steps then_page_does_not_contain(String text) {
      assertThat(page).doesNotContain(text);
      return this;
    }

    /// No placeholder is left, apart from one a client sent in the host, no fixed host, and the
    /// script constants name the right endpoints and cookie.
    private Steps then_page_is_complete() {
      assertThat(page)
          .doesNotContain("packingpanic")
          .contains("<div id=\"status\">")
          .contains("var STEP = '" + Deflector.CHALLENGE_STEP_URL + "';")
          .contains("var ENDPOINT = '" + Deflector.CHALLENGE_ANSWER_URL + "';")
          .contains("var TOKEN_COOKIE = '" + Deflector.TOKEN_COOKIE_NAME + "';");
      assertThat(page.replace(ESCAPED_HOST_PLACEHOLDER, ""))
          .doesNotContain("{{")
          .doesNotContain("}}");
      return this;
    }
  }
}
