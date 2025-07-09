package org.sensepitch.edge;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.pool.ChannelHealthChecker;
import io.netty.channel.pool.ChannelPoolHandler;
import io.netty.channel.pool.FixedChannelPool;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;

/**
 * @author Jens Wilke
 */
public class Upstream {

  static ProxyLogger DEBUG = ProxyLogger.get(Upstream.class);

  final Bootstrap bootstrap;
  final FixedChannelPool pool;

  public Upstream(UpstreamConfig cfg) {
    String[] sa = cfg.target().split(":");
    int port = 80;
    String target = sa[0];
    if (sa.length > 2) { throw new IllegalArgumentException("Target: " + cfg.target()); }
    if (sa.length > 1) {
      port = Integer.parseInt(sa[1]);
    }
    bootstrap = new Bootstrap()
      .group(new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory()))
      .channel(NioSocketChannel.class)
      .option(ChannelOption.SO_KEEPALIVE, true)
      // NGINX test
      // .remoteAddress("172.24.0.2", 80)
      .remoteAddress(target, port);
    pool= new FixedChannelPool(bootstrap,
      new ChannelPoolHandler() {
        @Override
        public void channelReleased(Channel ch) throws Exception {

        }

        @Override
        public void channelAcquired(Channel ch) throws Exception {
          DEBUG.trace(null, ch, "channelAcquired, pipeline=" + ch.pipeline().names());
        }

        @Override
        public void channelCreated(Channel ch) throws Exception {
          addHttpHandler(ch.pipeline());
          ch.pipeline().addLast(new ForwardHandler(null));
        }
      },
      ChannelHealthChecker.ACTIVE,
      FixedChannelPool.AcquireTimeoutAction.FAIL,
      50,   // acquire timeout ms
      5000,     // max connections
      1,
      true
    );
  }

  void addHttpHandler(ChannelPipeline  pipeline) {
    pipeline.addLast(new ReportIoErrorsHandler("upstream"));
    pipeline.addLast(new HttpClientCodec());
  }

  public void sendPooledRequest(ChannelHandlerContext ctx, HttpRequest request) {
    pool.acquire().addListener(new FutureListener<Channel>() {
      @Override
      public void operationComplete(Future<Channel> future) throws Exception {
        if (future.isSuccess()) {
          Channel upstreamChannel = future.getNow();
          sendResponseDownstream(upstreamChannel, ctx);
          // set keep-alive for upstream
          FullHttpRequest req = new DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1, HttpMethod.GET, request.uri(), Unpooled.EMPTY_BUFFER);
          req.headers().set(HttpHeaderNames.HOST, request.headers().get(HttpHeaderNames.HOST));
          req.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
          upstreamChannel.writeAndFlush(req).addListener(
            (ChannelFutureListener) future1 -> {
              if (!future1.isSuccess()) {
                DEBUG.trace(ctx.channel(), upstreamChannel, future1.cause() + "");
                ctx.close();
                return;
              }
              DEBUG.trace(ctx.channel(), upstreamChannel, "upstream request sent, pooled");
            }
          );
        } else {
          DEBUG.error(ctx.channel(), "pool acquire", future.cause());
          ctx.close();
        }
      }
    });
  }

  public ChannelFuture connect(ChannelHandlerContext downstreamContext) {
    Bootstrap  bs = bootstrap.clone()
      .handler(new ChannelInitializer<SocketChannel>() {
        @Override
        public void initChannel(SocketChannel ch) {
          addHttpHandler(ch.pipeline());
          ch.pipeline().addLast("forward", new ForwardHandler(downstreamContext.channel()));
        }
      });
    ChannelFuture f = bs.connect();
    return f;
  }

  private static void sendResponseDownstream(Channel upstreamChannel, ChannelHandlerContext downstreamContext) {
    upstreamChannel.pipeline().replace(ForwardHandler.class, "forward", new ForwardHandler(downstreamContext.channel()));
  }

}
