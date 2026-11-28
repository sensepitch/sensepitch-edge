package org.sensepitch.edge;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;

/// TLS stand-ins for tests that run the request pipeline on an `EmbeddedChannel`, which has no
/// TLS. A stand-in takes the place of what the TLS listener puts in front of the request
/// pipeline: the `SniHandler`, the `SslHandler` it installs and the [TlsSessionHandler]. It is the
/// only way past the check of [Proxy#addHttpHandlers]; it lives in test sources, production code
/// has no bypass. Put it first into the pipeline.
///
/// @author Jens Wilke
final class TestTls {

  static final String PROTOCOL = "TLSv1.3";
  static final String CIPHER = "TLS_AES_128_GCM_SHA256";

  /// A connection with a completed handshake: fires a successful `SslHandshakeCompletionEvent`
  /// when the channel becomes active, as the `SslHandler` does, e.g. for [ExceptionHandler]
  static final TlsSessionHandler HANDLER = new StandIn(true);

  /// A connection whose handshake is not done yet, no event is fired
  static final TlsSessionHandler BEFORE_HANDSHAKE = new StandIn(false);

  private TestTls() {}

  /// Reports [#PROTOCOL] and [#CIPHER] and accepts a pipeline without TLS. Holds no state of a
  /// connection, so one instance serves every test channel.
  @ChannelHandler.Sharable
  private static final class StandIn extends TlsSessionHandler {

    private final boolean handshake;

    StandIn(boolean handshake) {
      this.handshake = handshake;
    }

    @Override
    void requireTls(ChannelPipeline pipeline) {}

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
      if (handshake) {
        ctx.executor()
            .execute(() -> ctx.fireUserEventTriggered(SslHandshakeCompletionEvent.SUCCESS));
      }
      super.channelActive(ctx);
    }

    @Override
    String protocol() {
      return PROTOCOL;
    }

    @Override
    String cipher() {
      return CIPHER;
    }
  }
}
