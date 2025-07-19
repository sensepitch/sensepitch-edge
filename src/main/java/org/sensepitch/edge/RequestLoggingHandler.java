package org.sensepitch.edge;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.concurrent.Ticker;

/**
 * Listens to incoming and outgoing http messages and collects all relevant information
 * during request processing and calls the request logger implementation when the last
 * content is written.
 *
 * <p>Note on concurrency: there is no concurrent activity on this object.
 * The downstream request comes from one thread, after submitting the upstream request,
 * writes come from the upstream reading thread
 *
 * @author Jens Wilke
 */
public class RequestLoggingHandler extends ChannelDuplexHandler implements RequestLogInfo {

  static ProxyLogger DEBUG = ProxyLogger.get(RequestLoggingHandler.class);

  private long contentBytes = 0;
  private HttpRequest request;
  private HttpResponse response;
  private long requestStartTime;
  private final RequestLogger logger;
  private Channel channel;
  private Throwable error;
  private HttpHeaders trailingHeaders;
  private int requestCount;
  private Ticker ticker;
  private long requestStartTimeNanos;
  private long requestCompleteTimeNanos;
  private long responseStartedTimeNanos;
  private long responseReceivedTimeNanos;

  public RequestLoggingHandler(RequestLogger logger) {
    this.logger = logger;
  }

  @Override
  public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
    ticker = ctx.executor().ticker();
    super.channelRegistered(ctx);
  }

  @Override
  public void channelActive(ChannelHandlerContext ctx) throws Exception {
    requestStartTime = System.currentTimeMillis();
    requestStartTimeNanos = ticker.nanoTime();
  }

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception{
    if (msg instanceof HttpRequest) {
      request = (HttpRequest) msg;
      contentBytes = 0;
      requestStartTime = System.currentTimeMillis();
      requestStartTimeNanos = ticker.nanoTime();
      requestCompleteTimeNanos = responseStartedTimeNanos = 0;
    }
    if (msg instanceof LastHttpContent) {
      requestCompleteTimeNanos = System.currentTimeMillis();
    }
    super.channelRead(ctx, msg);
  }

  @Override
  public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
    if (!ctx.channel().isWritable() && responseReceivedTimeNanos == 0) {
      responseStartedTimeNanos = ticker.nanoTime();
    }
    super.channelWritabilityChanged(ctx);
  }

  @Override
  public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
    if (msg instanceof HttpResponse) {
      response = (HttpResponse) msg;
    }
    if (request == null) {
      request = constructMockHttpRequest(ctx);
    }
    // HttpResponse may have content as well
    if (msg instanceof HttpContent httpContent) {
      contentBytes += httpContent.content().readableBytes();
    }
    if (msg instanceof LastHttpContent lastHttpContent) {
      if (responseStartedTimeNanos == 0) {
        responseStartedTimeNanos = ticker.nanoTime();
      }
      trailingHeaders = lastHttpContent.trailingHeaders();
      channel = ctx.channel();
      promise.addListener(future -> {
          responseReceivedTimeNanos = ticker.nanoTime();
          error = future.cause();
          try {
            logger.logRequest(this);
            requestCount++;
          } catch (Throwable e) {
            DEBUG.error(ctx.channel(), "Error logging request", e);
          }
        }
      );
    }
    super.write(ctx, msg, promise);
  }

  /**
   * Construct a mock http request in case we don't have a request, which can happen if
   * the request was malformed or receive timed out. We don't use a HttpRequest singleton,
   * maybe we want to add headers, like set the host, if its known.
   */
  private HttpRequest constructMockHttpRequest(ChannelHandlerContext ctx) {
    HttpRequest request = new DefaultFullHttpRequest(NIL_VERSION, NIL_METHOD, "/");
    return request;
  }

  @Override public String requestId() { return LogTarget.localChannelId(channel) + "/" + requestCount; }
  @Override public Channel channel() { return channel; }
  @Override  public Throwable error() { return error; }
  @Override public HttpRequest request() { return request; }
  @Override public HttpResponse response() { return response; }
  @Override public HttpHeaders trailingHeaders() { return trailingHeaders; }
  @Override public long contentBytes() { return contentBytes; }
  @Override public long requestStartTimeMillis() { return requestStartTime; }
  @Override public long responseReceivedTimeNanos() { return responseReceivedTimeNanos; }
  @Override public long responseStartedTimeNanos() { return responseStartedTimeNanos; }
  @Override public long requestCompleteTimeNanos() { return requestCompleteTimeNanos; }
  @Override public long requestStartTimeNanos() { return requestStartTimeNanos; }

}
