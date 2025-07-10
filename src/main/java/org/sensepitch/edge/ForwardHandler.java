package org.sensepitch.edge;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.pool.ChannelPool;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.LastHttpContent;

/**
 * Receive response and pass it on downstream
 *
 * @author Jens Wilke
 */
public class ForwardHandler extends ChannelInboundHandlerAdapter {

  static ProxyLogger DEBUG = ProxyLogger.get(ForwardHandler.class);
  private Channel downstream;
  private final ChannelPool pool;
  private boolean closeConnection =  false;

  public ForwardHandler(Channel downstream, ChannelPool pool) {
    this.downstream = downstream;
    this.pool = pool;
  }

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) {
    if (msg instanceof HttpResponse) {
      HttpResponse response = (HttpResponse) msg;
      String connection = response.headers().get(HttpHeaderNames.CONNECTION);
      if (DEBUG.isTraceEnabled()) {
        DEBUG.trace(downstream, ctx.channel(), "status=" + response.status() + ", connection=" + connection);
      }
      closeConnection = connection != null && connection.equalsIgnoreCase("close");
      downstream.write(response);
    }
    if (msg instanceof HttpContent) {
      if (msg instanceof LastHttpContent) {
        downstream.writeAndFlush(msg);
        if (closeConnection) {
          ctx.channel().close();
        }
        if (pool != null) {
          // even if close we should release it
          pool.release(ctx.channel());
          DEBUG.trace(downstream, ctx.channel(), "release upstream to pool");
          // channel sits in the pool, don't keep resources
          downstream = null;
        }
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
