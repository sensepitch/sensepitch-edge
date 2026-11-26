package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.util.Set;
import javax.net.ssl.SSLException;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/// OpenSSL key-algorithm checks for [SslSetup]. Unit tests for the setup belong in
/// [SslSetupTest], and [ConfigReloadTest] checks that a rejected reload keeps the active context.
class SslSetupIT {

  /// The handshake check rejects mismatched keys of the certificate's type (RSA) and of another
  /// type (EC).
  @ParameterizedTest
  @ValueSource(strings = {"RSA", "EC"})
  void keyOfAnotherCertificateIsRejected(String algorithm, @TempDir Path dir)
      throws IOException, NoSuchAlgorithmException {
    Path key = SslSetupTest.writeNewKey(algorithm, dir);
    SslConfig cfg = ConfigReloadTest.TEST_SSL.toBuilder().keyPath(key.toString()).build();
    ListenConfig listen = ListenConfig.builder().ssl(cfg).build();
    assertThatThrownBy(() -> SslSetup.build(listen, Set.of(), null, new ConfigFiles()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Cannot build the TLS context from " + cfg.certPath() + " and " + key)
        .hasCauseInstanceOf(SSLException.class);
  }
}
