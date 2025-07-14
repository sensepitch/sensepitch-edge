package org.sensepitch.edge;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandler;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;

import java.util.Set;

/**
 * @author Jens Wilke
 */
@ChannelHandler.Sharable
public class RedirectHandler extends ChannelInboundHandlerAdapter {

  private final Set<String> passDomains;
  private String defaultTarget;

  public RedirectHandler(RedirectConfig cfg) {
    this.passDomains = Set.copyOf(cfg.passDomains());
    this.defaultTarget = cfg.defaultTarget();
  }

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
    if (msg instanceof HttpRequest) {
      String host =  ((HttpRequest) msg).headers().get(HttpHeaderNames.HOST);
      if (host == null || !passDomains.contains(host)) {
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.FOUND);
        response.headers().set(HttpHeaderNames.LOCATION, defaultTarget);
        ctx.writeAndFlush(response);
        discardFollowingContent(ctx);
        return;
      }
    }
    super.channelRead(ctx, msg);
  }

  void discardFollowingContent(ChannelHandlerContext ctx) {
    ChannelInboundHandler inboundHandler = new ChannelInboundHandlerAdapter() {
      @Override
      public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof LastHttpContent) {
          ctx.pipeline().replace(this, "redirect", RedirectHandler.this);
        }
      }
    };
    ctx.pipeline().replace(this, "discard", inboundHandler);
  }

}
