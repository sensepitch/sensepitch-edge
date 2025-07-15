package org.sensepitch.edge;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOption;
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
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;

/**
 * @author Jens Wilke
 */
public class DownstreamHandler extends ChannelInboundHandlerAdapter {

  static final ProxyLogger DEBUG = ProxyLogger.get(DownstreamHandler.class);

  private final UpstreamRouter upstreamRouter;
  private final ProxyMetrics metrics;
  // private ChannelFuture upstreamFuture;
  private Future<Channel> upstreamChannel;
  private boolean requestProcessed;

  public DownstreamHandler(UpstreamRouter upstreamRouter, ProxyMetrics metrics) {
    this.upstreamRouter = upstreamRouter;
    this.metrics = metrics;
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
      upstreamChannel = upstream.connect(ctx);
      augmentHeadersAndForwardRequest(ctx, request);
    } else if (msg instanceof LastHttpContent) {
      // Upstream channel might be still connecting or retrieved and checked by the pool.
      // Queue in all content we receive via the listener.
      upstreamChannel.addListener((FutureListener<Channel>)
        future -> forwardLastContentAndFlush(ctx.channel(), future, (LastHttpContent) msg));
    } else if (msg instanceof HttpContent) {
      upstreamChannel.addListener((FutureListener<Channel>)
        future -> forwardContent(future, (LastHttpContent) msg));
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

  void forwardContent(Future<Channel> future, HttpContent msg) {
    if (future.isSuccess()) {
      future.resultNow().write(msg);
    } else {
      ReferenceCountUtil.release(msg);
      // ignore, only react to an upstream connection problem after receiving LastHttpContent
    }
  }

  void completeWithError(Channel downstream, HttpResponseStatus status) {
    FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status);
    downstream.writeAndFlush(response);
    DownstreamProgress.complete(downstream);
  }

  /**
   * Send the HTTP request, which may include content, upstream
   */
  void augmentHeadersAndForwardRequest(ChannelHandlerContext ctx, HttpRequest request) {
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
        if (request instanceof LastHttpContent) {
          upstreamChannel.resultNow().writeAndFlush(request);
          requestProcessed = true;
        } else {
          upstreamChannel.resultNow().write(request);
        }
        if (contentExpected) {
          ctx.channel().config().setAutoRead(true);
        }
      } else {
        ReferenceCountUtil.release(request);
        // ignore, only react to an upstream connection problem after receiving LastHttpContent
      }
    });
  }

  /**
   * Flush if output buffer is full and apply back pressure to upstream.
   */
  @Override
  public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
    DEBUG.trace(ctx.channel(), "channelWritabilityChanged, isWritable=" + ctx.channel().isWritable());
    if (upstreamChannel == null || !upstreamChannel.isDone()) { return; }
    if (ctx.channel().isWritable()) {
      upstreamChannel.resultNow().setOption(ChannelOption.AUTO_READ, true);
    } else {
      DEBUG.trace(ctx.channel(), "channelWritabilityChanged, flushing");
      // FIXME: in test we never get the isWritable=true
      // upstreamChannel.resultNow().setOption(ChannelOption.AUTO_READ, false);
      ctx.channel().flush();
    }
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
    if (upstreamChannel == null) {
      DEBUG.downstreamError(ctx.channel(), "handshake error", cause);
      metrics.incrementDownstreamHandshakeErrorCount();
    } else {
      if (!requestProcessed) {
        DEBUG.downstreamError(ctx.channel(), "request error", cause);
      } else {
        DEBUG.downstreamError(ctx.channel(), "processing error", cause);
      }
    }
  }

}
