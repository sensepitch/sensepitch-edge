package org.sensepitch.edge;

import lombok.Builder;

/// Upstream connection settings. Either `target` is set, or `ref` names an upstream defined in
/// [ProxyConfig#upstreams()].
///
/// @param key name of the upstream, when defined in [ProxyConfig#upstreams()]. Injected from the
///   map key.
/// @param ref name of an upstream defined in [ProxyConfig#upstreams()]. Mutually exclusive with
///   all other settings.
/// @param target target host with optional port number. Names are supported, however the standard
///   Java DNS resolver is used
/// @param connectionPool keep-alive connections to the upstream, on by default, see
///   [ConnectionPoolConfig]
/// @author Jens Wilke
@Builder(toBuilder = true)
public record UpstreamConfig(
    String key, String ref, String target, ConnectionPoolConfig connectionPool) implements HasKey {

  /// Builder with the defaults preset
  public static Builder builder() {
    return new Builder().connectionPool(ConnectionPoolConfig.DEFAULT);
  }
}
