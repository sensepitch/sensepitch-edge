package org.sensepitch.edge;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.ssl.SniHandler;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;
import javax.net.ssl.SSLSession;

/// Holds the negotiated TLS parameters of a connection for the handlers behind it, the request
/// log. The TLS listener places it right after the [SniHandler]; [Proxy#addHttpHandlers] refuses a
/// pipeline without it, see [#requireTls(ChannelPipeline)]. Tests of the request pipeline on an
/// `EmbeddedChannel`, which has no TLS, put a stand-in of their own at its place.
///
/// One instance per connection.
///
/// @author Jens Wilke
class TlsSessionHandler extends ChannelInboundHandlerAdapter {

  private String protocol;
  private String cipher;

  /// Checks that the pipeline encrypts, called by [Proxy#addHttpHandlers] when the channel is set
  /// up. Not done in `handlerAdded`: Netty does not throw an exception of `handlerAdded` to the
  /// caller, it removes the handler and fires `exceptionCaught`.
  ///
  /// @throws IllegalStateException if the pipeline has neither an [SniHandler] nor an
  ///   [SslHandler]; the `SniHandler` replaces itself by the `SslHandler` after the ClientHello
  void requireTls(ChannelPipeline pipeline) {
    if (pipeline.get(SniHandler.class) == null && pipeline.get(SslHandler.class) == null) {
      throw new IllegalStateException("No TLS in front of the request pipeline");
    }
  }

  /// Takes the parameters when the handshake succeeded; the `SslHandler` that fired the event is
  /// in the pipeline at that moment
  @Override
  public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
    if (evt instanceof SslHandshakeCompletionEvent completion && completion.isSuccess()) {
      SSLSession session = ctx.pipeline().get(SslHandler.class).engine().getSession();
      protocol = session.getProtocol();
      cipher = session.getCipherSuite();
    }
    super.userEventTriggered(ctx, evt);
  }

  /// Negotiated protocol as reported by the JDK, e.g. `TLSv1.3`; set before the first request is
  /// decoded, the handshake comes first
  String protocol() {
    return protocol;
  }

  /// Negotiated cipher suite, IANA name, e.g. `TLS_AES_128_GCM_SHA256`
  String cipher() {
    return cipher;
  }
}
