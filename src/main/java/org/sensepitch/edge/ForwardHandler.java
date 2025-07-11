package org.sensepitch.edge;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.pool.ChannelPool;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.LastHttpContent;

/**
 * Receive response from upstream and pass it on downstream
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
      DownstreamProgress.progress(downstream, "got response header status=" + response.status().code());
      if (DEBUG.isTraceEnabled()) {
        DEBUG.trace(downstream, ctx.channel(), "status=" + response.status() + ", connection=" + connection);
      }
      closeConnection = connection != null && connection.equalsIgnoreCase("close");
      // if message contains response and content, write below
      if (!(msg instanceof HttpContent)) {
        downstream.write(response);
      }
    }
    if (msg instanceof LastHttpContent) {
      DownstreamProgress.progress(downstream, "received last content from upstream, flushing response");
      // FIXME: the call of this listener is missing
      downstream.writeAndFlush(msg).addListener(future -> {
        if (future.isSuccess()) {
          DownstreamProgress.complete(downstream);
        } else {
          DownstreamProgress.progress(downstream, "flush error " + future.cause());
        }
      });
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
    } else if (msg instanceof HttpContent) {
      DownstreamProgress.progress(downstream, "writing content");
      downstream.write(msg);
    }
  }

  // TODO: exceptionCaught not reached, should deal with read exceptions properly
  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
    DEBUG.trace(downstream, ctx.channel(), "upstream read exception closing downstream");
    downstream.close();
    ctx.close();
  }

}
