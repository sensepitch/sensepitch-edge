package org.sensepitch.edge;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.LastHttpContent;

/**
 * @author Jens Wilke
 */
public class DownstreamHandler extends ChannelInboundHandlerAdapter {

  static final ProxyLogger DEBUG = ProxyLogger.get(DownstreamHandler.class);

  private final UpstreamRouter upstreamRouter;
  private ChannelFuture upstreamFuture;

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
        DEBUG.trace(ctx.channel(), "DownstreamHandler: " + msg.getClass().getSimpleName() + " " + clientIP + " -> " + request.method() + " " + request.uri());
      }
      boolean hasBodyOrIsNoGet = HttpUtil.isContentLengthSet(request) || HttpUtil.isTransferEncodingChunked(request) || !request.method().name().equals("GET");
      // TODO: pooled connections need more work and interpret keep alive correctly
      boolean doNotPool = true;
      Upstream upstream = upstreamRouter.selectUpstream(ctx, request);
      if (doNotPool || hasBodyOrIsNoGet) {
        requestWithBody(upstream, ctx, request);
      } else {
        upstream.sendPooledRequest(ctx, request);
      }
    }
    if (msg instanceof LastHttpContent) {
      upstreamFuture.addListener(
        new ChannelFutureListener() {
          @Override
          public void operationComplete(ChannelFuture future) throws Exception {
            if (future.isSuccess()) {
              future.channel().writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture future) throws Exception {
                  DEBUG.trace(ctx.channel(), future.channel(), "upstream request sent and flushed, unpooled");
                }
              });
            }
          }
        }
      );
    } if (msg instanceof HttpContent) {
      upstreamFuture.channel().write(msg);
    }
  }

  /**
   * For requests with body, always establish a new upstream connection. We can start writing to the channel
   * even before the connection is established.
   */
  public void requestWithBody(Upstream upstream, ChannelHandlerContext ctx, HttpRequest request) {
    upstreamFuture = upstream.connect(ctx);
    boolean contentExpected = (HttpUtil.isContentLengthSet(request) || HttpUtil.isTransferEncodingChunked(request)) && !(request instanceof FullHttpRequest);
    // HttpCodec will always sent HttpLastContent, however, when we send this upstream the connection might be already
    // closed. Ignore any HttpContent messages and don't send them upstream.
    if (contentExpected) {
      // turn of reading until the upstream connection is established to avoid overflowing
      ctx.channel().config().setAutoRead(false);
      // ctx.channel().pipeline().replace(this, "content", new DownstreamContentHandler(f.channel(), this));
    }
    DEBUG.trace(ctx.channel(), "connecting to upstream contentExpected=" + contentExpected);
    request.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
    request.headers().set("X-Forwarded-For", ctx.channel().remoteAddress().toString());
    request.headers().set("X-Forwarded-Proto", "https");
    // request.headers().set("X-Forwarded-Port", );
    upstreamFuture.channel().write(request);
    if (contentExpected) {
      upstreamFuture.addListener((ChannelFutureListener) future -> {
        if (future.isSuccess()) {
          ctx.channel().config().setAutoRead(true);
        } else {
          ctx.close();
        }
      });
    }
  }

}
