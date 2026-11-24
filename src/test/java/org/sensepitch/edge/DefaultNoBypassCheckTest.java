package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/// URIs the deflector never bypasses, see [NoBypassConfig]
///
/// @author Jens Wilke
class DefaultNoBypassCheckTest {

  final ChannelHandlerContext ctx =
      new EmbeddedChannel(new ChannelInboundHandlerAdapter()).pipeline().firstContext();

  /// Only exact URIs, a wildcard would look like a prefix match but never match
  @ParameterizedTest
  @ValueSource(strings = {"/admin*", "*.php"})
  void wildcardIsRejected(String uri) {
    assertThatThrownBy(
            () -> new DefaultNoBypassCheck(NoBypassConfig.builder().uris(List.of(uri)).build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(uri);
  }

  @Test
  void exactUriOnly() {
    NoBypassCheck check =
        new DefaultNoBypassCheck(NoBypassConfig.builder().uris(List.of("/login")).build());
    assertThat(check.skipBypass(ctx, DefaultBypassCheckTest.get("/login"))).isTrue();
    assertThat(check.skipBypass(ctx, DefaultBypassCheckTest.get("/login/x"))).isFalse();
  }

  @Test
  void withoutUrisNothingIsExcluded() {
    NoBypassCheck check = new DefaultNoBypassCheck(NoBypassConfig.builder().build());
    assertThat(check.skipBypass(ctx, DefaultBypassCheckTest.get("/login"))).isFalse();
  }
}
