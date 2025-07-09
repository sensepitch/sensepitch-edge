package org.sensepitch.edge;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.pool.ChannelPool;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.LastHttpContent;

/**
 * Receive response and pass it on downstream
 *
 * @author Jens Wilke
 */
public class ForwardHandler extends ChannelInboundHandlerAdapter {

  static ProxyLogger DEBUG = ProxyLogger.get(ForwardHandler.class);
  private final Channel downstream;
  private final ChannelPool pool;
  private boolean keepAlive;

  public ForwardHandler(Channel downstream) {
    this.downstream = downstream;
    this.pool = null;
  }

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) {
    if (msg instanceof HttpResponse) {
      HttpResponse response = (HttpResponse) msg;
      DEBUG.trace(downstream, ctx.channel(), "status=" + response.status());
      downstream.write(response);
    }
    if (msg instanceof HttpContent) {
      if (msg instanceof LastHttpContent) {
        downstream.writeAndFlush(msg);
      } else {
        downstream.write(msg);
      }
    }

  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
    DEBUG.trace(downstream, ctx.channel(), "upstream read exception closing downstream");
    downstream.close();
    ctx.close();
  }

}
