package org.sensepitch.edge;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.sensepitch.edge.TestingParameters.EVENT_TIMEOUT_SECONDS;
import static org.sensepitch.edge.TestingParameters.TEST_TIMEOUT_SECONDS;

import io.netty.channel.Channel;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.EventLoop;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioSocketChannel;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

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

  /// Checks one connection and releases it before the next exchange on the same ingress.
  private void assertConnectsOnIngressLoop(DefaultUpstream upstream, Channel ingress)
      throws Exception {
    Channel channel =
        upstream.connect(ingress.pipeline().firstContext()).get(EVENT_TIMEOUT_SECONDS, SECONDS);
    try {
      assertThat(channel.eventLoop()).isSameAs(ingress.eventLoop());
    } finally {
      channel.close().get(EVENT_TIMEOUT_SECONDS, SECONDS);
    }
  }
}
