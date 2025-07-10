package org.sensepitch.edge;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.pool.SimpleChannelPool;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;
import io.netty.util.concurrent.Promise;

/**
 * @author Jens Wilke
 */
public class DownstreamHandler extends ChannelInboundHandlerAdapter {

  static final ProxyLogger DEBUG = ProxyLogger.get(DownstreamHandler.class);

  private final UpstreamRouter upstreamRouter;
  // private ChannelFuture upstreamFuture;
  private Future<Channel> upstreamChannel;


  public DownstreamHandler(UpstreamRouter upstreamRouter) {
    this.upstreamRouter = upstreamRouter;
  }

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) {
    DEBUG.traceChannelRead(ctx, msg);
    if (msg instanceof HttpRequest) {
      HttpRequest request = (HttpRequest) msg;
      if (DEBUG.isTraceEnabled()) {
        String clientIP = ((SocketChannel) ctx.channel()).remoteAddress().getAddress().getHostAddress();
        DEBUG.trace(ctx.channel(), msg.getClass().getName() + " " + clientIP + " -> " + request.method() + " " + request.uri());
      }
      Upstream upstream = upstreamRouter.selectUpstream(request);
      requestWithBody(upstream, ctx, request);
    }
    if (msg instanceof LastHttpContent) {
      upstreamChannel.addListener(new FutureListener<Channel>() {
        @Override
        public void operationComplete(Future<Channel> future) throws Exception {
          future.resultNow().writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(
            (ChannelFutureListener)
              future1 ->
                DEBUG.trace(ctx.channel(), future1.channel(), "flushed"));
        }
      });
    } if (msg instanceof HttpContent) {
      upstreamChannel.addListener(future -> upstreamChannel.resultNow().write(msg));
      // Unfortunately, the pool returns a Future<Channel> and not a ChannelFuture, although the
      // channel would always be available immediately, so we always need to write through the listener
      // upstreamChannel.write(msg);
    }
  }

  /**
   * For requests with body, always establish a new upstream connection. We can start writing to the channel
   * even before the connection is established.
   */
  public void requestWithBody(Upstream upstream, ChannelHandlerContext ctx, HttpRequest request) {
    upstreamChannel = upstream.connect(ctx);
    boolean contentExpected = (HttpUtil.isContentLengthSet(request) || HttpUtil.isTransferEncodingChunked(request)) && !(request instanceof FullHttpRequest);
    // HttpCodec will always sent HttpLastContent, however, when we send this upstream the connection might be already
    // closed. Ignore any HttpContent messages and don't send them upstream.
    if (contentExpected) {
      // turn of reading until the upstream connection is established to avoid overflowing
      ctx.channel().config().setAutoRead(false);
    }
    DEBUG.trace(ctx.channel(), "connecting to upstream contentExpected=" + contentExpected);
    /*-
    boolean pooled = true;
    if (pooled) {
      request.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
    } else {
      request.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
    }
    -*/
    request.headers().set("X-Forwarded-For", ctx.channel().remoteAddress().toString());
    request.headers().set("X-Forwarded-Proto", "https");
    // request.headers().set("X-Forwarded-Port", );
    upstreamChannel.addListener(future -> {
      upstreamChannel.resultNow().write(request);
      if (contentExpected) {
        ctx.channel().config().setAutoRead(true);
      }
    });
  }

}
