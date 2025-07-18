package org.sensepitch.edge;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;
import io.netty.handler.timeout.ReadTimeoutException;
import io.netty.handler.timeout.TimeoutException;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;

import java.net.InetSocketAddress;

/**
 * @author Jens Wilke
 */
public class DownstreamHandler extends ChannelDuplexHandler {

  static final ProxyLogger DEBUG = ProxyLogger.get(DownstreamHandler.class);

  private final UpstreamRouter upstreamRouter;
  private final ProxyMetrics metrics;
  private Future<Channel> upstreamChannelFuture;
  private boolean requestReceived;
  private boolean lastContentReceivedFromClient;
  private boolean sslHandshakeComplete;
  private Runnable flushTask;
  private HttpRequest request;

  public DownstreamHandler(UpstreamRouter upstreamRouter, ProxyMetrics metrics) {
    this.upstreamRouter = upstreamRouter;
    this.metrics = metrics;
  }

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) {
    DEBUG.traceChannelRead(ctx, msg);
    if (msg instanceof HttpRequest) {
      request = (HttpRequest) msg;
      if (DEBUG.isTraceEnabled()) {
        String clientIP = ((SocketChannel) ctx.channel()).remoteAddress().getAddress().getHostAddress();
        DEBUG.trace(ctx.channel(), msg.getClass().getName() + " " + clientIP + " -> " + request.method() + " " + request.uri());
      }
      requestReceived = true;
      DownstreamProgress.progress(ctx.channel(), "request received, selecting upstream");
      Upstream upstream = upstreamRouter.selectUpstream(request);
      upstreamChannelFuture = upstream.connect(ctx);
      augmentHeadersAndForwardRequest(ctx, request);
    } else if (msg instanceof LastHttpContent) {
      DownstreamProgress.progress(ctx.channel(), "last content, waiting for upstream");
      // Upstream channel might be still connecting or retrieved and checked by the pool.
      // Queue in all content we receive via the listener.
      upstreamChannelFuture.addListener((FutureListener<Channel>)
        future -> forwardLastContentAndFlush(ctx.channel(), future, (LastHttpContent) msg));
      lastContentReceivedFromClient = true;
    } else if (msg instanceof HttpContent) {
      upstreamChannelFuture.addListener((FutureListener<Channel>)
        future -> forwardContent(future, (LastHttpContent) msg));
    }
  }

  // runs in another tread!
  void forwardLastContentAndFlush(Channel downstream, Future<Channel> future, LastHttpContent msg) {
    if (future.isSuccess()) {
      String clientIP = ((SocketChannel) downstream).remoteAddress().getAddress().getHostAddress();
      String upstreamString = DEBUG.channelId(future.resultNow());
      String requestString = "upstream=" + upstreamString + ", " + request.headers().get(HttpHeaderNames.HOST) + " " + clientIP + " " + request.method() + " " + request.uri();
      DownstreamProgress.progress(downstream, "write and flushing last content to upstream, " + requestString);
      future.resultNow().writeAndFlush(msg).addListener((ChannelFutureListener) future1 -> {
        if (!future1.isSuccess()) {
          future1.channel().close();
          completeWithError(downstream, HttpResponseStatus.valueOf(577, "Upstream write problem: " + future1.cause()));
        }
      });
    } else {
      DownstreamProgress.complete(downstream);
      ReferenceCountUtil.release(msg);
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
        completeWithError(downstream, HttpResponseStatus.valueOf(577, "Upstream connection problem: " + cause));
      } else {
        completeWithError(downstream, HttpResponseStatus.valueOf(577, "Upstream connection problem"));
      }
    }
  }

  // runs in another tread!
  void forwardContent(Future<Channel> future, HttpContent msg) {
    if (future.isSuccess()) {
      future.resultNow().write(msg);
    } else {
      ReferenceCountUtil.release(msg);
      // ignore, only react to an upstream connection problem after receiving LastHttpContent
    }
  }

  void completeWithError(Channel downstream, HttpResponseStatus status) {
    assert sslHandshakeComplete;
    FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status);
    response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
    downstream.writeAndFlush(response);
    DownstreamProgress.complete(downstream);
    downstream.close();
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
    addProxyHeaders(ctx, request);
    DEBUG.trace(ctx.channel(), "connecting to upstream contentExpected=" + contentExpected);
    upstreamChannelFuture.addListener((FutureListener<Channel>)
      future -> {
        if (future.isSuccess()) {
          upstreamChannelFuture.resultNow().write(request);
          if (contentExpected) {
            ctx.channel().config().setAutoRead(true);
          }
        } else {
          ReferenceCountUtil.release(request);
          // ignore, only react to an upstream connection problem after receiving LastHttpContent
        }
      }
    );
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
   * If upstream is active sending content, throttle reading. In any case flush the buffer it its full.
   */
  @Override
  public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
    if (upstreamChannelFuture != null && upstreamChannelFuture.isDone()) {
      if (ctx.channel().isWritable()) {
        DEBUG.trace(ctx.channel(), "channelWritabilityChanged, isWritable=true, start upstream reads");
        upstreamChannelFuture.resultNow().config().setAutoRead(true);
      } else {
        DEBUG.trace(ctx.channel(), "channelWritabilityChanged, isWritable=false, queuing flush, stop upstream reads");
        upstreamChannelFuture.resultNow().config().setAutoRead(false);
      }
    }
    if (!ctx.channel().isWritable()) {
      DEBUG.trace(ctx.channel(), "channelWritabilityChanged, isWritable=false, queuing flush, stop upstream reads");
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

  /**
   * If the channel becomes inactive, make sure upstream reads are enabled, so
   * upstream read is completed and the connection is put back into the pool.
   * Downstream writes will produce errors and the logger will log it once the
   * listener to the last content write is executed.
   * That should work okay for small responses. For longer responses it might
   * be better to close the upstream channel to avoid transferring data needlessly.
   */
  @Override
  public void channelInactive(ChannelHandlerContext ctx) throws Exception {
    if (upstreamChannelFuture != null && upstreamChannelFuture.isDone()) {
      upstreamChannelFuture.resultNow().config().setAutoRead(true);
    }
  }

  /**
   * Remove upstream reference when processing for this request is complete.
   * The upstream channel will go back to the pool, so we need to ensure that we don't
   * have it anymore for throttling.
   * 
   * @see #channelWritabilityChanged(ChannelHandlerContext)
   */
  @Override
  public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
    if (msg instanceof LastHttpContent) {
      upstreamChannelFuture = null;
    }
    super.write(ctx, msg, promise);
  }

  @Override
  public void userEventTriggered(ChannelHandlerContext ctx, Object event) throws Exception {
    if (event instanceof SslHandshakeCompletionEvent sslEvent) {
      if (sslEvent.isSuccess()) {
        sslHandshakeComplete = true;
      }
    }
    super.userEventTriggered(ctx, event);
  }

  /**
   * Exception reading from the socket, like a connection reset.
   */
  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
    if (!sslHandshakeComplete) {
      DEBUG.downstreamError(ctx.channel(), "handshake error", cause);
      metrics.incrementDownstreamHandshakeErrorCount();
    } else if (!requestReceived || !lastContentReceivedFromClient) {
      HttpResponseStatus status = HttpResponseStatus.BAD_REQUEST;
      if (cause instanceof ReadTimeoutException) {
        status = HttpResponseStatus.REQUEST_TIMEOUT;
      } else if (cause instanceof TooLongFrameException) {
        // TODO: interpolated with ChatGPT, needs testing
        String msg = cause.toString().toLowerCase();
        if (msg.contains("initial")) {
          status = HttpResponseStatus.REQUEST_URI_TOO_LONG;        // 414
        } else if (msg.contains("header")) {
          status = HttpResponseStatus.REQUEST_HEADER_FIELDS_TOO_LARGE; // 431
        } else {
          status = HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE;     // 413
        }
      } else {
        DEBUG.downstreamError(ctx.channel(), "request error, returning status " + status, cause);
      }
      completeWithError(ctx.channel(), status);
    } else {
      // this can be a connection reset while waiting or sending the response
      DEBUG.downstreamError(ctx.channel(), "processing error", cause);
      if (upstreamChannelFuture.isDone()) {
        upstreamChannelFuture.resultNow().close();
      }
      DownstreamProgress.complete(ctx.channel());
      ctx.channel().close();
    }
  }

}
