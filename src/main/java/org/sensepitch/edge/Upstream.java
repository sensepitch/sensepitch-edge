package org.sensepitch.edge;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.concurrent.Future;

/// Connects to an upstream.
///
/// @author Jens Wilke
public interface Upstream {

  /// Provides an upstream channel. The channel is connected to the ingress and writes everything
  /// received to the ingress channel. The listeners of the returned future are called on the
  /// ingress event loop extracted from the context, the [DownstreamHandler] relies on that. The
  /// channel itself may do its I/O on another event loop, then the listeners of its writes run
  /// there; [DefaultUpstream] uses the ingress event loop, which saves the hops between loops.
  Future<Channel> connect(ChannelHandlerContext downstreamContext);

  /// Name of the upstream in the configuration, e.g. `upstreams.backend` or
  /// `sites.example.com.paths./api/*.upstream`, see [UpstreamRegistry]. Logged with each exchange.
  ///
  /// @return the name, or `null` if unnamed
  default String name() {
    return null;
  }

  /// Configured target, `host:port`, logged with each exchange
  ///
  /// @return the target, or `null` if the upstream has none, e.g. a static response
  default String target() {
    return null;
  }

  /// Ends the use of the channel of an exchange. A pooling upstream keeps a `reusable` channel for
  /// the next exchange, see [DefaultUpstream]; otherwise, and by default, the channel is closed.
  ///
  /// @param reusable the exchange ended cleanly and the connection is ready for the next request,
  ///   see [DownstreamHandler]
  default void release(Channel channel, boolean reusable) {
    channel.close();
  }

  /// Like [#connect(ChannelHandlerContext)], but always a new connection, never an idle one of a
  /// pool, for the retry after a reused connection failed, see [DownstreamHandler]
  default Future<Channel> connectNew(ChannelHandlerContext downstreamContext) {
    return connect(downstreamContext);
  }

  /// Whether the channel was an idle connection taken from a pool, so it may have been closed by
  /// the upstream just as the request was sent
  default boolean reused(Channel channel) {
    return false;
  }

  /// The upstream has as many connections as it may open, see
  /// [ConnectionPoolConfig#maxConnections()]; the exchange is answered with `503`, see
  /// [DownstreamHandler]. Without a stack trace: it is an expected event under load, the request
  /// log records type and message.
  final class ConnectionLimitException extends RuntimeException {

    public ConnectionLimitException(String target) {
      super("connection limit of upstream " + target + " reached", null, false, false);
    }
  }

  /// Called when a configuration reload removed or changed this upstream. Exchanges that are
  /// in flight may still use it.
  default void close() {}
}
