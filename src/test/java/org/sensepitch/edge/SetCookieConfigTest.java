package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/// @author Jens Wilke
class SetCookieConfigTest {

  @Test
  void defaults() {
    assertThat(SetCookieConfig.builder().name("beta").build().encode())
        .isEqualTo("beta=1; Path=/; Secure; HTTPOnly; SameSite=Lax");
  }

  @Test
  void allAttributes() {
    String encoded =
        SetCookieConfig.builder()
            .name("beta")
            .value("on")
            .maxAge(3600)
            .path("/shop")
            .domain("example.com")
            .sameSite("strict")
            .build()
            .encode();
    assertThat(encoded)
        .startsWith("beta=on; Max-Age=3600; Expires=")
        .endsWith("; Path=/shop; Domain=example.com; Secure; HTTPOnly; SameSite=Strict");
  }

  @Test
  void delete() {
    assertThat(SetCookieConfig.builder().name("beta").delete(true).build().encode())
        .startsWith("beta=; Max-Age=0; Expires=")
        .endsWith("; Path=/; Secure; HTTPOnly; SameSite=Lax");
  }

  @Test
  void nameMissingFails() {
    assertThatThrownBy(() -> SetCookieConfig.builder().build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cookie name missing");
  }

  @Test
  void deleteWithMaxAgeFails() {
    assertThatThrownBy(() -> SetCookieConfig.builder().name("beta").delete(true).maxAge(1).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exclusive");
  }

  @Test
  void negativeMaxAgeFails() {
    assertThatThrownBy(() -> SetCookieConfig.builder().name("beta").maxAge(-1).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("negative");
  }

  @Test
  void invalidSameSiteFails() {
    assertThatThrownBy(() -> SetCookieConfig.builder().name("beta").sameSite("sometimes").build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("sameSite");
  }

  @Test
  void responseWithoutCookiesHasEmptyList() {
    ResponseConfig cfg = ResponseConfig.builder().text("hi").build();
    assertThat(cfg.cookies()).isEmpty();
    assertThat(cfg.encodedCookies()).isEmpty();
  }

  @Test
  void responseEncodesEachCookie() {
    ResponseConfig cfg =
        ResponseConfig.builder()
            .location("/")
            .cookies(
                List.of(
                    SetCookieConfig.builder().name("beta").maxAge(2592000).build(),
                    SetCookieConfig.builder().name("alpha").delete(true).build()))
            .build();
    assertThat(cfg.cookies()).extracting(SetCookieConfig::name).containsExactly("beta", "alpha");
    assertThat(cfg.encodedCookies()).hasSize(2);
  }
}
