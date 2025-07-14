package org.sensepitch.edge;

import io.netty.bootstrap.Bootstrap;
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
import io.netty.channel.pool.SimpleChannelPool;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;
import io.netty.util.concurrent.Promise;

/**
 * @author Jens Wilke
 */
public class Upstream {

  private static ProxyLogger LOG = ProxyLogger.get(Upstream.class);

  private final Bootstrap bootstrap;
  private final SimpleChannelPool pool;

  public Upstream(UpstreamConfig cfg) {
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
      .group(new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory()))
      .channel(NioSocketChannel.class)
      .option(ChannelOption.SO_KEEPALIVE, true)
      .remoteAddress(target, port);
    final ChannelPoolHandler channelHandler = new ChannelPoolHandler() {
      @Override
      public void channelReleased(Channel ch) throws Exception {
        LOG.trace(null, ch, "channelReleased, pipeline=" + ch.pipeline().names());
      }

      @Override
      public void channelAcquired(Channel ch) throws Exception {
        LOG.trace(null, ch, "channelAcquired, pipeline=" + ch.pipeline().names());
      }

      @Override
      public void channelCreated(Channel ch) throws Exception {
        LOG.trace(null, ch, "channel created");
        addHttpHandler(ch.pipeline());
        ch.pipeline().addLast("forward", new ForwardHandler(null, null));
      }
    };
    int maxConnections = 1000;
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
    pipeline.addLast(new ReportIoErrorsHandler("upstream"));
    pipeline.addLast(new HttpClientCodec());
    // pipeline.addLast(new LoggingHandler(LogLevel.INFO));
  }

  /**
   * TODO: have a better pool returning ChannelFuture?
   */
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
    Future<Channel> future = pool.acquire();
    future.addListener(new FutureListener<Channel>() {
      @Override
      public void operationComplete(Future<Channel> future) throws Exception {
        if (future.isSuccess()) {
          DownstreamProgress.progress(downstream, "upstream connection established");
          Channel ch = future.resultNow();
          if (LOG.isTraceEnabled()) {
            LOG.trace(downstream, future.resultNow(), "pool acquire complete isActive=" + ch.isActive() + " pipeline=" + ch.pipeline().names());
          }
          try {
            future.resultNow().pipeline().replace("forward", "forward", new ForwardHandler(downstream, pool));
          } catch (Throwable t) {
            LOG.error(downstream, future.resultNow(), "pipeline replace", t);
          }
        }
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
