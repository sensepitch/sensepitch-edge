package org.sensepitch.edge;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;

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
      DownstreamProgress.progress(ctx.channel(), "request received");
      Upstream upstream = upstreamRouter.selectUpstream(request);
      requestWithBody(upstream, ctx, request);
    }
    // Upstream channel might be still connecting or retrieved and checked by the pool.
    // Queue in all content we receive via the listener.
    if (msg instanceof LastHttpContent) {
      upstreamChannel.addListener((FutureListener<Channel>)
        future -> forwardLastContentAndFlush(ctx.channel(), future, (LastHttpContent) msg));
    } else if (msg instanceof HttpContent) {
      upstreamChannel.addListener((FutureListener<Channel>)
        future -> forwardContent(ctx.channel(), future, (LastHttpContent) msg));
    }
  }

  void forwardLastContentAndFlush(Channel downstream, Future<Channel> future, LastHttpContent msg) {
    if (future.isSuccess()) {
      future.resultNow().writeAndFlush(msg).addListener(new ChannelFutureListener() {
        @Override
        public void operationComplete(ChannelFuture future) throws Exception {
          if (future.isSuccess()) {
            DownstreamProgress.progress(downstream, "last content received, flushed to upstream");
            DEBUG.trace(downstream, future.channel(), "last content received, flushed to upstream");
          }
        }
      });
      DownstreamProgress.progress(downstream, "last content, write and flushing to upstream");
    } else {
      Throwable cause = future.cause();
      // TODO: counter!
      if (cause instanceof IllegalStateException) {
        if (cause.getMessage() != null && cause.getMessage().contains("Too many outstanding acquire operations")) {
          completeWithError(downstream, HttpResponseStatus.valueOf(509, "Bandwidth Limit Exceeded"));
          return;
        }
      }
      DEBUG.error(downstream, "unknown upstream connection problem", future.cause());
      if (cause.getMessage() != null) {
        completeWithError(downstream, HttpResponseStatus.valueOf(577, "Upstream connection problem: " + cause.getMessage()));
      } else {
        completeWithError(downstream, HttpResponseStatus.valueOf(577, "Upstream connection problem"));
      }
    }
  }

  void forwardContent(Channel downstream, Future<Channel> future, HttpContent msg) {
    if (future.isSuccess()) {
      future.resultNow().write(msg);
    } else {
      // ignore, only react to an upstream connection problem after receiving LastHttpContent
    }
  }

  void completeWithError(Channel downstream, HttpResponseStatus status) {
    FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status);
    downstream.writeAndFlush(response);
    DownstreamProgress.complete(downstream);
  }

  /**
   * For requests with body, always establish a new upstream connection. We can start writing to the channel
   * even before the connection is established.
   */
  public void requestWithBody(Upstream upstream, ChannelHandlerContext ctx, HttpRequest request) {
    upstreamChannel = upstream.connect(ctx);
    boolean contentExpected = (HttpUtil.isContentLengthSet(request) || HttpUtil.isTransferEncodingChunked(request)) && !(request instanceof FullHttpRequest);
    if (contentExpected) {
      // turn of reading until the upstream connection is established to avoid overflowing
      ctx.channel().config().setAutoRead(false);
    }
    DEBUG.trace(ctx.channel(), "connecting to upstream contentExpected=" + contentExpected);
    request.headers().set("X-Forwarded-For", ctx.channel().remoteAddress().toString());
    request.headers().set("X-Forwarded-Proto", "https");
    // request.headers().set("X-Forwarded-Port", );
    upstreamChannel.addListener(future -> {
      if (future.isSuccess()) {
        upstreamChannel.resultNow().write(request);
        if (contentExpected) {
          ctx.channel().config().setAutoRead(true);
        }
      } else {
        // ignore, only react to an upstream connection problem after receiving LastHttpContent
      }
    });
  }

}
