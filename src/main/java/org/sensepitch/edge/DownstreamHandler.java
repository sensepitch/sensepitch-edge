package org.sensepitch.edge;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpStatusClass;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.AsciiString;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;
import java.net.InetSocketAddress;

/// Sends a request upstream and forwards the response to the client. Informational responses (1xx)
/// are forwarded before the final response; only the `LastHttpContent` of that final response ends
/// the exchange and closes the upstream.
///
/// Every close of the upstream by the edge clears [#upstreamChannelFuture] first, so a close
/// reported afterwards by the [ForwardHandler] belongs to no active exchange and is ignored. A close
/// of the upstream connection of the active exchange came from the upstream: it is counted as
/// `closed_in_flight` and ends the exchange at once, see [#userEventTriggered].
///
/// @author Jens Wilke
public class DownstreamHandler extends ChannelDuplexHandler {

  private static final ProxyLogger LOG = ProxyLogger.get(DownstreamHandler.class);

  private static final AsciiString STRICT_TRANSPORT_SECURITY =
      AsciiString.cached("Strict-Transport-Security");
  private static final String DEFAULT_HSTS_POLICY = "max-age=31536000";

  private final Upstream upstream;
  private final ProxyMetrics metrics;

  /// The upstream connection of the active exchange, `null` when no exchange is active. A listener
  /// compares it with the future it was added to, to tell whether its exchange is still active.
  private Future<Channel> upstreamChannelFuture;

  private Runnable flushTask;

  /// The response headers of the active exchange were written to the client, so an error can no
  /// longer be answered with an error response, see [#completeWithError]
  private boolean responseStarted;

  /// An informational response was written, its `LastHttpContent` follows, then the final response.
  /// Its last content ends neither the exchange nor [responseStarted].
  private boolean informationalResponse;

  public DownstreamHandler(Upstream upstream, ProxyMetrics metrics) {
    this.upstream = upstream;
    this.metrics = metrics;
  }

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) {
    if (msg instanceof HttpRequest request) {
      // no pipelining, the HttpServerCodec delivers content only between a request and its last
      // content, so a new request while the exchange is active is the only unexpected case
      if (upstreamChannelFuture != null) {
        String phase = exchangePhase();
        LOG.incrementAndWarnOnce(
            metrics.ingressErrorCounter,
            new String[] {
              ProxyMetrics.ingress(ctx), phase, ProxyMetrics.IngressErrorType.ANOTHER_REQUEST.label
            },
            ctx.channel(),
            "another request is unexpected, phase=" + phase);
        ReferenceCountUtil.release(request);
        completeWithError(
            ctx,
            new HttpResponseStatus(
                HttpResponseStatus.BAD_REQUEST.code(), "another request is unexpected"));
        return;
      }
      RequestLoggingHandler requestLog = RequestLoggingHandler.find(ctx);
      if (requestLog != null) {
        requestLog.setUpstream(upstream);
      }
      responseStarted = false;
      informationalResponse = false;
      upstreamChannelFuture = upstream.connect(ctx);
      augmentHeadersAndForwardRequest(ctx, request);
    } else if (msg instanceof LastHttpContent) {
      // upstream might complete the response before the client sent the LastHttpContent request
      // e.g. for a get request that has no body
      if (upstreamChannelFuture == null) {
        ReferenceCountUtil.release(msg);
        return;
      }
      // The upstream channel might be still connecting.
      // Queue in all content we receive via the listener.
      upstreamChannelFuture.addListener(
          (FutureListener<Channel>)
              future -> {
                forwardLastContentAndFlush(ctx, future, (LastHttpContent) msg);
              });
    } else if (msg instanceof HttpContent) {
      if (upstreamChannelFuture == null) {
        ReferenceCountUtil.release(msg);
        return;
      }
      upstreamChannelFuture.addListener(
          (FutureListener<Channel>) future -> forwardContent(future, (HttpContent) msg));
    }
  }

  /// Called by the listener of the upstream connect. The upstream channel runs on the event loop
  /// of the client channel, see [Upstream#connect], so this and the listener of the write run on
  /// it as well and end a failed exchange at once.
  void forwardLastContentAndFlush(
      ChannelHandlerContext ctx, Future<Channel> future, LastHttpContent msg) {
    if (future.isSuccess()) {
      future
          .resultNow()
          .writeAndFlush(msg)
          .addListener(
              (ChannelFutureListener)
                  f -> {
                    if (!f.isSuccess()) {
                      assert ctx.executor().inEventLoop();
                      failExchange(
                          ctx,
                          future,
                          f.cause(),
                          RequestOutcome.UPSTREAM_WRITE_FAILED,
                          HttpResponseStatus.valueOf(502, "Upstream write problem"));
                    }
                  });
    } else {
      // upstream future listener is called within ingress event loop
      assert ctx.executor().inEventLoop();
      ReferenceCountUtil.release(msg);
      failExchange(
          ctx,
          future,
          future.cause(),
          RequestOutcome.UPSTREAM_CONNECT_FAILED,
          HttpResponseStatus.valueOf(502, "Upstream connection problem"));
    }
  }

  /// Ends the exchange of `exchangeUpstream` after an upstream failure that is reported
  /// asynchronously. The failure is ignored if the exchange is over already: the response was
  /// written completely, or another error ended it, e.g. a response timeout. Otherwise the report
  /// would change the request log of the next request on a keep-alive connection, or a second
  /// response would be sent.
  void failExchange(
      ChannelHandlerContext ctx,
      Future<Channel> exchangeUpstream,
      Throwable cause,
      RequestOutcome outcome,
      HttpResponseStatus status) {
    if (upstreamChannelFuture != exchangeUpstream) {
      LOG.trace(ctx.channel(), "upstream failure after the exchange ended: " + cause);
      return;
    }
    // no diagnostic line: the request log records the outcome and the cause
    RequestLoggingHandler requestLog = RequestLoggingHandler.find(ctx);
    if (requestLog != null) {
      requestLog.setException(cause);
    }
    RequestLoggingHandler.reportOutcome(ctx, outcome);
    completeWithError(ctx, status);
  }

  /// Called by the listener of the upstream connect, on the event loop of the client channel
  void forwardContent(Future<Channel> future, HttpContent msg) {
    if (future.isSuccess()) {
      future.resultNow().write(msg);
    } else {
      ReferenceCountUtil.release(msg);
      // ignore, only react to an upstream connection problem after receiving LastHttpContent
    }
  }

  /// Ends the active exchange after an error and closes the client connection. The upstream
  /// connection is dropped, so it sends nothing more to the client. If the response has not
  /// started, the client gets an error response with `status`. If it has started, the headers and
  /// maybe a part of the body are out, and a second response would corrupt the stream, so the
  /// connection is closed without one: the client sees a truncated response, which it can tell
  /// from a complete one by the content length or the missing last chunk.
  void completeWithError(ChannelHandlerContext ctx, HttpResponseStatus status) {
    dropUpstream();
    if (responseStarted) {
      responseStarted = false;
      ctx.channel().close();
      return;
    }
    FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status);
    response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
    ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
  }

  /// Ends the use of the upstream of the active exchange and closes it. The reference is cleared
  /// before the close, so the [ForwardHandler] report of this close, which may arrive at once since
  /// both channels share an event loop, belongs to no exchange, see [#userEventTriggered].
  private void dropUpstream() {
    Future<Channel> exchangeUpstream = upstreamChannelFuture;
    if (exchangeUpstream != null) {
      upstreamChannelFuture = null;
      closeUpstream(exchangeUpstream);
    }
  }

  /// Closes the upstream connection of an exchange once it is established, also when it is still
  /// connecting, since there is no connection pool to return it to
  private static void closeUpstream(Future<Channel> upstreamChannelFuture) {
    upstreamChannelFuture.addListener(
        (FutureListener<Channel>)
            future -> {
              if (future.isSuccess()) {
                future.resultNow().close();
              }
            });
  }

  /// Send the HTTP request, which may include content, upstream
  void augmentHeadersAndForwardRequest(ChannelHandlerContext ctx, HttpRequest request) {
    boolean contentExpected =
        (HttpUtil.isContentLengthSet(request) || HttpUtil.isTransferEncodingChunked(request))
            && !(request instanceof FullHttpRequest);
    if (contentExpected) {
      // turn off reading until the upstream connection is established to avoid overflowing
      ctx.channel().config().setAutoRead(false);
    }
    addProxyHeaders(ctx, request);
    upstreamChannelFuture.addListener(
        (FutureListener<Channel>)
            future -> {
              assert ctx.executor().inEventLoop();
              if (future.isSuccess()) {
                future.resultNow().write(request);
                if (contentExpected) {
                  ctx.channel().config().setAutoRead(true);
                }
              } else {
                ReferenceCountUtil.release(request);
                // ignore, only react to an upstream connection problem after receiving
                // LastHttpContent
              }
            });
  }

  /// Add standard minimal proxy request headers. We don't need to set X-Forwarded-Host, because
  /// this is already set in the Host header, also for https and SNI. We also don't include code
  /// here the support non-standard ports. If additional headers are needed, another handler can be
  /// added depending on configuration.
  private static void addProxyHeaders(ChannelHandlerContext ctx, HttpRequest request) {
    if (ctx.channel().remoteAddress() instanceof InetSocketAddress) {
      InetSocketAddress addr = (InetSocketAddress) ctx.channel().remoteAddress();
      request.headers().set("X-Forwarded-For", addr.getAddress().getHostAddress());
    }
    request.headers().set("X-Forwarded-Proto", "https");
  }

  /// Throttle reading, if the upstream is connected. If buffer is full send flush. If upstream is
  /// null, it means we received the last content, so no more flush is needed.
  @Override
  public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
    if (upstreamChannelFuture == null || !upstreamChannelFuture.isSuccess()) {
      return;
    }
    if (ctx.channel().isWritable()) {
      upstreamChannelFuture.resultNow().config().setAutoRead(true);
      return;
    }
    upstreamChannelFuture.resultNow().config().setAutoRead(false);
    if (flushTask == null) {
      flushTask =
          () ->
              ctx.channel()
                  .writeAndFlush(Unpooled.EMPTY_BUFFER)
                  .addListener(
                      (ChannelFutureListener)
                          future -> {
                            if (future.isSuccess() && ctx.channel().isActive()) {
                              if (!ctx.channel().isWritable()) {
                                ctx.executor().execute(flushTask);
                              }
                            }
                          });
    }
    ctx.executor().execute(flushTask);
  }

  /// When the client connection closes during an exchange, the upstream connection is dropped at
  /// once, also while it is still connecting: nobody takes the rest of the response, and there is
  /// no pool to return the connection to. Without this, the end of the response would never reach
  /// [#write], since Netty removes the handlers of a closed channel, and the upstream connection
  /// would stay open until the upstream closes it.
  ///
  /// A client that closes during an exchange is no fault of ours and gets no diagnostic line: the
  /// request log records the request as [RequestOutcome#CLIENT_ABORTED], and
  /// `ingressRequestsAborted` counts it, see [ProxyLogger] "Levels".
  @Override
  public void channelInactive(ChannelHandlerContext ctx) throws Exception {
    if (upstreamChannelFuture != null) {
      dropUpstream();
    }
    super.channelInactive(ctx);
  }

  /// Phase of the exchange for the log of an unexpected request, named like the phases of the
  /// [ExceptionHandler]. Never `upload`: the HTTP codec delivers a new request only after the last
  /// content of the previous one.
  private String exchangePhase() {
    return responseStarted ? "responding" : "waiting";
  }

  /// Remove upstream reference when processing for this request is complete. The upstream channel
  /// is closed, so we need to ensure that we don't have it anymore for throttling. Throttling can
  /// only occur in response to a write, so we are sure that there is no pending throttling.
  ///
  /// @see #channelWritabilityChanged(ChannelHandlerContext)
  @Override
  public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
      throws Exception {
    if (msg instanceof HttpResponse response) {
      HttpHeaders headers = response.headers();
      sanitizeResponseHeaders(headers);
      // forwarded and configured static responses, edge generated ones do not pass here
      if (upstreamChannelFuture != null && !headers.contains(STRICT_TRANSPORT_SECURITY)) {
        headers.set(STRICT_TRANSPORT_SECURITY, DEFAULT_HSTS_POLICY);
      }
      informationalResponse = response.status().codeClass() == HttpStatusClass.INFORMATIONAL;
      if (!informationalResponse) {
        responseStarted = true;
        // with an upstream attached the head is its response; an error response of the
        // ExceptionHandler passes here too, but only after the upstream was dropped
        if (upstreamChannelFuture != null) {
          RequestLoggingHandler.reportResponseSource(
              ctx, upstream.target() != null ? ResponseSource.UPSTREAM : ResponseSource.STATIC);
        }
      }
    }
    if (msg instanceof LastHttpContent) {
      boolean informational = informationalResponse;
      informationalResponse = false;
      if (!informational) {
        // there is no connection pool, so the upstream connection is closed after each response,
        // also if the upstream would keep it alive. The reference is cleared here to ensure no more
        // throttling is done.
        dropUpstream();
        responseStarted = false;
      }
    }
    super.write(ctx, msg, promise);
  }

  /// Keep upstream connection options out of the client connection. Transfer-Encoding stays so
  /// the downstream HTTP codec can frame the decoded content as chunks.
  private static void sanitizeResponseHeaders(HttpHeaders headers) {
    // Read all Connection fields before removing any: Connection can name itself or another field.
    for (String connection : headers.getAll(HttpHeaderNames.CONNECTION)) {
      for (String option : connection.split(",")) {
        String name = option.trim();
        if (!name.isEmpty()) {
          headers.remove(name);
        }
      }
    }
    headers.remove(HttpHeaderNames.CONNECTION);
    headers.remove(HttpHeaderNames.KEEP_ALIVE);
    headers.remove(HttpHeaderNames.PROXY_CONNECTION);
    headers.remove(HttpHeaderNames.TE);
    headers.remove(HttpHeaderNames.TRAILER);
    headers.remove(HttpHeaderNames.UPGRADE);
  }

  /// Drops the upstream of the active exchange, the [ExceptionHandler] further down ends it
  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
    dropUpstream();
    super.exceptionCaught(ctx, cause);
  }

  /// Ends the exchange when its upstream closed the connection before the response was complete:
  /// a `502` if the response has not started, else the client connection is closed, see
  /// [#completeWithError]. A close of an upstream the edge dropped itself, or of an exchange that
  /// is over, is ignored, see the class doc. The close is a request level event of the upstream,
  /// counted in [ProxyMetrics#upstreamErrorCounter] and recorded as
  /// [RequestOutcome#UPSTREAM_CLOSED]; no diagnostic line, see [ProxyLogger] "Levels".
  @Override
  public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
    if (!(evt instanceof ForwardHandler.UpstreamClosed closed)) {
      super.userEventTriggered(ctx, evt);
      return;
    }
    if (upstreamChannelFuture == null
        || !upstreamChannelFuture.isSuccess()
        || upstreamChannelFuture.resultNow() != closed.upstream()) {
      return;
    }
    metrics
        .upstreamErrorCounter
        .labelValues(
            ProxyMetrics.upstreamLabel(upstream),
            ProxyMetrics.UpstreamErrorType.CLOSED_IN_FLIGHT.label)
        .inc();
    RequestLoggingHandler.reportOutcome(ctx, RequestOutcome.UPSTREAM_CLOSED);
    completeWithError(ctx, HttpResponseStatus.valueOf(502, "Upstream closed"));
  }
}
