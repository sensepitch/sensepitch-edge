package org.sensepitch.edge;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.concurrent.Future;

/// Connects to an upstream.
///
/// @author Jens Wilke
public interface Upstream {

  /// Provides an upstream channel. The channel is connected to the ingress and writes everything
  /// received to the ingress channel. The channel does its I/O on the ingress event loop extracted
  /// from the context, so the listeners of the returned future and of the channel's writes are
  /// called on it; the [DownstreamHandler] relies on that and does not hand them over to it.
  Future<Channel> connect(ChannelHandlerContext downstreamContext);

  /// Name of the upstream in the configuration, e.g. `upstreams.backend` or
  /// `sites.example.com.paths./api/*.upstream`, see [UpstreamRegistry]. Logged with each request.
  ///
  /// @return the name, or `null` if unnamed
  default String name() {
    return null;
  }

  /// Configured target, `host:port`, logged with each request
  ///
  /// @return the target, or `null` if the upstream has none, e.g. a static response
  default String target() {
    return null;
  }

  /// Called when a configuration reload removed or changed this upstream. Requests that are in
  /// flight may still use it.
  default void close() {}
}
