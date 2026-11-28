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
import io.netty.handler.codec.http.HttpMethod;
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
import java.util.Set;

/// Sends a request upstream and forwards the response to the client. Informational responses (1xx)
/// are forwarded before the final response; only the `LastHttpContent` of that final response ends
/// the exchange and releases the upstream connection.
///
/// After a clean exchange the connection is released as reusable, so a pooling [Upstream] keeps it
/// for the next exchange, see [DefaultUpstream]: the request was forwarded completely and the
/// final response allows keep-alive. Every other end closes it. Hop-by-hop headers are removed in
/// both directions, see [#stripHopByHopHeaders], so a client's `Connection: close` does not end a
/// pooled connection.
///
/// A connection taken from a pool may have been closed by the upstream just as the request was
/// sent (RFC 9112, section 9.5). If it fails before any response arrived, and the request is
/// idempotent and has no body, it is sent once more on a new connection, see
/// [#retryOnNewConnection]; RFC 9110, section 9.2.2 allows this for idempotent requests only.
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

  /// Methods whose request may be sent again after a connection failure, RFC 9110, section 9.2.2
  private static final Set<HttpMethod> IDEMPOTENT_METHODS =
      Set.of(
          HttpMethod.GET,
          HttpMethod.HEAD,
          HttpMethod.OPTIONS,
          HttpMethod.TRACE,
          HttpMethod.PUT,
          HttpMethod.DELETE);

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

  /// The `LastHttpContent` of the HTTP request was written upstream, a condition for reusing the
  /// connection
  private boolean requestForwarded;

  /// The upstream allows keep-alive so far: no `101`, the final response is checked when it is
  /// written, see [#write]
  private boolean upstreamKeepAlive;

  /// The HTTP request of the active exchange while a retry on a new connection is still possible:
  /// idempotent, without a body, and no response arrived yet, see [#retryOnNewConnection].
  /// `null` otherwise.
  private HttpRequest retryableRequest;

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
      requestForwarded = false;
      upstreamKeepAlive = true;
      retryableRequest = retryable(request) ? request : null;
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

  /// Called by the listener of the upstream connect, on the event loop of the client channel, see
  /// [Upstream#connect]. The listener of the write runs on the event loop of the upstream channel.
  /// [DefaultUpstream] opens it on the client's loop, so a failed exchange ends at once; an
  /// upstream on another loop hands the failure over to the client's loop.
  void forwardLastContentAndFlush(
      ChannelHandlerContext ctx, Future<Channel> future, LastHttpContent msg) {
    if (future.isSuccess()) {
      if (upstreamChannelFuture == future) {
        requestForwarded = true;
      }
      future
          .resultNow()
          .writeAndFlush(msg)
          .addListener(
              (ChannelFutureListener)
                  f -> {
                    if (!f.isSuccess()) {
                      Runnable fail =
                          () -> {
                            if (!retryOnNewConnection(ctx, future)) {
                              failExchange(
                                  ctx,
                                  future,
                                  f.cause(),
                                  RequestOutcome.UPSTREAM_WRITE_FAILED,
                                  HttpResponseStatus.valueOf(502, "Upstream write problem"));
                            }
                          };
                      if (ctx.executor().inEventLoop()) {
                        fail.run();
                      } else {
                        ctx.executor().execute(fail);
                      }
                    }
                  });
    } else {
      // upstream future listener is called within ingress event loop
      assert ctx.executor().inEventLoop();
      ReferenceCountUtil.release(msg);
      if (future.cause() instanceof Upstream.ConnectionLimitException) {
        failExchange(
            ctx,
            future,
            future.cause(),
            RequestOutcome.UPSTREAM_CONNECTION_LIMIT,
            HttpResponseStatus.valueOf(503, "Upstream connection limit"));
        return;
      }
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
    assert ctx.executor().inEventLoop() : "exchange state changed off the client's event loop";
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

  /// Called by the listener of the upstream connect, on the event loop of the client channel. Uses
  /// no state of this handler, so the upstream channel may run on another loop.
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
    assert ctx.executor().inEventLoop() : "exchange state changed off the client's event loop";
    dropUpstream(false);
    if (responseStarted) {
      responseStarted = false;
      ctx.channel().close();
      return;
    }
    FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status);
    response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
    ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
  }

  /// Ends the use of the upstream of the active exchange and releases it. The reference is cleared
  /// before the release, so the [ForwardHandler] report of a close, which may arrive at once since
  /// both channels share an event loop, belongs to no exchange, see [#userEventTriggered].
  ///
  /// @param reusable the exchange ended cleanly, see the class doc; `false` closes the connection
  private void dropUpstream(boolean reusable) {
    Future<Channel> exchangeUpstream = upstreamChannelFuture;
    if (exchangeUpstream != null) {
      upstreamChannelFuture = null;
      retryableRequest = null;
      releaseUpstream(exchangeUpstream, reusable);
    }
  }

  /// Releases the upstream connection of an exchange once it is established; one that is still
  /// connecting is closed when the connect completes, nobody takes it
  private void releaseUpstream(Future<Channel> exchangeUpstream, boolean reusable) {
    exchangeUpstream.addListener(
        (FutureListener<Channel>)
            future -> {
              if (future.isSuccess()) {
                upstream.release(future.resultNow(), reusable);
              }
            });
  }

  /// Whether the HTTP request may be sent once more after its reused connection failed: an
  /// idempotent method (RFC 9110, section 9.2.2) and no body. A message with content is excluded,
  /// its buffer is released by the first write.
  private static boolean retryable(HttpRequest request) {
    return IDEMPOTENT_METHODS.contains(request.method())
        && !(request instanceof HttpContent)
        && !HttpUtil.isTransferEncodingChunked(request)
        && HttpUtil.getContentLength(request, 0) == 0;
  }

  /// Sends the HTTP request of the active exchange once more on a new connection, if its upstream
  /// connection `failed` was taken from a pool and the request is retryable, see the class doc.
  /// The new connection is never retried, it is not [Upstream#reused].
  ///
  /// @return `true` if the request was sent again, the failure then ends nothing
  private boolean retryOnNewConnection(ChannelHandlerContext ctx, Future<Channel> failed) {
    HttpRequest request = retryableRequest;
    // every caller passes a connected future
    if (request == null
        || upstreamChannelFuture != failed
        || !upstream.reused(failed.resultNow())) {
      return false;
    }
    retryableRequest = null;
    requestForwarded = false;
    metrics.upstreamRetries.labelValues(ProxyMetrics.upstreamLabel(upstream)).inc();
    // replaced before the old connection is closed, so its close belongs to no exchange
    upstreamChannelFuture = upstream.connectNew(ctx);
    upstream.release(failed.resultNow(), false);
    Future<Channel> retry = upstreamChannelFuture;
    retry.addListener(
        (FutureListener<Channel>)
            future -> {
              if (future.isSuccess()) {
                future.resultNow().write(request);
              }
              forwardLastContentAndFlush(ctx, future, LastHttpContent.EMPTY_LAST_CONTENT);
            });
    return true;
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
    stripHopByHopHeaders(request.headers());
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

  /// When the client connection closes during an exchange, the upstream connection is closed at
  /// once, also while it is still connecting: nobody takes the rest of the response, and a
  /// connection in the middle of a response cannot go back to a pool. Without this, the end of the response would never reach
  /// [#write], since Netty removes the handlers of a closed channel, and the upstream connection
  /// would stay open until the upstream closes it.
  ///
  /// A client that closes during an exchange is no fault of ours and gets no diagnostic line: the
  /// request log records the request as [RequestOutcome#CLIENT_ABORTED], and
  /// `ingressRequestsAborted` counts it, see [ProxyLogger] "Levels".
  @Override
  public void channelInactive(ChannelHandlerContext ctx) throws Exception {
    if (upstreamChannelFuture != null) {
      dropUpstream(false);
    }
    super.channelInactive(ctx);
  }

  /// Phase of the exchange for the log of an unexpected request, named like the phases of the
  /// [ExceptionHandler]. Never `upload`: the HTTP codec delivers a new request only after the last
  /// content of the previous one.
  private String exchangePhase() {
    return responseStarted ? "responding" : "waiting";
  }

  /// Remove upstream reference when the exchange is complete. The upstream channel is released, so
  /// we need to ensure that we don't have it anymore for throttling. Throttling can only occur in
  /// response to a write, so we are sure that there is no pending throttling.
  ///
  /// @see #channelWritabilityChanged(ChannelHandlerContext)
  @Override
  public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
      throws Exception {
    if (msg instanceof HttpResponse response) {
      HttpHeaders headers = response.headers();
      if (upstreamChannelFuture != null) {
        // a response arrived, sending the request again could repeat it
        retryableRequest = null;
        if (response.status().code() == HttpResponseStatus.SWITCHING_PROTOCOLS.code()
            || response.status().codeClass() != HttpStatusClass.INFORMATIONAL
                && !HttpUtil.isKeepAlive(response)) {
          upstreamKeepAlive = false;
        }
      }
      stripHopByHopHeaders(headers);
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
        // the reference is cleared here to ensure no more throttling is done
        dropUpstream(requestForwarded && upstreamKeepAlive);
        responseStarted = false;
      }
    }
    super.write(ctx, msg, promise);
  }

  /// Removes the hop-by-hop headers (RFC 9110, section 7.6.1), of a response before it goes to the
  /// client and of an HTTP request before it goes upstream: the options of one connection do not
  /// apply to the other, e.g. a client's `Connection: close` must not end a pooled upstream
  /// connection. Transfer-Encoding stays so the HTTP codec of the other side can frame the decoded
  /// content as chunks.
  static void stripHopByHopHeaders(HttpHeaders headers) {
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
    headers.remove(LegacyHeaderNames.KEEP_ALIVE);
    headers.remove(LegacyHeaderNames.PROXY_CONNECTION);
    headers.remove(HttpHeaderNames.TE);
    headers.remove(HttpHeaderNames.TRAILER);
    headers.remove(HttpHeaderNames.UPGRADE);
  }

  /// Drops the upstream of the active exchange, the [ExceptionHandler] further down ends it. An
  /// exception of a reused upstream connection before the response, e.g. a reset, sends a
  /// retryable request once more instead, see [#retryOnNewConnection].
  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
    if (cause instanceof ForwardHandler.UpstreamException
        && retryOnNewConnection(ctx, upstreamChannelFuture)) {
      return;
    }
    dropUpstream(false);
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
    if (retryOnNewConnection(ctx, upstreamChannelFuture)) {
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
