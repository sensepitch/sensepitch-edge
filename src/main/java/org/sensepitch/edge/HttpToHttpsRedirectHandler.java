package org.sensepitch.edge;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.sensepitch.edge.ProxyMetrics.PlainHttpOutcome;

/// Answers every request on the plain HTTP listener with a permanent redirect (308) to the same
/// resource on HTTPS. The redirect target is `https://<host>[:<httpsPort>]<path[?query]>`; the
/// host is the client `Host` header normalized like [SanitizeHostHandler#normalizeHost] (port and
/// trailing dot removed, lower case). The port is omitted when it is `443`.
///
/// Only a host the edge serves is redirected, matched against the known hosts like
/// [SanitizeHostHandler] does; an unknown host is rejected with `400 Bad Request`, as on the TLS
/// side. A request without a usable host is rejected the same way, see RFC 9112, section 3.2.
/// Every response sets `Connection: close` and the connection is closed after it was flushed: the
/// redirect is the only exchange on a plain HTTP connection.
///
/// The requests are not written to the request log, only counted by outcome in
/// [ProxyMetrics#plainHttpRequestCounter], see `doc/design/encrypted-transport.md`.
///
/// @author Jens Wilke
public class HttpToHttpsRedirectHandler extends SimpleChannelInboundHandler<HttpRequest> {

  private static final int STANDARD_HTTPS_PORT = 443;
  private final int httpsPort;
  private final Supplier<Set<String>> knownHosts;
  private final ProxyMetrics metrics;

  /// @param knownHosts supplies the current known hosts, read per request so a reload applies,
  ///   see [SanitizeHostHandler#filterKnownHosts]
  public HttpToHttpsRedirectHandler(
      int httpsPort, Supplier<Set<String>> knownHosts, ProxyMetrics metrics) {
    this.httpsPort = httpsPort;
    this.knownHosts = knownHosts;
    this.metrics = metrics;
  }

  @Override
  protected void channelRead0(ChannelHandlerContext ctx, HttpRequest request) {
    List<String> hosts = request.headers().getAll(HttpHeaderNames.HOST);
    String host = hosts.size() == 1 ? SanitizeHostHandler.normalizeHost(hosts.getFirst()) : null;
    if (host == null || !request.decoderResult().isSuccess()) {
      reject(ctx, PlainHttpOutcome.BAD_REQUEST);
      return;
    }
    if (SanitizeHostHandler.matchingHost(knownHosts.get(), host) == null) {
      reject(ctx, PlainHttpOutcome.UNKNOWN_HOST);
      return;
    }
    URI authority = redirectAuthority(host);
    if (authority == null) {
      reject(ctx, PlainHttpOutcome.BAD_REQUEST);
      return;
    }
    metrics.countPlainHttpRequest(PlainHttpOutcome.REDIRECT);
    FullHttpResponse response =
        new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.PERMANENT_REDIRECT);
    response.headers().set(HttpHeaderNames.LOCATION, location(authority, request.uri()));
    writeAndClose(ctx, response);
  }

  /// Answers `400 Bad Request` and counts the outcome
  private void reject(ChannelHandlerContext ctx, PlainHttpOutcome outcome) {
    metrics.countPlainHttpRequest(outcome);
    writeAndClose(
        ctx, new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.BAD_REQUEST));
  }

  /// Builds a server authority only from a valid host and the configured HTTPS port.
  private URI redirectAuthority(String host) {
    try {
      int port = httpsPort == STANDARD_HTTPS_PORT ? -1 : httpsPort;
      URI authority = new URI("https", null, host, port, null, null, null);
      return host.equals(authority.getHost()) ? authority : null;
    } catch (URISyntaxException e) {
      return null;
    }
  }

  /// Sends the response and closes the connection once it was flushed.
  private void writeAndClose(ChannelHandlerContext ctx, FullHttpResponse response) {
    response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
    ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
  }

  /// The redirect target for the normalized host and the raw request target.
  private String location(URI authority, String requestTarget) {
    String path = "/";
    try {
      URI uri =
          URI.create(requestTarget.startsWith("/") ? authority + requestTarget : requestTarget);
      String rawPath = uri.getRawPath();
      String rawQuery = uri.getRawQuery();
      if (rawPath != null && rawPath.startsWith("/")) {
        path = rawPath;
      }
      if (rawQuery != null) {
        path = path + "?" + rawQuery;
      }
    } catch (IllegalArgumentException invalidUri) {
      // Netty accepts some request targets that URI.create rejects, e.g. a bad percent escape
    }
    return authority + path;
  }
}
