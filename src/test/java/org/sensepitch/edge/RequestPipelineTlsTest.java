package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.ssl.SniHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;
import io.netty.util.Mapping;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/// The request pipeline is only set up behind TLS, see [Proxy#addHttpHandlers], and the
/// [TlsSessionHandler] keeps the negotiated parameters for the request log. The other tests of
/// the pipeline put the stand-in of [TestTls] in front; `ExternalTcpIT` checks a real session end
/// to end.
///
/// @author Jens Wilke
class RequestPipelineTlsTest {

  final Proxy proxy = new Proxy(CompleteTest.COMMON_CONFIG, new RequestLogCapture());
  final EmbeddedChannel channel = new EmbeddedChannel();

  @AfterEach
  void tearDown() {
    channel.finishAndReleaseAll();
  }

  // the check when the channel is set up

  @Test
  void withoutTlsSessionHandlerNothingIsAdded() {
    assertThatThrownBy(() -> proxy.addHttpHandlers(channel.pipeline()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("No TLS session handler in front of the request pipeline");
    assertThat(channel.pipeline().get(RequestLoggingHandler.class)).isNull();
  }

  @Test
  void withoutTlsNothingIsAdded() {
    channel.pipeline().addLast(new TlsSessionHandler());
    assertThatThrownBy(() -> proxy.addHttpHandlers(channel.pipeline()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("No TLS in front of the request pipeline");
    assertThat(channel.pipeline().get(RequestLoggingHandler.class)).isNull();
  }

  /// The state of the TLS listener when its channel is set up, before the ClientHello
  @Test
  void behindTheSniHandler() {
    Mapping<String, SslContext> noContext = host -> null;
    channel.pipeline().addLast(new SniHandler(noContext), new TlsSessionHandler());
    proxy.addHttpHandlers(channel.pipeline());
    assertThat(channel.pipeline().get(RequestLoggingHandler.class)).isNotNull();
  }

  /// The `SniHandler` has replaced itself by the `SslHandler`
  @Test
  void behindTheSslHandler() throws SSLException {
    channel.pipeline().addLast(clientSslHandler(), new TlsSessionHandler());
    proxy.addHttpHandlers(channel.pipeline());
    assertThat(channel.pipeline().get(RequestLoggingHandler.class)).isNotNull();
  }

  // the session

  /// Takes what the engine of the `SslHandler` reports; in this test that is the JDK's placeholder
  /// of an engine without handshake, the values after a real one are checked by `ExternalTcpIT`
  @Test
  void successfulHandshakeKeepsTheSession() throws SSLException {
    SslHandler sslHandler = clientSslHandler();
    TlsSessionHandler tls = new TlsSessionHandler();
    channel.pipeline().addLast(sslHandler, tls);
    SSLSession session = sslHandler.engine().getSession();
    channel.pipeline().fireUserEventTriggered(SslHandshakeCompletionEvent.SUCCESS);
    assertThat(tls.protocol()).isEqualTo(session.getProtocol());
    assertThat(tls.cipher()).isEqualTo(session.getCipherSuite());
  }

  /// A failed handshake ends the connection before any request, nothing is kept
  @Test
  void otherEventsKeepNothing() {
    TlsSessionHandler tls = new TlsSessionHandler();
    channel.pipeline().addLast(tls);
    channel
        .pipeline()
        .fireUserEventTriggered(new SslHandshakeCompletionEvent(new SSLException("failed")));
    channel.pipeline().fireUserEventTriggered("other");
    assertThat(tls.protocol()).isNull();
    assertThat(tls.cipher()).isNull();
  }

  private static SslHandler clientSslHandler() throws SSLException {
    return SslContextBuilder.forClient().build().newHandler(ByteBufAllocator.DEFAULT);
  }
}
