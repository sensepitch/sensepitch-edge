package org.sensepitch.edge;

/**
 * @author Jens Wilke
 */
import static io.netty.handler.codec.http.HttpResponseStatus.FOUND;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static io.netty.handler.codec.http.HttpMethod.GET;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelInboundHandler;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.util.ReferenceCountUtil;

class RedirectHandlerTest {

  private List<String> passDomains;
  private String defaultTarget;
  private RedirectHandler handler;

  @BeforeEach
  void setUp() {
    passDomains = List.of("www.foo.com", "bar.com", "www.baz.com");
    defaultTarget = "https://default.example";

    // build real RedirectConfig instead of mocking
    RedirectConfig cfg = RedirectConfig.builder()
      .passDomains(passDomains)
      .defaultTarget(defaultTarget)
      .build();

    handler = new RedirectHandler(cfg);
  }

  @Test
  void channelRead_withAllowedHost_passesThroughWithoutWriting() {
    EmbeddedChannel ch = new EmbeddedChannel(handler, new ChannelInboundHandlerAdapter());
    DefaultHttpRequest req = new DefaultHttpRequest(HTTP_1_1, GET, "/");
    req.headers().set(HttpHeaderNames.HOST, "bar.com");

    boolean forwarded = ch.writeInbound(req);
    assertThat(forwarded).isTrue();

    Object forwardedMsg = ch.readInbound();
    assertThat(forwardedMsg).isSameAs(req);
  }

  @Test
  void channelRead_withNoHost_redirectsToDefaultAndInstallsDiscard() {
    EmbeddedChannel ch = new EmbeddedChannel(handler);
    DefaultHttpRequest req = new DefaultHttpRequest(HTTP_1_1, GET, "/");
    // no Host header

    assertThat(ch.writeInbound(req)).isFalse();
    FullHttpResponse resp = ch.readOutbound();
    assertThat(resp.status()).isEqualTo(FOUND);
    assertThat(resp.headers().get(HttpHeaderNames.LOCATION))
      .isEqualTo(defaultTarget);

    assertThat(ch.pipeline().get("discard"))
      .isInstanceOf(ChannelInboundHandlerAdapter.class);
  }

  @Test
  void channelRead_withMappedHost_redirectsToMappedTarget() {
    EmbeddedChannel ch = new EmbeddedChannel(handler);
    DefaultHttpRequest req = new DefaultHttpRequest(HTTP_1_1, GET, "/");
    req.headers().set(HttpHeaderNames.HOST, "foo.com");

    assertThat(ch.writeInbound(req)).isFalse();
    FullHttpResponse resp = ch.readOutbound();
    assertThat(resp.status()).isEqualTo(FOUND);
    assertThat(resp.headers().get(HttpHeaderNames.LOCATION))
      .isEqualTo("https://www.foo.com");
  }

  @Test
  void discardFollowingContent_restoresHandler_onLastHttpContent() {
    EmbeddedChannel ch = new EmbeddedChannel(handler);
    handler.discardFollowingContent(ch.pipeline().lastContext());

    assertThat(ch.pipeline().get("discard")).isNotNull();

    ch.writeInbound(new DefaultHttpContent(Unpooled.copiedBuffer("hi", io.netty.util.CharsetUtil.UTF_8)));
    LastHttpContent last = LastHttpContent.EMPTY_LAST_CONTENT;
    ch.writeInbound(last);

    assertThat(ch.pipeline().get("redirect"))
      .isInstanceOf(RedirectHandler.class);
  }
}
