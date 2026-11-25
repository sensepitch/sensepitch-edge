package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.sensepitch.edge.TestingParameters.EVENT_TIMEOUT;
import static org.sensepitch.edge.TestingParameters.EVENT_TIMEOUT_SECONDS;
import static org.sensepitch.edge.TestingParameters.PROCESS_START_TIMEOUT;
import static org.sensepitch.edge.TestingParameters.TEST_TIMEOUT_SECONDS;
import static org.sensepitch.edge.TestingParameters.await;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/// Checks the fat JAR produced by the shade plugin, as it is deployed.
///
/// The proxy always uses [io.netty.handler.ssl.SslProvider#OPENSSL], so a TLS handshake with the
/// JAR started in a separate JVM proves that the tcnative library of the build host was found in
/// the JAR, extracted and loaded. The libraries of the other platforms cannot be loaded here, so
/// for those the JAR entry is checked to be a binary for the expected OS and CPU.
@Timeout(TEST_TIMEOUT_SECONDS)
class ShadedJarIT {

  private static Path jar;

  @TempDir Path dir;

  private Process process;

  @BeforeAll
  static void locateJar() {
    String property = System.getProperty("shadedJar");
    assertThat(property).as("system property shadedJar, set by failsafe").isNotNull();
    jar = Path.of(property);
    assertThat(jar).as("shaded JAR, built in the package phase").isRegularFile();
  }

  /// `java` with the arguments, plus the JaCoCo agent of this JVM if it has one, so the run of
  /// the shaded JAR counts for the coverage: its classes are the same bytes as `target/classes`,
  /// and the process ends with `SIGTERM`, which lets the agent write its data
  static List<String> javaCommand(String... args) {
    List<String> command = new ArrayList<>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
        .filter(arg -> arg.startsWith("-javaagent:") && arg.contains("org.jacoco.agent"))
        .forEach(command::add);
    command.addAll(List.of(args));
    return command;
  }

  @AfterEach
  void stopProcess() throws InterruptedException {
    if (process != null) {
      process.destroy();
      if (!process.waitFor(EVENT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        process.destroyForcibly();
      }
    }
  }

  @Test
  void servesHttpsWithHostNativeLibrary() throws Exception {
    int port = Loopback.freePort();
    Path config = writeConfig(port);
    Path stderr = dir.resolve("stderr.log");
    process =
        new ProcessBuilder(javaCommand("-jar", jar.toString(), config.toString()))
            .redirectOutput(dir.resolve("stdout.log").toFile())
            .redirectError(stderr.toFile())
            .start();
    awaitOutput(stderr, "Proxy listening on 127.0.0.1:" + port);

    String response = get(port);

    assertThat(response).startsWith("HTTP/1.1 200").endsWith("ok");
    List<String> lines = Files.readAllLines(stderr);
    assertThat(lines).anyMatch(line -> line.contains("Open SSL: BoringSSL"));
    // e.g. the JDK warning about restricted methods, when native access is not enabled
    assertThat(lines).noneMatch(line -> line.startsWith("WARNING"));
  }

  /// Without a configuration file the JAR reads its configuration from `SENSEPITCH_EDGE_*`
  /// environment variables, the way of a container without a mounted file. The admin endpoint
  /// runs, a reload is refused, there is no file to read again.
  @Test
  void configuredFromEnvironmentWithAdmin() throws Exception {
    int port = Loopback.freePort();
    int adminPort = Loopback.freePort();
    Path stderr = dir.resolve("stderr.log");
    ProcessBuilder builder =
        new ProcessBuilder(javaCommand("-jar", jar.toString()))
            .redirectOutput(dir.resolve("stdout.log").toFile())
            .redirectError(stderr.toFile());
    Map<String, String> env = builder.environment();
    env.keySet().removeIf(name -> name.startsWith(ENV_PREFIX));
    env.put(ENV_PREFIX + "LISTEN_ADDRESS", "127.0.0.1");
    env.put(ENV_PREFIX + "LISTEN_HTTPS_PORT", Integer.toString(port));
    env.put(ENV_PREFIX + "LISTEN_SSL_CERT_PATH", copyResource("ssl/test.crt").toString());
    env.put(ENV_PREFIX + "LISTEN_SSL_KEY_PATH", copyResource("ssl/test.key").toString());
    env.put(ENV_PREFIX + "METRICS_ENABLE", "false");
    env.put(ENV_PREFIX + "PROTECTION_DISABLE", "true");
    env.put(ENV_PREFIX + "SITES_0_KEY", "localhost");
    env.put(ENV_PREFIX + "SITES_0_RESPONSE_TEXT", "ok");
    env.put(ENV_PREFIX + "ADMIN_ADDRESS", "127.0.0.1");
    env.put(ENV_PREFIX + "ADMIN_PORT", Integer.toString(adminPort));
    process = builder.start();
    awaitOutput(stderr, "Proxy listening on 127.0.0.1:" + port);

    assertThat(get(port)).startsWith("HTTP/1.1 200").endsWith("ok");
    assertThat(adminStatus(adminPort, "GET", "/healthz")).isEqualTo(200);
    assertThat(adminStatus(adminPort, "POST", "/reload")).isEqualTo(409);
  }

  private static final String ENV_PREFIX = "SENSEPITCH_EDGE_";

  private static int adminStatus(int port, String method, String path) throws IOException {
    HttpURLConnection connection =
        (HttpURLConnection) URI.create("http://127.0.0.1:" + port + path).toURL().openConnection();
    try {
      connection.setRequestMethod(method);
      return connection.getResponseCode();
    } finally {
      connection.disconnect();
    }
  }

  /// `SIGTERM` shuts down gracefully: the request log is complete when the process exits, also the
  /// OTLP export, which flushes only every minute. With nothing left to wait for, no shutdown
  /// phase runs into its timeout, which would log a `Shutdown:` diagnostic.
  @Test
  void sigtermSendsTheRequestLogAndExits() throws Exception {
    AtomicInteger records = new AtomicInteger();
    HttpServer logServer = Loopback.httpServer();
    logServer.createContext(
        "/v1/logs",
        exchange -> {
          records.addAndGet(
              OtlpTestSupport.decode(exchange)
                  .getResourceLogs(0)
                  .getScopeLogs(0)
                  .getLogRecordsCount());
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    logServer.start();
    try {
      int port = Loopback.freePort();
      Path config =
          writeConfig(
              port,
              """
              requestLog:
                destinations:
                  - type: stdout
                  - type: otlp
                    otlp:
                      endpoint: http://127.0.0.1:%d/v1/logs
                      flushIntervalMillis: 60000
              """
                  .formatted(logServer.getAddress().getPort()));
      Path stderr = dir.resolve("stderr.log");
      Path stdout = dir.resolve("stdout.log");
      process =
          new ProcessBuilder(javaCommand("-jar", jar.toString(), config.toString()))
              .redirectOutput(stdout.toFile())
              .redirectError(stderr.toFile())
              .start();
      awaitOutput(stderr, "Proxy listening on 127.0.0.1:" + port);
      assertThat(get(port)).startsWith("HTTP/1.1 200");
      process.destroy();
      assertThat(process.waitFor(EVENT_TIMEOUT_SECONDS, TimeUnit.SECONDS)).as("exited").isTrue();
      assertThat(Files.readAllLines(stdout)).singleElement().asString().contains("\"url.path\"");
      assertThat(records.get()).as("OTLP record sent before the exit").isOne();
      assertThat(Files.readAllLines(stderr)).noneMatch(line -> line.contains("Shutdown:"));
    } finally {
      TestingParameters.stop(logServer);
    }
  }

  /// A blocked exporter forces a diagnostic at the end of the shutdown budget, after SIGTERM
  /// started all JVM hooks. Both deployed logging configurations must keep that message.
  ///
  /// The collector answers the export once the JVM has exited. The JDK server counts an exchange
  /// as ended only when its response is complete; a close without response headers drops the
  /// connection but leaves the exchange open, and [TestingParameters#stop] then waits its whole
  /// grace. With no body, `sendResponseHeaders` completes the response itself.
  @ParameterizedTest
  @ValueSource(strings = {"log4j2.xml", "log4j2-trace.xml"})
  void sigtermKeepsFinalShutdownDiagnostic(String loggingConfig) throws Exception {
    var exportStarted = new CountDownLatch(1);
    var releaseExport = new CountDownLatch(1);
    var exportAnswered = new CountDownLatch(1);
    var collector = Loopback.httpServer();
    collector.createContext(
        "/v1/logs",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          exportStarted.countDown();
          try {
            releaseExport.await(EVENT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, -1);
            exportAnswered.countDown();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } finally {
            exchange.close();
          }
        });
    collector.start();
    try {
      int port = Loopback.freePort();
      Path config =
          writeConfig(
              port,
              """
              shutdownTimeoutMillis: 1000
              requestLog:
                drainTimeoutMillis: 1000
                destinations:
                  - type: otlp
                    otlp:
                      endpoint: http://127.0.0.1:%d/v1/logs
                      flushIntervalMillis: 60000
              """
                  .formatted(collector.getAddress().getPort()));
      Path stderr = dir.resolve("stderr.log");
      process =
          new ProcessBuilder(
                  javaCommand(
                      "-Dlog4j2.configurationFile=classpath:" + loggingConfig,
                      "-jar",
                      jar.toString(),
                      config.toString()))
              .redirectOutput(dir.resolve("stdout.log").toFile())
              .redirectError(stderr.toFile())
              .start();
      awaitOutput(stderr, "Proxy listening on 127.0.0.1:" + port);
      assertThat(get(port)).startsWith("HTTP/1.1 200");
      process.destroy();
      assertThat(exportStarted.await(EVENT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
          .as("shutdown export started")
          .isTrue();
      assertThat(process.waitFor(EVENT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
          .as("exited after draining")
          .isTrue();
      releaseExport.countDown();
      assertThat(exportAnswered.await(EVENT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
          .as("collector answered the export, so the exchange ended before its stop")
          .isTrue();
      assertThat(Files.readString(stderr))
          .contains("Shutdown: request log not drained after")
          .contains("unsent records");
    } finally {
      releaseExport.countDown();
      TestingParameters.stop(collector);
    }
  }

  /// Checks the header of each bundled tcnative library: object file format and CPU type.
  @ParameterizedTest
  @CsvSource({
    "libnetty_tcnative_linux_x86_64.so,       ELF,    x86_64",
    "libnetty_tcnative_linux_aarch_64.so,     ELF,    aarch64",
    "libnetty_tcnative_osx_x86_64.jnilib,     Mach-O, x86_64",
    "libnetty_tcnative_osx_aarch_64.jnilib,   Mach-O, aarch64",
    "netty_tcnative_windows_x86_64.dll,       PE,     x86_64",
  })
  void containsNativeLibraryForPlatform(String name, String format, String cpu) throws IOException {
    byte[] header;
    try (JarFile file = new JarFile(jar.toFile())) {
      ZipEntry entry = file.getEntry("META-INF/native/" + name);
      assertThat(entry).as(name).isNotNull();
      try (InputStream in = file.getInputStream(entry)) {
        header = in.readNBytes(4096);
      }
    }
    assertThat(binaryType(header)).isEqualTo(format + " " + cpu);
  }

  /// A snapshot build gets the version of its commit, e.g. `0.1-20261112.153045-dbe0c1a` (commit
  /// time and id), a release build keeps the plain version.
  @Test
  void manifestHasUniqueVersion() throws IOException {
    String version;
    try (JarFile file = new JarFile(jar.toFile())) {
      version = file.getManifest().getMainAttributes().getValue("Implementation-Version");
    }
    assertThat(version).matches("\\d+(\\.\\d+)*(-\\d{8}\\.\\d{6}-[0-9a-f]{7})?");
  }

  /// Object file format and CPU of a native library, derived from its first bytes.
  static String binaryType(byte[] header) {
    ByteBuffer buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
    if (buf.getInt(0) == 0x464c457f) { // "\x7fELF"
      return "ELF " + cpuName(buf.getShort(18), 0x3e, 0xb7);
    }
    if (buf.getInt(0) == 0xfeedfacf) { // Mach-O 64 bit, little endian
      return "Mach-O " + cpuName(buf.getInt(4), 0x01000007, 0x0100000c);
    }
    if (buf.getShort(0) == 0x5a4d) { // "MZ", the PE header follows at offset e_lfanew
      int pe = buf.getInt(0x3c);
      assertThat(buf.getInt(pe)).as("PE signature").isEqualTo(0x00004550);
      return "PE " + cpuName(buf.getShort(pe + 4) & 0xffff, 0x8664, 0xaa64);
    }
    return "unknown";
  }

  private static String cpuName(int code, int x86_64, int aarch64) {
    if (code == x86_64) {
      return "x86_64";
    }
    if (code == aarch64) {
      return "aarch64";
    }
    return "cpu 0x" + Integer.toHexString(code);
  }

  /// Same shape as `performance-test/ja4-benchmark.yaml`: the response comes from the proxy
  /// itself, so no upstream is needed.
  private Path writeConfig(int port) throws IOException {
    return writeConfig(port, "");
  }

  /// @param extra YAML appended to the configuration
  private Path writeConfig(int port, String extra) throws IOException {
    Path cert = copyResource("ssl/test.crt");
    Path key = copyResource("ssl/test.key");
    String yaml =
        """
        listen:
          address: 127.0.0.1
          httpsPort: %d
          ssl:
            certPath: %s
            keyPath: %s
        metrics:
          enable: false
        protection:
          disable: true
        sites:
          localhost:
            response:
              text: ok
        """
                .formatted(port, cert, key)
            + extra;
    return Files.writeString(dir.resolve("config.yaml"), yaml);
  }

  /// The forked JVM sees only the JAR, not the test classpath.
  private Path copyResource(String name) throws IOException {
    Path target = dir.resolve(Path.of(name).getFileName());
    try (InputStream in = getClass().getClassLoader().getResourceAsStream(name)) {
      assertThat(in).as(name).isNotNull();
      Files.copy(in, target);
    }
    return target;
  }

  /// Waits for startup in the child JVM, reporting an early exit immediately.
  private void awaitOutput(Path log, String text) throws Exception {
    await(
        "JAR logs " + text,
        PROCESS_START_TIMEOUT,
        () -> {
          if (Files.readString(log).contains(text)) {
            return true;
          }
          if (!process.isAlive()) {
            throw new IllegalStateException(
                "JAR exited with " + process.exitValue() + ":\n" + Files.readString(log));
          }
          return false;
        });
  }

  private static String get(int port) throws Exception {
    SSLContext context = SSLContext.getInstance("TLS");
    // the test certificate is self-signed, validation is deliberately skipped
    context.init(
        null,
        new TrustManager[] {
          new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {}

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {}

            @Override
            public X509Certificate[] getAcceptedIssuers() {
              return new X509Certificate[0];
            }
          }
        },
        null);
    try (SSLSocket socket =
        (SSLSocket) context.getSocketFactory().createSocket("127.0.0.1", port)) {
      socket.setSoTimeout((int) EVENT_TIMEOUT.toMillis());
      socket
          .getOutputStream()
          .write(
              "GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
                  .getBytes(StandardCharsets.US_ASCII));
      socket.getOutputStream().flush();
      return new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
    }
  }
}
