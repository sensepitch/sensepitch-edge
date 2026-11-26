package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import java.io.IOException;
import java.io.InputStream;
import javax.net.ssl.SSLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/// The handshake check of a server context. [SslSetupIT] tests keys of another certificate
/// through [SslSetup].
///
/// @author Jens Wilke
class SslContextCheckTest {

  @ParameterizedTest
  @ValueSource(strings = {"TLSv1.3", "TLSv1.2"})
  void matchingKeyCompletesTheHandshake(String protocol) throws IOException {
    SslContext context = testContext(protocol);
    assertThatCode(() -> SslContextCheck.requireHandshake(context)).doesNotThrowAnyException();
  }

  @Test
  void handshakeExceedingTheRoundtripLimitFails() throws IOException {
    SslContext context = testContext("TLSv1.3");
    assertThatThrownBy(() -> SslContextCheck.requireHandshake(context, 1))
        .isInstanceOf(SSLException.class)
        .hasMessage("TLS handshake exceeded the roundtrip limit of 1");
  }

  /// The server context of the test key and certificate, built without the check
  ///
  /// @param protocol the only protocol the server accepts
  private static SslContext testContext(String protocol) throws IOException {
    ConfigFiles files = new ConfigFiles();
    try (InputStream cert = files.open(ConfigReloadTest.TEST_SSL.certPath());
        InputStream key = files.open(ConfigReloadTest.TEST_SSL.keyPath())) {
      return SslContextBuilder.forServer(cert, key)
          .sslProvider(SslProvider.OPENSSL)
          .protocols(protocol)
          .build();
    }
  }
}
