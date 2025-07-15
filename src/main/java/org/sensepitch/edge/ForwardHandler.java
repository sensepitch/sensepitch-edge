package org.sensepitch.edge;

import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOption;
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
      Channel downstreamCopy = downstream;
      downstreamCopy.writeAndFlush(msg).addListener(future -> {
        if (future.isSuccess()) {
          DownstreamProgress.complete(downstreamCopy);
        } else {
          DownstreamProgress.complete(downstreamCopy);
          // DownstreamProgress.progress(downstreamCopy, "flush error " + future.cause());
        }
      });
      DownstreamProgress.progress(downstream, "received last content from upstream, flushing response");
      if (closeConnection) {
        ctx.channel().close();
      }
      if (pool != null) {
        // even if closed, we should release it
        pool.release(ctx.channel());
        DEBUG.trace(downstream, ctx.channel(), "release upstream to pool");
        // channel sits in the pool, don't keep resources
        downstream = null;
      }
    } else if (msg instanceof HttpContent) {
      DEBUG.trace(downstream, ctx.channel(), "write " + msg);
      downstream.write(msg).addListener(future -> {
        DEBUG.trace(downstream, ctx.channel(), "write completed " + msg);
      });
    }
  }

  /**
   * Flush if output buffer is full and apply back pressure to downstream
   */
  @Override
  public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
    DEBUG.trace(ctx.channel(), "channelWritabilityChanged, isWritable=" + ctx.channel().isWritable());
    if (ctx.channel().isWritable()) {
      downstream.setOption(ChannelOption.AUTO_READ, true);
    } else {
      // FIXME: in test we never get the isWritable=true
      // downstream.setOption(ChannelOption.AUTO_READ, false);
      ctx.channel().flush();
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
