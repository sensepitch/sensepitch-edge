package org.sensepitch.edge;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoop;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;

/// Connections to an upstream server, on the event loop of the client channel. An exchange takes an
/// idle connection of the [UpstreamConnectionPool] of that loop, or opens a new one; the
/// [DownstreamHandler] releases it after a clean exchange, otherwise it is closed. With
/// [ConnectionPoolConfig#enable()] off every exchange opens a connection and closes it after the
/// response. The upstream connections are spread over the event loops like the accepted client
/// connections, so their load balance is that of the clients.
///
/// @author Jens Wilke
public class DefaultUpstream implements Upstream {

  private final String name;
  private final String target;
  private final Bootstrap bootstrap;
  private final ProxyMetrics metrics;
  private final boolean pooling;
  private final UpstreamConnectionPool pool;

  /// @param name identifies the upstream within the configuration, see [UpstreamRegistry]
  public DefaultUpstream(ProxyContext ctx, String name, UpstreamConfig cfg) {
    this.name = name;
    this.target = cfg.target();
    this.metrics = ctx.metrics();
    this.pooling = cfg.connectionPool().enable();
    this.pool =
        new UpstreamConnectionPool(
            cfg.connectionPool(), metrics, metricsName(), ctx.environment().eventLoopThreads());
    String[] sa = cfg.target().split(":");
    int port = 80;
    String target = sa[0];
    if (sa.length > 2) {
      throw new IllegalArgumentException("Target: " + cfg.target());
    }
    if (sa.length > 1) {
      port = Integer.parseInt(sa[1]);
    }
    bootstrap =
        new Bootstrap()
            .channel(NioSocketChannel.class)
            // deliberately no ChannelOption.ALLOCATOR, so this uses ByteBufAllocator.DEFAULT
            // like the ingress side does, see Proxy.start()
            .option(ChannelOption.SO_KEEPALIVE, true)
            .remoteAddress(target, port);
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public String target() {
    return target;
  }

  void addHttpHandler(ChannelPipeline pipeline) {
    pipeline.addLast(new HttpClientCodec());
  }

  /// An idle connection of the pool of the client's event loop, bound to the exchange, or a new
  /// connection
  @Override
  public Future<Channel> connect(ChannelHandlerContext ingressCtx) {
    if (pooling) {
      Channel idle = pool.acquire(ingressCtx.channel().eventLoop());
      if (idle != null) {
        idle.pipeline().get(ForwardHandler.class).attach(ingressCtx.channel());
        return ingressCtx.executor().newSucceededFuture(idle);
      }
    }
    return connectNew(ingressCtx);
  }

  /// A new connection on the event loop of the client channel, if the loop's share of
  /// `maxConnections` allows it, otherwise a failed future with [Upstream.ConnectionLimitException].
  /// The connection is counted until it closes.
  @Override
  public Future<Channel> connectNew(ChannelHandlerContext ingressCtx) {
    EventLoop loop = ingressCtx.channel().eventLoop();
    if (!pool.reserve(loop)) {
      metrics.upstreamConnectionLimit.labelValues(metricsName()).inc();
      return ingressCtx.executor().newFailedFuture(new ConnectionLimitException(target));
    }
    ChannelFuture upstreamFuture = connectToUpstream(ingressCtx.channel());
    Promise<Channel> promise = ingressCtx.executor().newPromise();
    upstreamFuture.addListener(
        (ChannelFutureListener)
            cf -> {
              if (cf.isSuccess()) {
                cf.channel().closeFuture().addListener(closed -> pool.unreserve(loop));
                metrics.upstreamConnectionsOpened.labelValues(metricsName()).inc();
                promise.setSuccess(cf.channel());
              } else {
                pool.unreserve(loop);
                promise.setFailure(cf.cause());
              }
            });
    return promise;
  }

  private ChannelFuture connectToUpstream(Channel ingress) {
    Bootstrap bs =
        bootstrap
            .clone(ingress.eventLoop())
            .handler(
                new ChannelInitializer<SocketChannel>() {
                  @Override
                  public void initChannel(SocketChannel ch) {
                    addHttpHandler(ch.pipeline());
                    ch.pipeline()
                        .addLast("forward", new ForwardHandler(ingress, metrics, metricsName()));
                  }
                });
    return bs.connect();
  }

  /// Keeps a reusable connection in the pool of its event loop, closes it otherwise
  @Override
  public void release(Channel channel, boolean reusable) {
    if (pooling && reusable) {
      pool.release(channel);
    } else {
      channel.close();
    }
  }

  @Override
  public boolean reused(Channel channel) {
    // only the pool sets it, when it hands out an idle connection
    return channel.hasAttr(UpstreamConnectionPool.REUSED);
  }

  /// Closes the idle connections, see [UpstreamConnectionPool#close()]
  @Override
  public void close() {
    pool.close();
  }

  /// Label of the upstream metrics, see [ProxyMetrics#upstreamLabel(Upstream)]
  private String metricsName() {
    return ProxyMetrics.upstreamLabel(this);
  }
}
