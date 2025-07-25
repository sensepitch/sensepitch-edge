package org.sensepitch.edge;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.pool.ChannelHealthChecker;
import io.netty.channel.pool.ChannelPoolHandler;
import io.netty.channel.pool.FixedChannelPool;
import io.netty.channel.pool.SimpleChannelPool;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;
import io.netty.util.concurrent.Promise;

/**
 * @author Jens Wilke
 */
public class DefaultUpstream implements Upstream {

  private static ProxyLogger LOG = ProxyLogger.get(DefaultUpstream.class);

  private final Bootstrap bootstrap;
  private final SimpleChannelPool pool;

  public DefaultUpstream(ProxyContext ctx, UpstreamConfig cfg) {
    String[] sa = cfg.target().split(":");
    int port = 80;
    String target = sa[0];
    if (sa.length > 2) {
      throw new IllegalArgumentException("Target: " + cfg.target());
    }
    if (sa.length > 1) {
      port = Integer.parseInt(sa[1]);
    }
    bootstrap = new Bootstrap()
      .group(ctx.eventLoopGroup())
      .channel(NioSocketChannel.class)
      .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
      .option(ChannelOption.SO_KEEPALIVE, true)
      .remoteAddress(target, port);
    ChannelPoolHandler channelHandler = new ChannelPoolHandler() {
      @Override
      public void channelReleased(Channel ch) throws Exception {
      }

      @Override
      public void channelAcquired(Channel ch) throws Exception {
      }

      @Override
      public void channelCreated(Channel ch) throws Exception {
        addHttpHandler(ch.pipeline());
        ch.pipeline().addLast("forward", new ForwardHandler(null, null));
      }
    };
    // TODO: parameter
    int maxConnections = 0;
    if (maxConnections <= 0) {
      pool = new SimpleChannelPool(bootstrap,
        channelHandler,
        ChannelHealthChecker.ACTIVE);
    } else {
      pool = new FixedChannelPool(bootstrap,
        channelHandler,
        ChannelHealthChecker.ACTIVE,
        FixedChannelPool.AcquireTimeoutAction.FAIL,
        50,   // acquire timeout ms
        maxConnections,     // max connections
        1,
        true
      );
    }
  }

  void addHttpHandler(ChannelPipeline  pipeline) {
    // pipeline.addLast(new ReportIoErrorsHandler("upstream"));
    pipeline.addLast(new HttpClientCodec());
    // FIXME: timeout?
    // pipeline.addLast(new ReadTimeoutHandler(23));
    // pipeline.addLast(new LoggingHandler(LogLevel.INFO));
  }

  @Override
  public Future<Channel> connect(ChannelHandlerContext downstreamContext) {
    boolean pooled = true;
    if (pooled) {
      return getPooledChannel(downstreamContext.channel());
    } else {
      ChannelFuture upstreamFuture = connectToUpstream(downstreamContext.channel());
      Promise<Channel> promise = downstreamContext.executor().newPromise();
      upstreamFuture.addListener((ChannelFutureListener) cf -> {
        if (cf.isSuccess()) {
          promise.setSuccess(cf.channel());
        } else {
          promise.setFailure(cf.cause());
        }
      });
      return promise;
    }
  }

  private Future<Channel> getPooledChannel(Channel downstream) {
    Future<Channel> future = pool.acquire(downstream.eventLoop().newPromise());
    future.addListener((FutureListener<Channel>) future1 -> {
      if (future1.isSuccess()) {
        DownstreamProgress.progress(downstream, "upstream connection established");
        Channel ch = future1.resultNow();
        if (LOG.isTraceEnabled()) {
          LOG.trace(downstream, future1.resultNow(), "pool acquire complete isActive=" + ch.isActive() + " pipeline=" + ch.pipeline().names());
        }
        // we need to do this in the lister call back, to set the downstream
        future1.resultNow().pipeline().replace("forward", "forward", new ForwardHandler(downstream, pool));
      }
    });
    return future;
  }

  private ChannelFuture connectToUpstream(Channel downstream) {
    Bootstrap  bs = bootstrap.clone()
      .handler(new ChannelInitializer<SocketChannel>() {
        @Override
        public void initChannel(SocketChannel ch) {
          addHttpHandler(ch.pipeline());
          ch.pipeline().replace("forward", "forward", new ForwardHandler(downstream, null));
        }
      });
    ChannelFuture f = bs.connect();
    return f;
  }

}
