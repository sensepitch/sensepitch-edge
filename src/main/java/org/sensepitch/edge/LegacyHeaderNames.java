package org.sensepitch.edge;

import io.netty.util.AsciiString;

/// Names of the HTTP/1.0 era connection headers that Netty deprecates in
/// [io.netty.handler.codec.http.HttpHeaderNames] (4.2.21: "use CONNECTION"). That suggestion does not fit a proxy: `Connection` is a different
/// header, while these still arrive from old clients and servers and must be handled by their own
/// name. RFC 9110, section 7.6.1, lists both as fields an intermediary removes before forwarding,
/// see [DownstreamHandler#stripHopByHopHeaders]; [IngressTimeoutHandler] also sets `Keep-Alive` to
/// tell the client how long an idle connection stays open.
///
/// The values are lower case like Netty's own constants, header names are case-insensitive.
public final class LegacyHeaderNames {

  /// `Keep-Alive`, the parameters of a persistent connection, RFC 2068, section 19.7.1
  public static final AsciiString KEEP_ALIVE = AsciiString.cached("keep-alive");

  /// `Proxy-Connection`, a non-standard `Connection` sent to proxies, RFC 9112, appendix C.2.2
  public static final AsciiString PROXY_CONNECTION = AsciiString.cached("proxy-connection");

  private LegacyHeaderNames() {}
}
