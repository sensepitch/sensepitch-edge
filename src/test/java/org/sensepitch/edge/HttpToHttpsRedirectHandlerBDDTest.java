package org.sensepitch.edge;

import static io.netty.handler.codec.http.HttpMethod.GET;
import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;
import static io.netty.handler.codec.http.HttpResponseStatus.PERMANENT_REDIRECT;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static org.assertj.core.api.Assertions.assertThat;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.util.List;
import net.serenitybdd.annotations.Step;
import net.serenitybdd.junit5.SerenityJUnit5Extension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/// Behaviour of the [HttpToHttpsRedirectHandler]: every plain HTTP request is answered with a
/// permanent redirect (308) to the same resource on HTTPS, using the normalized `Host` header and
/// [ListenConfig#httpsPort()]. [PlainHttpListenerIT] is the real-socket cross check, including the
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
        .then_location_header_is_absent();
  }

  @Test
  void severalHosts_isBadRequest() {
    steps
        .given_a_redirect_handler_to_https_port(17443)
        .when_request_with_hosts_to_uri(List.of("a.com", "b.com"), "/")
        .then_response_status_is(BAD_REQUEST)
        .then_location_header_is_absent();
  }

  @Test
  void hostWithAPath_isBadRequest() {
    steps
        .given_a_redirect_handler_to_https_port(443)
        .when_request_to("example.com/path", "/")
        .then_response_status_is(BAD_REQUEST)
        .then_location_header_is_absent();
  }

  @Test
  void leadingDoubleSlash_isPreservedInPath() {
    steps
        .given_a_redirect_handler_to_https_port(443)
        .when_request_to("example.com", "//path/segment?q=1")
        .then_response_status_is(PERMANENT_REDIRECT)
        .then_location_header_is("https://example.com//path/segment?q=1");
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

    private EmbeddedChannel channel;
    private HttpResponse response;

    @Step("Given a redirect handler to HTTPS port {0}")
    public RedirectSteps given_a_redirect_handler_to_https_port(int httpsPort) {
      channel = new EmbeddedChannel(new HttpToHttpsRedirectHandler(httpsPort));
      return this;
    }

    @Step
    public RedirectSteps when_request_to(String host, String uri) {
      return when_request_with_hosts_to_uri(List.of(host), uri);
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
