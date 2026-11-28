package org.sensepitch.edge;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelException;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpStatusClass;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/// Watches the request and response pass through and implement timeouts for the request, response
/// and write. There is only one timeout active at a point in time. The response timeout is actually
/// a timeout for our upstream. Once the upstream starts responding the handler switches to write
/// timeout. The write timeout triggers when no write complete any more, which covers upstream and
/// receiver stalls.
///
/// It also manages keep-alive, in place of Netty's `HttpServerKeepAliveHandler`, because the
/// keep-alive decision and the idle read timeout depend on each other. The connection is kept open
/// after a response only if all of these hold, otherwise the response gets `Connection: close` and
/// the connection is closed after it:
/// - the request allows it: HTTP/1.1 without `Connection: close`, or HTTP/1.0 with
///   `Connection: keep-alive`
/// - a request with a body (`Content-Length` above 0, or chunked) was received completely before
///   the response started. A response that starts while the client is still sending the body,
///   e.g. an early 413 of the upstream, leaves the rest of the body unread; not reusing the
///   connection is the simple way to stay in sync with the client. A request without a body is
///   complete with its head: handlers that answer on the head, e.g. a fixed response, write
///   before the codec passes on the empty `LastHttpContent` that follows.
/// - the proxy is not shutting down, see [#userEventTriggered]
/// - the response allows it: no `Connection: close`, and its end is defined by the message itself
///   (`Content-Length`, chunked, `multipart`, or 204), the same rule as Netty 4.2's
///   `HttpServerKeepAliveHandler`
///
/// After such an early response the connection is closed only when the rest of the body was read,
/// or when the client sends nothing for the read timeout, counted with phase `after_response`.
/// Closing a socket with unread received data makes the TCP stack send a reset, which may discard
/// the response at the client before it was read (what NGINX's `lingering_close` avoids).
///
/// The idle read timeout is moved by every part of the request, so it does not bound a body that
/// is sent slowly. For a request with a body there is a second, fixed deadline next to it,
/// `requestTimeoutSeconds` from the complete head (see [ConnectionConfig]). When the body is not
/// complete by then and no final response has started, the client gets a 408 and the connection is
/// closed, counted with phase `request_total`. A response, including an early one, ends it: from
/// then on the timeouts of the response apply.
///
/// Informational responses (1xx, e.g. `100 Continue`) pass through, the final response follows.
/// HTTP pipelining is not supported, [DownstreamHandler] rejects a request during an exchange.
///
/// Place it after the HTTP codec, and after handlers that may answer a request themselves, so it
/// sees every response.
///
/// @author Jens Wilke
public final class IngressTimeoutHandler extends ChannelDuplexHandler {

  private static final ProxyLogger LOG = ProxyLogger.get(IngressTimeoutHandler.class);

  /// Content type prefix of a multipart response, which delimits its own end
  private static final String MULTIPART_PREFIX = "multipart";

  private static final Consumer<Channel> IGNORE_SHUTDOWN = channel -> {};

  private final ConnectionConfig config;
  private final Consumer<Channel> shutdownDelivered;
  private boolean responding = false;
  private TimeoutTask task;

  /// The fixed deadline to receive the body of the current request, or `null`, see the class doc
  private TimeoutTask requestDeadline;

  private final ProxyMetrics proxyMetrics;

  /// The connection stays open after the current response, decided when the response starts
  private boolean keepAlive;

  /// The current request allows keep-alive, see [HttpUtil#isKeepAlive]
  private boolean requestKeepAlive;

  /// The client is still sending the body of the request, see the class doc
  private boolean receiving;

  /// The response ended before the request body, the connection is closed after the body, see
  /// the class doc
  private boolean closeAfterRequest;

  /// The response being written is informational (1xx), the final response follows. Set by every
  /// response head, so a 1xx without its `LastHttpContent` cannot hide the final response
  private boolean informational;

  /// No exchange in progress: before the first HTTP request, and after a response on a
  /// keep-alive connection
  private boolean idle = true;

  /// The proxy shuts down, the connection closes after the current response
  private boolean closing;

  public IngressTimeoutHandler(ConnectionConfig config, ProxyMetrics proxyMetrics) {
    this(config, proxyMetrics, IGNORE_SHUTDOWN);
  }

  /// @param shutdownDelivered fired on the connection's event loop when the shutdown drain
  ///   reached it, after `closing` was set. A test uses this to let an upstream answer only once
  ///   the response will carry `Connection: close`.
  IngressTimeoutHandler(
      ConnectionConfig config, ProxyMetrics proxyMetrics, Consumer<Channel> shutdownDelivered) {
    this.config = config;
    this.proxyMetrics = proxyMetrics;
    this.shutdownDelivered = shutdownDelivered;
  }

  @Override
  public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
    initialize(ctx);
    super.handlerAdded(ctx);
  }

  @Override
  public void channelActive(ChannelHandlerContext ctx) throws Exception {
    initialize(ctx);
    super.channelActive(ctx);
  }

  @Override
  public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
    initialize(ctx);
    super.channelRegistered(ctx);
  }

  private void initialize(ChannelHandlerContext ctx) throws Exception {
    if (task != null) {
      return;
    }
    task =
        new TimeoutTask(ctx, config.readTimeoutSeconds()) {
          @Override
          public void fire() {
            answerRequestTimeout(ctx, "first_request");
          }
        };
  }

  /// Counts a receive timeout of the phase, then answers 408 and closes the connection. Used by
  /// the timeouts that end an exchange whose HTTP request the client did not send in time.
  private void answerRequestTimeout(ChannelHandlerContext ctx, String phase) {
    proxyMetrics.countReceiveTimeout(ctx, phase);
    RequestLoggingHandler.reportOutcome(ctx, RequestOutcome.REQUEST_TIMEOUT);
    FullHttpResponse response =
        new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.REQUEST_TIMEOUT);
    response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
    ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
  }

  @Override
  public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
    task.cancel();
    cancelRequestDeadline();
    super.handlerRemoved(ctx);
  }

  @Override
  public void channelInactive(ChannelHandlerContext ctx) throws Exception {
    task.cancel();
    cancelRequestDeadline();
    super.channelInactive(ctx);
  }

  /// On shutdown an idle connection is closed at once, a busy one after its response, see
  /// [TrackIngressConnectionsHandler#drain()]
  @Override
  public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
    if (evt != TrackIngressConnectionsHandler.ShutdownEvent.SHUTDOWN) {
      super.userEventTriggered(ctx, evt);
      return;
    }
    closing = true;
    shutdownDelivered.accept(ctx.channel());
    if (idle) {
      ctx.close();
    }
  }

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
    if (msg instanceof HttpRequest request) {
      idle = false;
      responding = false;
      receiving = hasBody(request);
      requestKeepAlive = HttpUtil.isKeepAlive(request);
      startRequestDeadline(ctx);
    }
    if (msg instanceof LastHttpContent) {
      receiving = false;
      cancelRequestDeadline();
      if (closeAfterRequest) {
        // DownstreamHandler drops the rest of the body of an ended exchange
        super.channelRead(ctx, msg);
        ctx.close();
        return;
      }
    }
    if (!responding && msg instanceof LastHttpContent) {
      task.cancel();
      task =
          new TimeoutTask(ctx, config.responseTimeoutSeconds()) {
            @Override
            public void fire() {
              RequestLoggingHandler.reportOutcome(ctx, RequestOutcome.UPSTREAM_TIMEOUT);
              // The exception closes the upstream; ExceptionHandler sends and logs the 504.
              ctx.fireExceptionCaught(new UpstreamResponseTimeoutException());
            }
          };
    } else {
      task.touch();
    }
    super.channelRead(ctx, msg);
  }

  /// Starts the fixed deadline for the body of a request that announces one, if configured, see the
  /// class doc. A deadline left from an earlier request is cancelled first.
  private void startRequestDeadline(ChannelHandlerContext ctx) {
    cancelRequestDeadline();
    if (!receiving || config.requestTimeoutSeconds() == 0) {
      return;
    }
    requestDeadline =
        new TimeoutTask(ctx, config.requestTimeoutSeconds()) {
          @Override
          public void fire() {
            answerRequestTimeout(ctx, "request_total");
          }
        };
  }

  /// Ends the deadline of [#startRequestDeadline], when the body is complete, the response starts
  /// or the connection ends
  private void cancelRequestDeadline() {
    if (requestDeadline != null) {
      requestDeadline.cancel();
      requestDeadline = null;
    }
  }

  @Override
  public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
      throws Exception {
    if (msg instanceof HttpResponse response) {
      informational = isInformational(response);
    }
    if (informational) {
      // a 1xx and its content, the final response follows, nothing to decide or time yet
      super.write(ctx, msg, promise);
      return;
    }
    if (!responding) {
      responding = true;
      var response = (HttpResponse) msg;
      keepAlive =
          requestKeepAlive
              && !receiving
              // shutting down: the client opens a new connection, which the next instance serves
              && !closing
              && HttpUtil.isKeepAlive(response)
              && isSelfDefinedMessageLength(response);
      if (!keepAlive) {
        HttpUtil.setKeepAlive(response, false);
      } else {
        // Keep-Alive header values is not specified in HTTP/1.1, however, it is a good practice
        // to avoid the client from sending a new requests while the server closes the connection
        // also server should send a 408 response before closing the connection
        // https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Keep-Alive
        response
            .headers()
            .set(
                LegacyHeaderNames.KEEP_ALIVE,
                "timeout=" + config.readTimeoutSeconds() + ", max=123");
        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
      }
      cancelRequestDeadline();
      task.cancel();
      task =
          new TimeoutTask(ctx, config.writeTimeoutSeconds()) {
            @Override
            public void fire() {
              RequestLoggingHandler.reportOutcome(ctx, RequestOutcome.RESPONSE_WRITE_TIMEOUT);
              ctx.fireExceptionCaught(new WriteTimeoutException());
            }
          };
    }
    promise =
        promise
            .unvoid()
            .addListener(
                future -> {
                  task.touch();
                  if (!(msg instanceof LastHttpContent)) {
                    return;
                  }
                  if (receiving) {
                    // an early response, keepAlive is false
                    closeAfterRequest = true;
                    task.cancel();
                    task =
                        new TimeoutTask(ctx, config.readTimeoutSeconds()) {
                          @Override
                          public void fire() {
                            proxyMetrics.countReceiveTimeout(ctx, "after_response");
                            ctx.close();
                          }
                        };
                    return;
                  }
                  if (!keepAlive || closing) {
                    // closing: the shutdown started after the response headers were written
                    ctx.close();
                    return;
                  }
                  idle = true;
                  task.cancel();
                  task =
                      new TimeoutTask(ctx, config.readTimeoutSeconds()) {
                        @Override
                        public void fire() {
                          answerRequestTimeout(ctx, "keep_alive");
                        }
                      };
                });
    super.write(ctx, msg, promise);
  }

  /// The request announces a body, so the client may still be sending when the response starts
  private static boolean hasBody(HttpRequest request) {
    return HttpUtil.isTransferEncodingChunked(request)
        || HttpUtil.getContentLength(request, 0L) > 0;
  }

  /// A 1xx response, which precedes the final response, see [#write]
  private static boolean isInformational(HttpResponse response) {
    return response.status().codeClass() == HttpStatusClass.INFORMATIONAL;
  }

  /// The client can tell the end of the response without the connection closing, the condition of
  /// keep-alive, see the class doc
  private static boolean isSelfDefinedMessageLength(HttpResponse response) {
    String contentType = response.headers().get(HttpHeaderNames.CONTENT_TYPE);
    return HttpUtil.isContentLengthSet(response)
        || HttpUtil.isTransferEncodingChunked(response)
        || (contentType != null
            && contentType.regionMatches(true, 0, MULTIPART_PREFIX, 0, MULTIPART_PREFIX.length()))
        || response.status().code() == HttpResponseStatus.NO_CONTENT.code();
  }

  private abstract static class TimeoutTask implements Runnable {
    private final ChannelHandlerContext ctx;
    private final long timeoutNs;
    private long lastTimeActiveNs;
    private ScheduledFuture<?> future;

    TimeoutTask(ChannelHandlerContext ctx, long timeoutSeconds) {
      this.ctx = ctx;
      this.timeoutNs = TimeUnit.SECONDS.toNanos(timeoutSeconds);
      lastTimeActiveNs = ctx.executor().ticker().nanoTime();
      schedule();
    }

    private void touch() {
      lastTimeActiveNs = ctx.executor().ticker().nanoTime();
    }

    private void cancel() {
      if (future != null) {
        future.cancel(false);
        future = null;
      }
    }

    private long delayNs() {
      long now = ctx.executor().ticker().nanoTime();
      long deadline = lastTimeActiveNs + timeoutNs;
      long delayNs = deadline - now;
      // long delaySeconds = TimeUnit.NANOSECONDS.toSeconds(delayNs);
      return delayNs;
    }

    private void schedule() {
      future = ctx.executor().schedule(this, delayNs(), TimeUnit.NANOSECONDS);
    }

    @Override
    public void run() {
      // Netty fires channelInactive in a later event-loop task than the close, so a due timer can
      // run after the close but before channelInactive cancels it.
      if (!ctx.channel().isOpen()) {
        return;
      }
      long delayNs = delayNs();
      if (delayNs <= 0L) {
        future = null;
        fire();
      } else {
        future = ctx.executor().schedule(this, delayNs, TimeUnit.NANOSECONDS);
      }
    }

    public abstract void fire();
  }

  public static class UpstreamResponseTimeoutException extends ChannelException {}

  public static class WriteTimeoutException extends ChannelException {}
}
