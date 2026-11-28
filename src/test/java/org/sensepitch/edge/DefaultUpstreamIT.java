package org.sensepitch.edge;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.sensepitch.edge.TestingParameters.EVENT_TIMEOUT_SECONDS;
import static org.sensepitch.edge.TestingParameters.TEST_TIMEOUT_SECONDS;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.EventLoop;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.concurrent.Future;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(TEST_TIMEOUT_SECONDS)
class DefaultUpstreamIT {

  private record Context(ProxyMetrics metrics, ProxyEnvironment environment)
      implements ProxyContext {}

  @Test
  void connectsOnEachIngressEventLoop() throws Exception {
    var loops = new MultiThreadIoEventLoopGroup(2, NioIoHandler.newFactory());
    try (var listener = ServerSocketChannel.open();
        var target = new ServerSocket()) {
      listener.bind(new InetSocketAddress(Loopback.ADDRESS, 0));
      target.bind(new InetSocketAddress(Loopback.ADDRESS, 0));
      var upstream =
          new DefaultUpstream(
              new Context(new ProxyMetrics(), new ProxyEnvironment(2)),
              "backend",
              UpstreamConfig.builder()
                  .target(Loopback.ADDRESS + ":" + target.getLocalPort())
                  .build());
      EventLoop first = loops.next();
      EventLoop second = loops.next();
      assertThat(first).isNotSameAs(second);
      for (EventLoop loop : List.of(first, second)) {
        try (var client = SocketChannel.open(listener.getLocalAddress())) {
          var ingress = new NioSocketChannel(listener.accept());
          ingress.pipeline().addLast(new ChannelInboundHandlerAdapter());
          loop.register(ingress).get(EVENT_TIMEOUT_SECONDS, SECONDS);
          try {
            // A group-wide round robin cannot match the same ingress twice in succession.
            for (int i = 0; i < 2; i++) {
              assertConnectsOnIngressLoop(upstream, ingress);
            }
          } finally {
            ingress.close().get(EVENT_TIMEOUT_SECONDS, SECONDS);
          }
        }
      }
    } finally {
      loops
          .shutdownGracefully(0, EVENT_TIMEOUT_SECONDS, SECONDS)
          .get(EVENT_TIMEOUT_SECONDS, SECONDS);
    }
  }

  /// A connection released as reusable is the one the next exchange on the same event loop gets,
  /// marked as reused; without pooling it is closed and the next exchange opens a new one
  @ParameterizedTest(name = "pooling {0}")
  @ValueSource(booleans = {true, false})
  void releasedConnectionIsReusedOnItsLoop(boolean pooling) throws Exception {
    var loops = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
    try (var listener = ServerSocketChannel.open();
        var target = new ServerSocket()) {
      listener.bind(new InetSocketAddress(Loopback.ADDRESS, 0));
      target.bind(new InetSocketAddress(Loopback.ADDRESS, 0));
      ProxyMetrics metrics = new ProxyMetrics();
      var upstream =
          new DefaultUpstream(
              new Context(metrics, new ProxyEnvironment(1)),
              "backend",
              UpstreamConfig.builder()
                  .target(Loopback.ADDRESS + ":" + target.getLocalPort())
                  .connectionPool(ConnectionPoolConfig.builder().disabled(!pooling).build())
                  .build());
      try (var client = SocketChannel.open(listener.getLocalAddress())) {
        var ingress = new NioSocketChannel(listener.accept());
        ingress.pipeline().addLast(new ChannelInboundHandlerAdapter());
        EventLoop loop = loops.next();
        loop.register(ingress).get(EVENT_TIMEOUT_SECONDS, SECONDS);
        ChannelHandlerContext ingressContext = ingress.pipeline().firstContext();
        try {
          Channel first = connectOnLoop(upstream, ingressContext);
          loop.submit(() -> upstream.release(first, true)).get(EVENT_TIMEOUT_SECONDS, SECONDS);
          Channel second = connectOnLoop(upstream, ingressContext);
          if (pooling) {
            assertThat(second).isSameAs(first);
            assertThat(upstream.reused(second)).isTrue();
          } else {
            assertThat(second).isNotSameAs(first);
            first.closeFuture().get(EVENT_TIMEOUT_SECONDS, SECONDS);
            assertThat(upstream.reused(second)).isFalse();
          }
          assertThat(metrics.upstreamConnectionsOpened.labelValues("backend").get())
              .isEqualTo(pooling ? 1 : 2);
          second.close().get(EVENT_TIMEOUT_SECONDS, SECONDS);
        } finally {
          ingress.close().get(EVENT_TIMEOUT_SECONDS, SECONDS);
        }
      }
    } finally {
      loops
          .shutdownGracefully(0, EVENT_TIMEOUT_SECONDS, SECONDS)
          .get(EVENT_TIMEOUT_SECONDS, SECONDS);
    }
  }

  /// With a limit of one connection on one event loop: a second connection is refused while the
  /// first is open, the released idle one is still handed out, and after it closed a new one opens
  @Test
  void connectionLimitOfTheEventLoop() throws Exception {
    var loops = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
    try (var listener = ServerSocketChannel.open();
        var target = new ServerSocket()) {
      listener.bind(new InetSocketAddress(Loopback.ADDRESS, 0));
      target.bind(new InetSocketAddress(Loopback.ADDRESS, 0));
      ProxyMetrics metrics = new ProxyMetrics();
      var upstream =
          new DefaultUpstream(
              new Context(metrics, new ProxyEnvironment(1)),
              "backend",
              UpstreamConfig.builder()
                  .target(Loopback.ADDRESS + ":" + target.getLocalPort())
                  .connectionPool(ConnectionPoolConfig.builder().maxConnections(1).build())
                  .build());
      try (var client = SocketChannel.open(listener.getLocalAddress())) {
        var ingress = new NioSocketChannel(listener.accept());
        ingress.pipeline().addLast(new ChannelInboundHandlerAdapter());
        EventLoop loop = loops.next();
        loop.register(ingress).get(EVENT_TIMEOUT_SECONDS, SECONDS);
        ChannelHandlerContext ingressContext = ingress.pipeline().firstContext();
        try {
          Channel first = connectOnLoop(upstream, ingressContext);
          Future<Channel> refused =
              loop.submit(() -> upstream.connectNew(ingressContext))
                  .get(EVENT_TIMEOUT_SECONDS, SECONDS);
          assertThat(refused.cause()).isInstanceOf(Upstream.ConnectionLimitException.class);
          assertThat(metrics.upstreamConnectionLimit.labelValues("backend").get()).isOne();
          loop.submit(() -> upstream.release(first, true)).get(EVENT_TIMEOUT_SECONDS, SECONDS);
          assertThat(connectOnLoop(upstream, ingressContext))
              .as("idle one at the limit")
              .isSameAs(first);
          first.close().get(EVENT_TIMEOUT_SECONDS, SECONDS);
          Channel next = connectOnLoop(upstream, ingressContext);
          assertThat(next).isNotSameAs(first);
          assertThat(metrics.upstreamConnections.labelValues("backend").get()).isOne();
          next.close().get(EVENT_TIMEOUT_SECONDS, SECONDS);
        } finally {
          ingress.close().get(EVENT_TIMEOUT_SECONDS, SECONDS);
        }
      }
    } finally {
      loops
          .shutdownGracefully(0, EVENT_TIMEOUT_SECONDS, SECONDS)
          .get(EVENT_TIMEOUT_SECONDS, SECONDS);
    }
  }

  /// Connects on the event loop of the ingress, where the proxy calls it
  private static Channel connectOnLoop(DefaultUpstream upstream, ChannelHandlerContext ingress)
      throws Exception {
    return ingress
        .executor()
        .submit(() -> upstream.connect(ingress))
        .get(EVENT_TIMEOUT_SECONDS, SECONDS)
        .get(EVENT_TIMEOUT_SECONDS, SECONDS);
  }

  /// Checks one connection and releases it before the next exchange on the same ingress.
  private void assertConnectsOnIngressLoop(DefaultUpstream upstream, Channel ingress)
      throws Exception {
    Channel channel = connectOnLoop(upstream, ingress.pipeline().firstContext());
    try {
      assertThat(channel.eventLoop()).isSameAs(ingress.eventLoop());
    } finally {
      channel.close().get(EVENT_TIMEOUT_SECONDS, SECONDS);
    }
  }
}
