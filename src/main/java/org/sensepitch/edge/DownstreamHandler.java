package org.sensepitch.edge;

import io.netty.buffer.Unpooled;
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

import java.net.InetSocketAddress;

/**
 * @author Jens Wilke
 */
public class DownstreamHandler extends ChannelInboundHandlerAdapter {

  static final ProxyLogger DEBUG = ProxyLogger.get(DownstreamHandler.class);

  private final UpstreamRouter upstreamRouter;
  private final ProxyMetrics metrics;
  // private ChannelFuture upstreamFuture;
  private Future<Channel> upstreamChannelFuture;
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
      upstreamChannelFuture = upstream.connect(ctx);
      augmentHeadersAndForwardRequest(ctx, request);
    } else if (msg instanceof LastHttpContent) {
      // Upstream channel might be still connecting or retrieved and checked by the pool.
      // Queue in all content we receive via the listener.
      upstreamChannelFuture.addListener((FutureListener<Channel>)
        future -> forwardLastContentAndFlush(ctx.channel(), future, (LastHttpContent) msg));
    } else if (msg instanceof HttpContent) {
      upstreamChannelFuture.addListener((FutureListener<Channel>)
        future -> forwardContent(future, (LastHttpContent) msg));
    }
  }

  void forwardLastContentAndFlush(Channel downstream, Future<Channel> future, LastHttpContent msg) {
    if (future.isSuccess()) {
      DownstreamProgress.progress(downstream, "last content, write and flushing to upstream");
      future.resultNow().writeAndFlush(msg);
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
    addProxyHeaders(ctx, request);
    upstreamChannelFuture.addListener(future -> {
      if (future.isSuccess()) {
        if (request instanceof LastHttpContent) {
          upstreamChannelFuture.resultNow().writeAndFlush(request);
          requestProcessed = true;
        } else {
          upstreamChannelFuture.resultNow().write(request);
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
   * Add standard minimal proxy request headers. We don't need to set X-Forwarded-Host, because
   * this is already set in the Host header, also for https and SNI. We also don't include
   * code here the support non-standard ports. If additional headers are needed, another
   * handler can be added depending on configuration.
   *
   * @see SniToHostHeader
   */
  private static void addProxyHeaders(ChannelHandlerContext ctx, HttpRequest request) {
    if (ctx.channel().remoteAddress() instanceof InetSocketAddress) {
      InetSocketAddress addr = (InetSocketAddress) ctx.channel().remoteAddress();
      request.headers().set("X-Forwarded-For", addr.getAddress().getHostAddress());
    }
    request.headers().set("X-Forwarded-Proto", "https");
  }

  /**
   * Flush if output buffer is full and throttle upstream.
   */
  @Override
  public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
    if (upstreamChannelFuture == null || !upstreamChannelFuture.isDone()) { return; }
    if (ctx.channel().isWritable()) {
      DEBUG.trace(ctx.channel(), "channelWritabilityChanged, isWritable=true, start upstream reads");
      upstreamChannelFuture.resultNow().config().setAutoRead(true);
    } else {
      DEBUG.trace(ctx.channel(), "channelWritabilityChanged, isWritable=false, queuing flush, stop upstream reads");
      upstreamChannelFuture.resultNow().config().setAutoRead(false);
      // flush task is only created once for the context
      if (flushTask == null) {
        flushTask = new Runnable() {
          @Override
          public void run() {
            ctx.channel().writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(new ChannelFutureListener() {
              @Override
              public void operationComplete(ChannelFuture future) throws Exception {
                if (future.isSuccess() && ctx.channel().isActive()) {
                  if (!ctx.channel().isWritable()) {
                    DEBUG.trace(future.channel(), "flush complete, output buffer still full, queuing another flush");
                    ctx.executor().execute(flushTask);
                  } else {
                    DEBUG.trace(future.channel(), "flush complete, output buffer writable");
                  }
                }
              }
            });
          }
        };
      }
      ctx.executor().execute(flushTask);
    }
  }

  private Runnable flushTask;

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
    if (upstreamChannelFuture == null) {
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
