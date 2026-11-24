package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.sensepitch.edge.SanitizeHostHandler.MISSING_HOST;
import static org.sensepitch.edge.SanitizeHostHandler.NIL_HOST;
import static org.sensepitch.edge.SanitizeHostHandler.UNKNOWN_HOST;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.ssl.SniCompletionEvent;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/// @author Jens Wilke
class SanitizeHostHandlerTest {

  final SanitizeHostHandler handler =
      new SanitizeHostHandler(
          List.of("example.com", "[2001:db8::1]", "192.0.2.1", "*.example.net"));

  HttpRequest pass(HttpRequest request) {
    EmbeddedChannel channel = new EmbeddedChannel(handler);
    channel.writeInbound(request);
    HttpRequest result = channel.readInbound();
    channel.finishAndReleaseAll();
    return result;
  }

  HttpRequest request(HttpMethod method, String... hosts) {
    HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, method, "/");
    for (String host : hosts) {
      request.headers().add(HttpHeaderNames.HOST, host);
    }
    return request;
  }

  String sanitize(String... hosts) {
    HttpRequest result = pass(request(HttpMethod.GET, hosts));
    assertThat(result.headers().getAll(HttpHeaderNames.HOST)).hasSize(1);
    return result.headers().get(HttpHeaderNames.HOST);
  }

  /// The TLS server name of the connection, sanitized like the host header when asked for
  @Test
  void serverName() {
    assertThat(sanitize("Tenant.Example.net.:443")).isEqualTo("tenant.example.net");
    assertThat(serverName(new SniCompletionEvent("tenant.example.net"))).isEqualTo("*.example.net");
    assertThat(serverName(null)).isEqualTo(NIL_HOST);
    assertThat(serverName(new SniCompletionEvent((String) null))).isEqualTo(MISSING_HOST);
    assertThat(serverName(new SniCompletionEvent("Example.COM"))).isEqualTo("example.com");
    assertThat(serverName(new SniCompletionEvent("other.com"))).isEqualTo(UNKNOWN_HOST);
  }

  /// Server name of a connection that received the event, or none if `null`
  String serverName(SniCompletionEvent event) {
    EmbeddedChannel channel = new EmbeddedChannel(handler);
    if (event != null) {
      channel.pipeline().fireUserEventTriggered(event);
    }
    String name = handler.serverName(channel);
    channel.finishAndReleaseAll();
    return name;
  }

  @Test
  void knownHost() {
    assertThat(sanitize("example.com")).isEqualTo("example.com");
    assertThat(sanitize("192.0.2.1")).isEqualTo("192.0.2.1");
    assertThat(sanitize("[2001:db8::1]")).isEqualTo("[2001:db8::1]");
  }

  @Test
  void unknownOrMissingHost() {
    assertThat(sanitize("other.com")).isEqualTo(UNKNOWN_HOST);
    assertThat(sanitize("")).isEqualTo(UNKNOWN_HOST);
    assertThat(sanitize()).isEqualTo(MISSING_HOST);
  }

  @Test
  void portIsRemoved() {
    assertThat(sanitize("example.com:8443")).isEqualTo("example.com");
    assertThat(sanitize("192.0.2.1:443")).isEqualTo("192.0.2.1");
    assertThat(sanitize("[2001:db8::1]:8443")).isEqualTo("[2001:db8::1]");
    // empty port is valid according to RFC 3986
    assertThat(sanitize("example.com:")).isEqualTo("example.com");
  }

  @Test
  void malformedPortOrAddressIsUnknown() {
    assertThat(sanitize("example.com:abc")).isEqualTo(UNKNOWN_HOST);
    assertThat(sanitize("example.com:443:1")).isEqualTo(UNKNOWN_HOST);
    assertThat(sanitize("example.com:1234567")).isEqualTo(UNKNOWN_HOST);
    assertThat(sanitize("2001:db8::1")).isEqualTo(UNKNOWN_HOST);
    assertThat(sanitize("[2001:db8::1")).isEqualTo(UNKNOWN_HOST);
    assertThat(sanitize("[2001:db8::1]x")).isEqualTo(UNKNOWN_HOST);
    assertThat(sanitize(":443")).isEqualTo(UNKNOWN_HOST);
    assertThat(sanitize("example.com:-1")).isEqualTo(UNKNOWN_HOST);
    assertThat(sanitize("example.com:4/3")).isEqualTo(UNKNOWN_HOST);
  }

  /// Non-ASCII and a tab inside the host never match a configured host, also not after the
  /// lower-casing. Netty's header validation already rejects the other control characters.
  @Test
  void tabOrNonAsciiCharacterIsUnknown() {
    assertThat(sanitize("ex\u00e4mple.com")).isEqualTo(UNKNOWN_HOST);
    assertThat(sanitize("exa\tmple.com")).isEqualTo(UNKNOWN_HOST);
  }

  /// A wildcard matches a label only within the length of a DNS name, 253 characters
  @Test
  void wildcardMatchLongerThanADnsNameIsUnknown() {
    assertThat(matchesWildcardOf(253)).isTrue();
    assertThat(matchesWildcardOf(254)).isFalse();
  }

  /// Whether a host of the length, with a short first label, matches the wildcard of its parent
  boolean matchesWildcardOf(int length) {
    String suffix = ".example.net";
    String host = "a." + "b".repeat(length - 2 - suffix.length()) + suffix;
    assertThat(host).hasSize(length);
    String wildcard = "*" + host.substring(1);
    return wildcard.equals(SanitizeHostHandler.matchingHost(Set.of(wildcard), host));
  }

  /// Host names are case-insensitive, a trailing dot denotes the same, fully qualified name.
  @Test
  void caseAndTrailingDotAreNormalized() {
    assertThat(sanitize("Example.COM")).isEqualTo("example.com");
    assertThat(sanitize("example.com.")).isEqualTo("example.com");
    assertThat(sanitize("EXAMPLE.com.:443")).isEqualTo("example.com");
    assertThat(sanitize("[2001:DB8::1]")).isEqualTo("[2001:db8::1]");
    assertThat(sanitize("example.com..")).isEqualTo(UNKNOWN_HOST);
  }

  /// RFC 9112 section 3.2: a request with more than one Host header must be rejected. The unknown
  /// host is rejected with 400 by [UnservicedHost]. Removing the extra headers makes sure an
  /// upstream never sees a different host than the one the proxy decided on.
  @Test
  void multipleHostHeadersAreUnknown() {
    assertThat(sanitize("example.com", "example.com")).isEqualTo(UNKNOWN_HOST);
    assertThat(sanitize("example.com", "evil.com")).isEqualTo(UNKNOWN_HOST);
  }

  @Test
  void unsupportedMethodIsReplaced() {
    for (String method : List.of("TRACE", "CONNECT", "FOO")) {
      HttpRequest result = pass(request(HttpMethod.valueOf(method), "example.com"));
      assertThat(result.method()).isEqualTo(SanitizeHostHandler.UNKNOWN_METHOD);
    }
    for (String method : SanitizeHostHandler.SUPPORTED_HTTP_METHODS) {
      HttpRequest result = pass(request(HttpMethod.valueOf(method), "example.com"));
      assertThat(result.method().name()).isEqualTo(method);
    }
  }

  /// A configured special host is dropped, so a client sending it never reaches a site: the
  /// handler sets it itself for unknown and missing hosts
  @Test
  void configuredSpecialHostIsDropped() {
    assertThat(SanitizeHostHandler.filterKnownHosts(List.of("example.com", UNKNOWN_HOST)))
        .containsExactly("example.com");
    SanitizeHostHandler withSpecial = new SanitizeHostHandler(List.of("example.com", MISSING_HOST));
    EmbeddedChannel channel = new EmbeddedChannel(withSpecial);
    channel.writeInbound(request(HttpMethod.GET, MISSING_HOST));
    HttpRequest result = channel.readInbound();
    channel.finishAndReleaseAll();
    assertThat(result.headers().get(HttpHeaderNames.HOST)).isEqualTo(UNKNOWN_HOST);
  }

  @Test
  void configuredHostMustBeNormalized() {
    for (String host : List.of("Example.com", "example.com.", "example.com:443", "a b")) {
      assertThatThrownBy(() -> SanitizeHostHandler.filterKnownHosts(List.of(host)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining(host);
    }
  }
}
