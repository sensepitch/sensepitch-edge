package org.sensepitch.edge;

import static io.netty.handler.codec.http.HttpMethod.GET;
import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;
import static io.netty.handler.codec.http.HttpResponseStatus.PERMANENT_REDIRECT;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static org.assertj.core.api.Assertions.assertThat;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderResult;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.util.List;
import java.util.Set;
import net.serenitybdd.annotations.Step;
import net.serenitybdd.junit5.SerenityJUnit5Extension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.sensepitch.edge.ProxyMetrics.PlainHttpOutcome;

/// Behaviour of the [HttpToHttpsRedirectHandler]: a plain HTTP request for a served host is
/// answered with a permanent redirect (308) to the same resource on HTTPS, using the normalized
/// `Host` header and [ListenConfig#httpsPort()]; anything else with 400. Every answer is counted by
/// outcome. [PlainHttpListenerIT] is the real-socket cross check, including the
/// `Connection: close` behaviour.
///
/// @author Jens Wilke
@ExtendWith(SerenityJUnit5Extension.class)
class HttpToHttpsRedirectHandlerBDDTest {

  final RedirectSteps steps = new RedirectSteps();

  @Test
  void customHttpsPort_appearsInLocationAndHostPortIsRemoved() {
    steps
        .given_a_redirect_handler_to_https_port(17443)
        .when_request_to("example.com:8080", "/path?q=1")
        .then_response_status_is(PERMANENT_REDIRECT)
        .then_location_header_is("https://example.com:17443/path?q=1");
  }

  @Test
  void defaultHttpsPort_isOmitted() {
    steps
        .given_a_redirect_handler_to_https_port(443)
        .when_request_to("example.com", "/")
        .then_response_status_is(PERMANENT_REDIRECT)
        .then_location_header_is("https://example.com/");
  }

  @Test
  void hostTrailingDot_isNormalized() {
    steps
        .given_a_redirect_handler_to_https_port(17443)
        .when_request_to("example.com.", "/")
        .then_response_status_is(PERMANENT_REDIRECT)
        .then_location_header_is("https://example.com:17443/");
  }

  /// Netty accepts a bad percent escape, [java.net.URI] rejects it, so the redirect falls back to
  /// the root path.
  @Test
  void badPercentEscape_fallsBackToRoot() {
    steps
        .given_a_redirect_handler_to_https_port(17443)
        .when_request_to("example.com", "/%zz")
        .then_response_status_is(PERMANENT_REDIRECT)
        .then_location_header_is("https://example.com:17443/");
  }

  @Test
  void missingHost_isBadRequest() {
    steps
        .given_a_redirect_handler_to_https_port(17443)
        .when_request_without_host_to_uri("/")
        .then_response_status_is(BAD_REQUEST)
        .then_location_header_is_absent()
        .then_counted_as(PlainHttpOutcome.BAD_REQUEST);
  }

  /// The edge redirects only hosts it serves, like the TLS side, which answers an unknown host
  /// with 400 as well
  @Test
  void unknownHost_isBadRequest() {
    steps
        .given_a_redirect_handler_to_https_port(443)
        .when_request_to("other.example", "/")
        .then_response_status_is(BAD_REQUEST)
        .then_location_header_is_absent()
        .then_counted_as(PlainHttpOutcome.UNKNOWN_HOST);
  }

  @Test
  void redirectIsCounted() {
    steps
        .given_a_redirect_handler_to_https_port(443)
        .when_request_to("example.com", "/")
        .then_response_status_is(PERMANENT_REDIRECT)
        .then_counted_as(PlainHttpOutcome.REDIRECT);
  }

  /// An undecodable request is not redirected, even for a served host
  @Test
  void decoderFailure_isBadRequest() {
    steps
        .given_a_redirect_handler_to_https_port(443)
        .when_undecodable_request_to("example.com")
        .then_response_status_is(BAD_REQUEST)
        .then_counted_as(PlainHttpOutcome.BAD_REQUEST);
  }

  @Test
  void severalHosts_isBadRequest() {
    steps
        .given_a_redirect_handler_to_https_port(17443)
        .when_request_with_hosts_to_uri(List.of("a.com", "b.com"), "/")
        .then_response_status_is(BAD_REQUEST)
        .then_location_header_is_absent();
  }

  /// Hosts that pass the normalization but are no valid URI host: a path, characters the URI
  /// rejects (a client could try to inject them into the `Location`), a host the URI keeps only as
  /// registry authority, a malformed escape or IPv6 literal. A configured host is only normalized,
  /// not checked to be a DNS name, so they are known hosts here and reach the URI check.
  @ParameterizedTest
  @ValueSource(strings = {"example.com/path", "a<b", "a\"b", "a_b", "a%zz", "[zz]"})
  void knownHostThatIsNoUriHost_isBadRequest(String host) {
    steps
        .given_a_redirect_handler_to_https_port(443)
        .when_request_to(host, "/")
        .then_response_status_is(BAD_REQUEST)
        .then_location_header_is_absent()
        .then_counted_as(PlainHttpOutcome.BAD_REQUEST);
  }

  @Test
  void leadingDoubleSlash_isPreservedInPath() {
    steps
        .given_a_redirect_handler_to_https_port(443)
        .when_request_to("example.com", "//path/segment?q=1")
        .then_response_status_is(PERMANENT_REDIRECT)
        .then_location_header_is("https://example.com//path/segment?q=1");
  }

  /// An opaque URI has no path at all
  @Test
  void opaqueRequestTarget_fallsBackToRoot() {
    steps
        .given_a_redirect_handler_to_https_port(443)
        .when_request_to("example.com", "mailto:x")
        .then_response_status_is(PERMANENT_REDIRECT)
        .then_location_header_is("https://example.com/");
  }

  @Test
  void relativeRequestTarget_fallsBackToRoot() {
    steps
        .given_a_redirect_handler_to_https_port(443)
        .when_request_to("example.com", "relative")
        .then_response_status_is(PERMANENT_REDIRECT)
        .then_location_header_is("https://example.com/");
  }

  public static class RedirectSteps {

    /// The hosts the handler redirects, with the ones that are no URI host, see
    /// [HttpToHttpsRedirectHandlerBDDTest#knownHostThatIsNoUriHost_isBadRequest(String)]
    static final Set<String> KNOWN_HOSTS =
        Set.of("example.com", "example.com/path", "a<b", "a\"b", "a_b", "a%zz", "[zz]");

    private final ProxyMetrics metrics = new ProxyMetrics();
    private EmbeddedChannel channel;
    private HttpResponse response;

    @Step("Given a redirect handler to HTTPS port {0}")
    public RedirectSteps given_a_redirect_handler_to_https_port(int httpsPort) {
      channel =
          new EmbeddedChannel(
              new HttpToHttpsRedirectHandler(httpsPort, () -> KNOWN_HOSTS, metrics));
      return this;
    }

    @Step
    public RedirectSteps then_counted_as(PlainHttpOutcome outcome) {
      assertThat(metrics.plainHttpRequestCounter.labelValues(outcome.label).get()).isEqualTo(1);
      return this;
    }

    @Step
    public RedirectSteps when_request_to(String host, String uri) {
      return when_request_with_hosts_to_uri(List.of(host), uri);
    }

    @Step
    public RedirectSteps when_undecodable_request_to(String host) {
      HttpRequest request = new DefaultHttpRequest(HTTP_1_1, GET, "/");
      request.headers().add(HttpHeaderNames.HOST, host);
      request.setDecoderResult(DecoderResult.failure(new IllegalArgumentException("malformed")));
      channel.writeInbound(request);
      response = channel.readOutbound();
      return this;
    }

    @Step
    public RedirectSteps when_request_without_host_to_uri(String uri) {
      HttpRequest request = new DefaultHttpRequest(HTTP_1_1, GET, uri);
      channel.writeInbound(request);
      response = channel.readOutbound();
      return this;
    }

    @Step
    public RedirectSteps when_request_with_hosts_to_uri(List<String> hosts, String uri) {
      HttpRequest request = new DefaultHttpRequest(HTTP_1_1, GET, uri);
      hosts.forEach(host -> request.headers().add(HttpHeaderNames.HOST, host));
      channel.writeInbound(request);
      response = channel.readOutbound();
      return this;
    }

    @Step
    public RedirectSteps then_response_status_is(HttpResponseStatus status) {
      assertThat(response.status()).isEqualTo(status);
      return this;
    }

    @Step
    public RedirectSteps then_location_header_is(String expected) {
      assertThat(response.headers().get(HttpHeaderNames.LOCATION)).isEqualTo(expected);
      return this;
    }

    @Step
    public RedirectSteps then_location_header_is_absent() {
      assertThat(response.headers().get(HttpHeaderNames.LOCATION)).isNull();
      return this;
    }
  }
}
