package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandler;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandler;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/// @author Jens Wilke
public class ProtectionBypassTest {

  @RegisterExtension LogCapture logs = new LogCapture();

  private static final String COOKIE_NAME = "edge-access";
  private static final String ACCESS_URI = "/gate";

  private ProtectionHandler handler;
  private Channel channel;
  private boolean passed;
  private Object messageWritten;

  @Test
  public void testPathBypassSkipsProtection() throws Exception {
    ProtectionConfig protection =
        ProtectionConfig.builder()
            .bypass(new ProtectionBypassConfig(List.of(ACCESS_URI), null))
            .cookieGates(
                List.of(CookieGateConfig.builder().name(COOKIE_NAME).accessUri(ACCESS_URI).build()))
            .build();
    Supplier<ChannelHandler> supplier =
        Protection.handlerSupplier(protection, new ProxyMetrics(), new ConfigFiles());
    assertThat(supplier).isNotNull();
    init(supplier.get());
    DefaultHttpRequest req =
        new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, ACCESS_URI);
    req.headers().set(HttpHeaderNames.HOST, "example.com");
    request(req);
    assertThat(passed).isTrue();
    assertThat(messageWritten).isNull();
    assertThat(req.headers().get(BypassCheck.HEADER)).isEqualTo("config-uri-match");
    assertThat(req.headers().get(Deflector.TRAFFIC_TYPE_HEADER)).isEqualTo("unknown");
  }

  @Test
  public void testRemoteBypassSkipsProtection() throws Exception {
    ProtectionConfig protection =
        ProtectionConfig.builder()
            .bypass(new ProtectionBypassConfig(null, List.of("127.0.0.1")))
            .cookieGates(List.of(CookieGateConfig.builder().name(COOKIE_NAME).build()))
            .build();
    Supplier<ChannelHandler> supplier =
        Protection.handlerSupplier(protection, new ProxyMetrics(), new ConfigFiles());
    assertThat(supplier).isNotNull();
    initWithRemote(supplier.get(), "127.0.0.1", 1234);
    DefaultHttpRequest req =
        new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/private");
    req.headers().set(HttpHeaderNames.HOST, "example.com");
    request(req);
    assertThat(passed).isTrue();
    assertThat(messageWritten).isNull();
    assertThat(req.headers().get(BypassCheck.HEADER)).isEqualTo("config-ip-match");
    assertThat(req.headers().get(Deflector.TRAFFIC_TYPE_HEADER)).isEqualTo("unknown");
  }

  /// A configured bypass does not switch off the protection for other requests: the cookie gate
  /// still answers its access URI.
  @Test
  public void requestOutsideTheBypassIsProtected() throws Exception {
    ProtectionConfig protection =
        ProtectionConfig.builder()
            .bypass(new ProtectionBypassConfig(List.of("/public*"), null))
            .cookieGates(List.of(CookieGateConfig.builder().name(COOKIE_NAME).build()))
            .build();
    init(Protection.handlerSupplier(protection, new ProxyMetrics(), new ConfigFiles()).get());
    DefaultHttpRequest req =
        new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/private");
    req.headers().set(HttpHeaderNames.HOST, "example.com");
    request(req);
    assertThat(passed).isFalse();
    assertThat(messageWritten)
        .isInstanceOfSatisfying(
            HttpResponse.class, response -> assertThat(response.status().code()).isEqualTo(404));
    assertThat(req.headers().get(BypassCheck.HEADER)).isNull();
  }

  /// Every configured prefix counts, also when a longer prefix sorts in between.
  @Test
  public void overlappingPathPrefixes() throws Exception {
    ProtectionBypass bypass =
        new ProtectionBypass(new ProtectionBypassConfig(List.of("/a*", "/ab*", "/exact"), null));
    EmbeddedChannel ch = new EmbeddedChannel(new ChannelInboundHandlerAdapter());
    ChannelHandlerContext ctx = ch.pipeline().firstContext();
    for (String uri : List.of("/a", "/ab", "/abc", "/ac", "/exact")) {
      assertThat(bypass.allowBypass(ctx, get(uri))).as("bypass " + uri).isTrue();
    }
    for (String uri : List.of("/", "/b", "/exact/more")) {
      assertThat(bypass.allowBypass(ctx, get(uri))).as("no bypass " + uri).isFalse();
    }
  }

  /// An unresolvable remote is logged and skipped, the proxy still starts and the other remotes
  /// bypass, see [DefaultBypassCheckTest#unresolvableRemoteIsLogged()] for the deflector bypass
  @Test
  public void unresolvableRemoteIsLoggedAndSkipped() {
    ProtectionBypass bypass =
        new ProtectionBypass(
            new ProtectionBypassConfig(null, List.of("does-not-exist.invalid", "192.0.2.8")));
    assertThat(logs.messages(ProtectionBypass.class))
        .anySatisfy(m -> assertThat(m).contains("does-not-exist.invalid"));
    assertThat(bypass.allowBypass(contextFrom("192.0.2.8"), get("/"))).isTrue();
    assertThat(bypass.allowBypass(contextFrom("192.0.2.7"), get("/"))).isFalse();
  }

  private static ChannelHandlerContext contextFrom(String ip) {
    Channel channel = TestChannels.channelFrom(ip);
    channel.pipeline().addLast(new ChannelInboundHandlerAdapter());
    return channel.pipeline().firstContext();
  }

  @Test
  public void suffixIsNotSupported() {
    assertThatThrownBy(
            () -> new ProtectionBypass(new ProtectionBypassConfig(List.of("*.png"), null)))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  private static HttpRequest get(String uri) {
    return new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, uri);
  }

  private void init(ChannelHandler protectionHandler) {
    ChannelOutboundHandler out =
        new ChannelOutboundHandlerAdapter() {
          @Override
          public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
              throws Exception {
            messageWritten = msg;
            super.write(ctx, msg, promise);
          }
        };
    ChannelInboundHandler in =
        new ChannelInboundHandlerAdapter() {
          @Override
          public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            passed = true;
            super.channelRead(ctx, msg);
          }
        };
    handler = (ProtectionHandler) protectionHandler;
    channel = new EmbeddedChannel(out, handler, in);
  }

  private void initWithRemote(ChannelHandler protectionHandler, String host, int port) {
    ChannelOutboundHandler out =
        new ChannelOutboundHandlerAdapter() {
          @Override
          public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
              throws Exception {
            messageWritten = msg;
            super.write(ctx, msg, promise);
          }
        };
    ChannelInboundHandler in =
        new ChannelInboundHandlerAdapter() {
          @Override
          public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            passed = true;
            super.channelRead(ctx, msg);
          }
        };
    handler = (ProtectionHandler) protectionHandler;
    channel =
        new EmbeddedChannel(out, handler, in) {
          @Override
          public SocketAddress remoteAddress() {
            return new InetSocketAddress(host, port);
          }
        };
  }

  private void request(HttpRequest req) throws Exception {
    ChannelHandlerContext ctx = channel.pipeline().context(ProtectionHandler.class);
    messageWritten = null;
    passed = false;
    handler.channelRead(ctx, req);
    handler.channelRead(ctx, LastHttpContent.EMPTY_LAST_CONTENT);
  }
}
