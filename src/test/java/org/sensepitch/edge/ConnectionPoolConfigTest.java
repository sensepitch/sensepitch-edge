package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class ConnectionPoolConfigTest {

  @Test
  void defaults() {
    ConnectionPoolConfig cfg =
        UpstreamConfig.builder().target("localhost:8080").build().connectionPool();
    assertThat(cfg).isEqualTo(ConnectionPoolConfig.DEFAULT);
    assertThat(cfg.disabled()).isFalse();
    assertThat(cfg.idleTimeoutMillis()).isEqualTo(4_000);
    assertThat(cfg.maxConnections()).isEqualTo(10_000);
  }

  /// The event loops share the limit, rounded down, so together they do not exceed it; every loop
  /// may open at least one connection
  @Test
  void eachEventLoopGetsItsShare() {
    ConnectionPoolConfig cfg = ConnectionPoolConfig.builder().maxConnections(100).build();
    assertThat(cfg.maxConnectionsPerEventLoop(32)).isEqualTo(3);
    assertThat(cfg.maxConnectionsPerEventLoop(1)).isEqualTo(100);
    assertThat(cfg.maxConnectionsPerEventLoop(200)).isOne();
  }

  @Test
  void rejectsNoIdleTime() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> ConnectionPoolConfig.builder().idleTimeoutMillis(0).build())
        .withMessage("connectionPool.idleTimeoutMillis must be at least 1");
  }

  @Test
  void rejectsNoConnections() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> ConnectionPoolConfig.builder().maxConnections(0).build())
        .withMessage("connectionPool.maxConnections must be at least 1");
  }
}
