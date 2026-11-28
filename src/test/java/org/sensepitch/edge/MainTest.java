package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.sensepitch.edge.TestingParameters.EVENT_TIMEOUT_SECONDS;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// Loading the configuration file given on the command line, and the start and shutdown of the
/// [Main.Application] in this JVM, see [Main]. [ShadedJarIT] runs the packaged JAR as a process.
///
/// @author Jens Wilke
class MainTest {

  static final String SITE_YAML =
      """
      listen:
        address: 127.0.0.1
        httpsPort: %d
        ssl:
          certPath: classpath:ssl/test.crt
          keyPath: classpath:ssl/test.key
      metrics:
        disabled: true
      admin:
        address: 127.0.0.1
        port: %d
      protection:
        disable: true
      sites:
        localhost:
          response:
            text: ok
      #END
      """;

  final HttpClient client = HttpClient.newHttpClient();
  final AtomicInteger loggingStops = new AtomicInteger();

  /// Started with a configuration file, the admin endpoint reloads it. The shutdown stops the
  /// proxy, which ends the run, then the admin server, then the logging, once.
  @Test
  void startAndShutdownWithConfigFile(@TempDir Path dir) throws Exception {
    int port = Loopback.freePort();
    int adminPort = Loopback.freePort();
    Path file = Files.writeString(dir.resolve("edge.yaml"), SITE_YAML.formatted(port, adminPort));
    Main.Application app =
        Main.Application.create(
            new String[] {file.toString()},
            Map.of(),
            new RequestLogCapture(),
            loggingStops::incrementAndGet);
    FutureTask<Void> run = runAndAwaitListening(app, port);
    assertThat(admin(adminPort, "GET", "/healthz")).isEqualTo(200);
    assertThat(admin(adminPort, "POST", "/reload")).isEqualTo(200);
    assertShutdownInOrder(app, run, adminPort);
  }

  /// Without a file the configuration comes from the `SENSEPITCH_EDGE_*` variables, a reload is
  /// refused since there is nothing to read again
  @Test
  void startAndShutdownWithEnvironment() throws Exception {
    int port = Loopback.freePort();
    int adminPort = Loopback.freePort();
    String prefix = Main.ENV_PREFIX;
    Map<String, String> env =
        Map.of(
            prefix + "LISTEN_ADDRESS", "127.0.0.1",
            prefix + "LISTEN_HTTPS_PORT", Integer.toString(port),
            prefix + "LISTEN_SSL_CERT_PATH", "classpath:ssl/test.crt",
            prefix + "LISTEN_SSL_KEY_PATH", "classpath:ssl/test.key",
            prefix + "METRICS_DISABLED", "true",
            prefix + "PROTECTION_DISABLE", "true",
            prefix + "SITES_0_KEY", "localhost",
            prefix + "SITES_0_RESPONSE_TEXT", "ok",
            prefix + "ADMIN_ADDRESS", "127.0.0.1",
            prefix + "ADMIN_PORT", Integer.toString(adminPort));
    Main.Application app =
        Main.Application.create(
            new String[0], env, new RequestLogCapture(), loggingStops::incrementAndGet);
    FutureTask<Void> run = runAndAwaitListening(app, port);
    assertThat(admin(adminPort, "POST", "/reload")).isEqualTo(409);
    assertShutdownInOrder(app, run, adminPort);
  }

  /// Runs the application as `main` does, on its own thread, until the listener accepts
  private static FutureTask<Void> runAndAwaitListening(Main.Application app, int port)
      throws Exception {
    FutureTask<Void> run =
        new FutureTask<>(
            () -> {
              app.run();
              return null;
            });
    Thread.ofPlatform().name("main-under-test").start(run);
    // no hook for the bind, polling is the fallback; the run ends early only on a failure
    TestingParameters.await("proxy listening", () -> run.isDone() || accepts(port));
    assertThat(run.isDone()).as("run failed early").isFalse();
    return run;
  }

  /// The shutdown as the hook of `main` runs it: the run returns, the admin server is stopped and
  /// the logging is stopped once, also after the second call by the ending run
  private void assertShutdownInOrder(Main.Application app, FutureTask<Void> run, int adminPort)
      throws Exception {
    app.shutdown().run();
    run.get(EVENT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertThat(app.proxy().isShuttingDown()).isTrue();
    assertThat(accepts(adminPort)).as("admin server stopped").isFalse();
    assertThat(loggingStops).hasValue(1);
  }

  private static boolean accepts(int port) {
    try (Socket socket = new Socket("127.0.0.1", port)) {
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  private int admin(int port, String method, String path) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .method(method, HttpRequest.BodyPublishers.noBody())
            .build();
    return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
  }

  @Test
  void loadConfig(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("edge.yaml");
    Files.writeString(file, "listen:\n  httpsPort: 8443\nsites:\n  www.example.com:\n");
    ProxyConfig cfg = Main.loadConfig(file);
    assertThat(cfg.listen().httpsPort()).isEqualTo(8443);
    assertThat(cfg.sites()).containsKey("www.example.com");
  }

  @Test
  void missingConfigFile(@TempDir Path dir) {
    Path missing = dir.resolve("missing.yaml");
    assertThatThrownBy(() -> Main.loadConfig(missing))
        .isInstanceOf(UncheckedIOException.class)
        .hasMessageContaining("missing.yaml");
  }
}
