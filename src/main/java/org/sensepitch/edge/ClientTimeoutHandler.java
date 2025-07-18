package org.sensepitch.edge;

import io.netty.channel.ChannelException;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;

import java.io.IOException;

/**
 * Implements timeouts for client connection. Also adds keep alive timeout and adds
 * keep alive header with correct timeout time. The implementation is modifying the
 * pipeline according to the connection state and which timeout applies at the moment.
 *
 * This must be places between the http codec handler and the keep alive handler.
 *
 * @author Jens Wilke
 */
public class ClientTimeoutHandler extends ReadTimeoutHandler {

  static ProxyLogger LOG = ProxyLogger.get(ClientTimeoutHandler.class);

  private final ConnectionConfig config;

  public ClientTimeoutHandler(ConnectionConfig config) {
    super(config.readTimeoutSeconds());
    this.config = config;
  }

  @Override
  public void channelActive(ChannelHandlerContext ctx) throws Exception {
    LOG.trace(ctx.channel(), "Channel is active");
    super.channelActive(ctx);
  }

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
    if (msg instanceof LastHttpContent) {
      ctx.pipeline().replace(this, "waitForUpstreamContentTimeout",
        new WaitForUpstreamContentHandler(config));
    }
    super.channelRead(ctx, msg);
  }

  /**
   * Wait until we see the response written. Add keep alive timeout header and switch to write timeout.
   */
  static class WaitForUpstreamContentHandler extends WriteTimeoutHandler {
    private final ConnectionConfig config;
    private boolean closed;

    public WaitForUpstreamContentHandler(ConnectionConfig config) {
      super(config.responseTimeoutSeconds());
      this.config = config;
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
      boolean keepAlive = false;
      if (msg instanceof HttpResponse response) {
        if (HttpUtil.isKeepAlive(response)) {
          response.headers().set(HttpHeaderNames.KEEP_ALIVE, "timeout=" + config.keepAliveSeconds() + ", max=123");
          keepAlive = true;
        }
        SpecialWriteTimeoutHandler newHandler = new SpecialWriteTimeoutHandler(config, keepAlive);
        ctx.pipeline().replace(this, "writeTimeout", newHandler);
        // TODO: the context does not contain the correct new handler.
        newHandler.write(ctx, msg, promise);
        return;
      }
      super.write(ctx, msg, promise);
    }

    @Override
    protected void writeTimedOut(ChannelHandlerContext ctx) throws Exception {
      if (!closed) {
        ctx.fireExceptionCaught(new UpstreamResponseTimeoutException());
        ctx.close();
        closed = true;
      }
    }
  }

  static class SpecialWriteTimeoutHandler extends WriteTimeoutHandler {

    private final ConnectionConfig config;
    private final boolean keepAlive;

    public SpecialWriteTimeoutHandler(ConnectionConfig config, boolean keepAlive) {
      super(config.writeTimeoutSeconds());
      this.config = config;
      this.keepAlive = keepAlive;
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
      if (msg instanceof LastHttpContent) {
        promise.unvoid().addListener((ChannelFutureListener) future -> {
          if (future.isSuccess()) {
            if (keepAlive) {
              ctx.pipeline().replace(this, "keepAliveReadTimeout", new KeepAliveTimeoutHandler(config));
            }
          }
        });
      }
      super.write(ctx, msg, promise);
    }

  }

  static class KeepAliveTimeoutHandler extends ReadTimeoutHandler {
    private final ConnectionConfig config;
    private boolean closed;

    public KeepAliveTimeoutHandler(ConnectionConfig config) {
      super(config.keepAliveSeconds());
      this.config = config;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
      ctx.pipeline().replace(this, "clientTimeoutOngoing", new ClientTimeoutHandler(config));
      super.channelRead(ctx, msg);
    }

    @Override
    protected void readTimedOut(ChannelHandlerContext ctx) throws Exception {
      if (!closed) {
        ctx.fireExceptionCaught(new KeepAliveTimeoutException());
        ctx.close();
        closed = true;
      }
    }
  }

  public static class UpstreamResponseTimeoutException extends ChannelException { }

  public static class KeepAliveTimeoutException extends ChannelException { }

}
