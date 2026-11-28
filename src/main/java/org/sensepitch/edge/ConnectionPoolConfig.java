package org.sensepitch.edge;

import lombok.Builder;

/// Keep-alive connections to an upstream, reused for the next requests instead of opening a new
/// connection for each, and the limit of connections to it. See
/// `doc/design/upstream-connection-pool.md`.
///
/// @param disabled open a new connection for every request and close it after the response,
///   instead of keeping connections open and reusing them. A flag, so a later configuration layer
///   can switch off a default.
/// @param idleTimeoutMillis an idle connection is closed after this time. After a burst of
///   requests, the connections no longer needed are closed this way. Keep it below the keep-alive
///   timeout of the upstream server, otherwise the server closes connections just as the edge sends
///   a request on them. At least 1.
/// @param maxConnections the most connections to the upstream, open, idle and connecting, also with
///   `disabled: true`. Shared evenly by the event loops: each may open `maxConnections` divided by
///   the number of event loops, rounded down, at least 1. A request that needs a further connection
///   is answered with `503` and the outcome `upstream_connection_limit`. The default keeps the local
///   ports of the edge from running out. At least 1.
/// @author Jens Wilke
@Builder(toBuilder = true)
public record ConnectionPoolConfig(boolean disabled, long idleTimeoutMillis, int maxConnections) {

  /// Below the 5 s keep-alive timeout common for application servers
  static final long DEFAULT_IDLE_TIMEOUT_MILLIS = 4_000;

  /// Well below the about 28 000 local ports of a Linux host with the default port range
  static final int DEFAULT_MAX_CONNECTIONS = 10_000;

  public static final ConnectionPoolConfig DEFAULT = builder().build();

  /// Builder with the defaults preset
  public static Builder builder() {
    return new Builder()
        .idleTimeoutMillis(DEFAULT_IDLE_TIMEOUT_MILLIS)
        .maxConnections(DEFAULT_MAX_CONNECTIONS);
  }

  /// The connections one event loop may open, see `maxConnections`
  ///
  /// @param eventLoops the number of event loops, see [ProxyEnvironment#eventLoopThreads()]
  int maxConnectionsPerEventLoop(int eventLoops) {
    return Math.max(1, maxConnections / eventLoops);
  }

  /// Completed by Lombok, which generates everything but [#build()]
  public static class Builder {

    /// @throws IllegalArgumentException if `idleTimeoutMillis` or `maxConnections` is below 1
    public ConnectionPoolConfig build() {
      if (idleTimeoutMillis < 1) {
        throw new IllegalArgumentException("connectionPool.idleTimeoutMillis must be at least 1");
      }
      if (maxConnections < 1) {
        throw new IllegalArgumentException("connectionPool.maxConnections must be at least 1");
      }
      return new ConnectionPoolConfig(disabled, idleTimeoutMillis, maxConnections);
    }
  }
}
