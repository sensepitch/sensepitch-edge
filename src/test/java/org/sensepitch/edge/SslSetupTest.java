package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// The SNI selection, the takeover of unchanged contexts and the certificate log of [SslSetup].
/// [SslSetupIT] checks different key algorithms with OpenSSL. [ConfigReloadTest] covers reloads.
///
/// @author Jens Wilke
class SslSetupTest {

  // SNI selection

  /// A certificate of its own per entry, an equal [SslConfig] shares its context
  @Test
  void exactSniOverridesWildcardAndMissesUseDefault() {
    SslConfig wildcardSsl =
        SslConfig.builder()
            .certPath("classpath:ssl/wildcard.crt")
            .keyPath("classpath:ssl/test.key")
            .build();
    SslSetup setup =
        build(
            ListenConfig.builder()
                .ssl(ConfigReloadTest.TEST_SSL)
                .snis(
                    List.of(
                        new SniConfig("*.example.org", wildcardSsl),
                        new SniConfig("api.example.org", ConfigReloadTest.OTHER_SSL)))
                .build());
    var fallback = setup.map(null);
    var wildcard = setup.map("one.example.org");
    assertThat(wildcard).isNotSameAs(fallback).isSameAs(setup.map("TWO.example.org"));
    assertThat(setup.map("api.example.org")).isNotSameAs(wildcard).isNotSameAs(fallback);
    assertThat(setup.map("example.org")).isSameAs(fallback);
    assertThat(setup.map("-bad.example.org")).isSameAs(fallback);
    assertThat(setup.map("*.example.org")).isSameAs(fallback);
    assertThat(setup.map("two.one.example.org")).isSameAs(fallback);
  }

  @Test
  void wildcardLetsEncryptUsesSuffixDirectory() {
    var listen =
        ListenConfig.builder()
            .letsEncrypt(true)
            .letsEncryptPrefix("classpath:/letsencrypt/live/")
            .ssl(ConfigReloadTest.TEST_SSL)
            .build();
    var setup = SslSetup.build(listen, Set.of("*.example.org"), null, new ConfigFiles());
    assertThat(setup.map("tenant.example.org")).isNotSameAs(setup.map("example.org"));
  }

  @Test
  void missingHosts() {
    ListenConfig listenConfig =
        ListenConfig.builder()
            .letsEncrypt(true)
            .letsEncryptPrefix("classpath:/letsencrypt/live/")
            .build();
    assertThatThrownBy(() -> build(listenConfig))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("SSL setup missing");
  }

  @Test
  void missingSetup() {
    ListenConfig listenConfig = ListenConfig.builder().hosts(List.of("example.org")).build();
    assertThatThrownBy(() -> build(listenConfig))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("SSL setup missing");
  }

  /// Without a default, the first SNI context is the default
  @Test
  void sniSetup() {
    ListenConfig listenConfig =
        ListenConfig.builder()
            .hosts(List.of("example.org"))
            .snis(
                List.of(
                    SniConfig.builder().host("example.org").ssl(ConfigReloadTest.TEST_SSL).build()))
            .build();
    var setup = build(listenConfig);
    assertThat(setup.map("example.org")).isNotNull().isSameAs(setup.map(null));
  }

  @Test
  void letsEncrypt() {
    ListenConfig listenConfig =
        ListenConfig.builder()
            .hosts(List.of("example.org"))
            .letsEncrypt(true)
            .letsEncryptPrefix("classpath:/letsencrypt/live/")
            .build();
    assertThat(build(listenConfig).map("example.org")).isNotNull();
  }

  @Test
  void missingKey() {
    SslConfig cfg =
        SslConfig.builder().certPath("classpath:ssl/test.crt").keyPath("classpath:nope").build();
    assertThatThrownBy(() -> build(ListenConfig.builder().ssl(cfg).build()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Cannot read classpath:nope")
        .hasCauseInstanceOf(FileNotFoundException.class);
  }

  /// Writes a newly generated private key as PKCS#8 PEM, the format of `ssl/test.key`, for a key
  /// that belongs to no certificate
  static Path writeNewKey(String algorithm, Path dir) throws IOException, NoSuchAlgorithmException {
    byte[] encoded =
        KeyPairGenerator.getInstance(algorithm).generateKeyPair().getPrivate().getEncoded();
    String pem =
        "-----BEGIN PRIVATE KEY-----\n"
            + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(encoded)
            + "\n-----END PRIVATE KEY-----\n";
    return Files.writeString(dir.resolve(algorithm.toLowerCase(Locale.ROOT) + ".key"), pem);
  }

  // takeover of unchanged contexts

  @TempDir Path dir;

  @Test
  void equalConfigInOneBuildSharesTheContext() {
    SslSetup setup =
        build(
            ListenConfig.builder()
                .ssl(ConfigReloadTest.TEST_SSL)
                .snis(
                    List.of(new SniConfig("a.com", ConfigReloadTest.TEST_SSL.toBuilder().build())))
                .build());
    assertThat(setup.map("a.com")).isSameAs(setup.map(null));
  }

  /// A class path certificate cannot change, its bundle is shared by all builds of the JVM, also
  /// without a previous setup
  @Test
  void classPathBundleIsSharedAcrossBuilds() {
    ListenConfig listen = ListenConfig.builder().ssl(ConfigReloadTest.TEST_SSL).build();
    assertThat(build(listen).map(null)).isSameAs(build(listen).map(null));
  }

  /// A file can change, its bundle is only taken over from the previous setup, never shared;
  /// also when only the key is a file
  @Test
  void fileBundleIsNotSharedWithoutPrevious() throws IOException {
    SslFiles ssl = copyKeyAndCertificate();
    assertThat(build(ssl.listen()).map(null)).isNotSameAs(build(ssl.listen()).map(null));
    ListenConfig fileKey =
        ListenConfig.builder()
            .ssl(ConfigReloadTest.TEST_SSL.toBuilder().keyPath(ssl.key().toString()).build())
            .build();
    assertThat(build(fileKey).map(null)).isNotSameAs(build(fileKey).map(null));
  }

  /// The files of a context taken over are recorded in the next build, so the watcher keeps
  /// watching them
  @Test
  void unchangedContextIsTakenOver() throws IOException {
    SslFiles ssl = copyKeyAndCertificate();
    SslSetup first = build(ssl.listen());
    ConfigFiles files = new ConfigFiles();
    SslSetup next = SslSetup.build(ssl.listen(), Set.of(), first, files);
    assertThat(next.map(null)).isSameAs(first.map(null));
    assertThat(files.snapshot().files()).containsKeys(ssl.cert(), ssl.key());
  }

  /// A renewed certificate, replaced by a rename like Kubernetes and certbot do
  @Test
  void changedFileIsLoadedAgain() throws IOException {
    SslFiles ssl = copyKeyAndCertificate();
    SslSetup first = build(ssl.listen());
    Path renewed = copy("ssl/test.crt", dir.resolve("renewed.crt"));
    Files.move(
        renewed, ssl.cert(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    SslSetup next = SslSetup.build(ssl.listen(), Set.of(), first, new ConfigFiles());
    assertThat(next.map(null)).isNotSameAs(first.map(null));
  }

  /// A missing key is recorded in the build's files, so the reload is tried again when it appears
  @Test
  void missingFileIsRecorded() throws IOException {
    SslFiles ssl = copyKeyAndCertificate();
    Files.delete(ssl.key());
    ConfigFiles files = new ConfigFiles();
    assertThatThrownBy(() -> SslSetup.build(ssl.listen(), Set.of(), null, files))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(files.snapshot().files())
        .containsEntry(ssl.key(), ConfigFiles.Fingerprint.ABSENT)
        .containsKey(ssl.cert());
  }

  // certificate log

  @Test
  void changes() {
    Map<String, String> previous = Map.of("a.com", "A1", "b.com", "B", "c.com", "C");
    Map<String, String> current = Map.of("a.com", "A2", "b.com", "B", "d.com", "D");
    assertThat(SslSetup.changes(previous, current))
        .isEqualTo(
            "\n  changed a.com: A2"
                + "\n    was: A1"
                + "\n  removed c.com: C"
                + "\n  added d.com: D");
    assertThat(SslSetup.changes(previous, previous)).isEmpty();
  }

  /// The hosts sorted, the default under its pseudo host among them
  @Test
  void list() {
    Map<String, String> descriptionsByHost =
        Map.of("e.com", "E", SslSetup.DEFAULT_HOST, "D", "a.com", "A");
    assertThat(SslSetup.list(descriptionsByHost))
        .isEqualTo("\n  a.com: A\n  default_host: D\n  e.com: E");
  }

  @Test
  void invalidCertificateIsRejected() throws IOException {
    SslFiles ssl = copyKeyAndCertificate();
    Files.writeString(ssl.cert(), "-----BEGIN CERTIFICATE-----\nhalf written");
    ConfigFiles files = new ConfigFiles();
    assertThatThrownBy(() -> SslSetup.build(ssl.listen(), Set.of(), null, files))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Invalid certificate: " + ssl.cert());
    assertThat(files.snapshot().files()).containsKeys(ssl.cert(), ssl.key());
  }

  private static SslSetup build(ListenConfig listen) {
    return SslSetup.build(listen, Set.of(), null, new ConfigFiles());
  }

  /// Key and certificate as files in the temporary directory, class path resources are not
  /// recorded
  record SslFiles(Path cert, Path key) {

    ListenConfig listen() {
      return ListenConfig.builder()
          .ssl(SslConfig.builder().certPath(cert.toString()).keyPath(key.toString()).build())
          .build();
    }
  }

  private SslFiles copyKeyAndCertificate() throws IOException {
    return new SslFiles(
        copy("ssl/test.crt", dir.resolve("test.crt")),
        copy("ssl/test.key", dir.resolve("test.key")));
  }

  private static Path copy(String resource, Path target) throws IOException {
    try (InputStream in = SslSetupTest.class.getResourceAsStream("/" + resource)) {
      Files.copy(in, target);
    }
    return target;
  }
}
