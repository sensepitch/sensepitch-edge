package org.sensepitch.edge;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.LastHttpContent;

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
  private final RequestLogger logger = new StandardOutRequestLogger();

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception{
    if (msg instanceof HttpRequest) {
      request = (HttpRequest) msg;
      contentBytes = 0;
      requestStartTime = System.currentTimeMillis();
    }
    super.channelRead(ctx, msg);
  }

  @Override
  public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
    if (msg instanceof HttpResponse) {
      response = (HttpResponse) msg;
    }
    // HttpResponse may have content as well
    if (msg instanceof HttpContent httpContent) {
      contentBytes += httpContent.content().readableBytes();
    }
    if (msg instanceof LastHttpContent) {
      try {
        logger.logRequest(ctx, this);
      } catch (Throwable e) {
        DEBUG.error(ctx.channel(), "Error logging request", e);
      }
    }
    super.write(ctx, msg, promise);
  }

  @Override public HttpRequest request() { return request; }
  @Override public HttpResponse response() { return response; }
  @Override public long contentBytes() { return contentBytes; }
  @Override public long requestStartTime() { return requestStartTime; }
}
