package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/// Values of [ListenConfig] checked when the record is built. The listeners themselves are in
/// [PlainHttpListenerIT] and the proxy tests.
class ListenConfigTest {

  /// `0` disables the plain HTTP listener, negative values are rejected instead of a bind
  /// failure at startup
  @Test
  void negativeHttpPortIsRejected() {
    assertThat(ListenConfig.builder().httpPort(0).build().httpPort()).isZero();
    assertThatThrownBy(() -> ListenConfig.builder().httpPort(-1).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("httpPort must be >= 0, was: -1");
  }
}
