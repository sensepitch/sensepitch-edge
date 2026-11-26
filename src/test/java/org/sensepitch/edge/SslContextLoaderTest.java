package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// Which contexts a build takes over from the previous one. The reload of the proxy is tested in
/// `ConfigReloadTest.sniCertificatesReloaded`.
///
/// @author Jens Wilke
class SslContextLoaderTest {

  @TempDir Path dir;

  Path cert;
  Path key;
  SslConfig ssl;

  /// Key and certificate as files, class path resources are not recorded
  @BeforeEach
  void copyKeyAndCertificate() throws IOException {
    cert = copy("ssl/test.crt", dir.resolve("test.crt"));
    key = copy("ssl/test.key", dir.resolve("test.key"));
    ssl = SslConfig.builder().certPath(cert.toString()).keyPath(key.toString()).build();
  }

  @Test
  void equalConfigInOneBuildSharesTheContext() {
    var loader = new SslContextLoader(Map.of(), new ConfigFiles());
    assertThat(loader.load(ssl)).isSameAs(loader.load(ssl.toBuilder().build()));
  }

  /// The files of a context taken over are recorded in the next build, so the watcher keeps
  /// watching them
  @Test
  void unchangedContextIsTakenOver() {
    var first = new SslContextLoader(Map.of(), new ConfigFiles());
    SslContextLoader.Loaded loaded = first.load(ssl);
    ConfigFiles files = new ConfigFiles();
    var next = new SslContextLoader(first.loaded(), files);
    assertThat(next.load(ssl)).isSameAs(loaded);
    assertThat(files.snapshot().files()).containsKeys(cert, key);
  }

  /// A renewed certificate, replaced by a rename like Kubernetes and certbot do
  @Test
  void changedFileIsLoadedAgain() throws IOException {
    var first = new SslContextLoader(Map.of(), new ConfigFiles());
    SslContextLoader.Loaded loaded = first.load(ssl);
    Path renewed = copy("ssl/test.crt", dir.resolve("renewed.crt"));
    Files.move(renewed, cert, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    var next = new SslContextLoader(first.loaded(), new ConfigFiles());
    assertThat(next.load(ssl)).isNotSameAs(loaded);
  }

  /// A missing key is recorded in the build's files, so the reload is tried again when it appears
  @Test
  void missingFileIsRecorded() throws IOException {
    Files.delete(key);
    ConfigFiles files = new ConfigFiles();
    var loader = new SslContextLoader(Map.of(), files);
    assertThatThrownBy(() -> loader.load(ssl)).isInstanceOf(IllegalArgumentException.class);
    assertThat(files.snapshot().files())
        .containsEntry(key, ConfigFiles.Fingerprint.ABSENT)
        .containsKey(cert);
  }

  private static Path copy(String resource, Path target) throws IOException {
    try (InputStream in = SslContextLoaderTest.class.getResourceAsStream("/" + resource)) {
      Files.copy(in, target);
    }
    return target;
  }
}
