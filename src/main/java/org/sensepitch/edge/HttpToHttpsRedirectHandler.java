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

/// Answers every request on the plain HTTP listener with a permanent redirect (308) to the same
/// resource on HTTPS. The redirect target is `https://<host>[:<httpsPort>]<path[?query]>`; the
/// host is the client `Host` header normalized like [SanitizeHostHandler#normalizeHost] (port and
/// trailing dot removed, lower case). The port is omitted when it is `443`.
///
/// A request without a usable host is rejected with `400 Bad Request`, see RFC 9112, section 3.2.
/// Every response sets `Connection: close` and the connection is closed after it was flushed: the
/// redirect is the only exchange on a plain HTTP connection.
///
/// @author Jens Wilke
public class HttpToHttpsRedirectHandler extends SimpleChannelInboundHandler<HttpRequest> {

  private static final int STANDARD_HTTPS_PORT = 443;
  private final int httpsPort;

  public HttpToHttpsRedirectHandler(int httpsPort) {
    this.httpsPort = httpsPort;
  }

  @Override
  protected void channelRead0(ChannelHandlerContext ctx, HttpRequest request) {
    List<String> hosts = request.headers().getAll(HttpHeaderNames.HOST);
    String host = hosts.size() == 1 ? SanitizeHostHandler.normalizeHost(hosts.getFirst()) : null;
    URI authority = redirectAuthority(host);
    if (authority == null || !request.decoderResult().isSuccess()) {
      writeAndClose(
          ctx, new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.BAD_REQUEST));
      return;
    }
    FullHttpResponse response =
        new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.PERMANENT_REDIRECT);
    response.headers().set(HttpHeaderNames.LOCATION, location(authority, request.uri()));
    writeAndClose(ctx, response);
  }

  /// Builds a server authority only from a valid host and the configured HTTPS port.
  private URI redirectAuthority(String host) {
    if (host == null) {
      return null;
    }
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
