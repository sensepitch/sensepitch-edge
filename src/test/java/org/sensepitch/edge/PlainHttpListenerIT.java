package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.sensepitch.edge.TestingParameters.TIMEOUT;
import static org.sensepitch.edge.TestingParameters.await;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Isolated;

/// The plain HTTP listener on a real TCP port: `listen.httpPort` opens a second listener that
/// answers with a permanent redirect to the HTTPS URL on `listen.httpsPort`, and both listeners
/// close on shutdown. The redirect target itself is covered by
/// [HttpToHttpsRedirectHandlerBDDTest].
///
/// @author Jens Wilke
@Timeout(value = 40, unit = TimeUnit.SECONDS)
// alone: captures the log of all threads, the proxy logs from its event loops
@Isolated
class PlainHttpListenerIT {

  private Proxy proxy;
  private int httpsPort;
  private int httpPort;

  @AfterEach
  void stopProxy() {
    if (proxy != null) {
      proxy.shutdown();
    }
  }

  void startProxy() throws Exception {
    httpsPort = freePort();
    httpPort = freePort();
    ProxyConfig config =
        ProxyConfig.builder()
            .listen(
                ListenConfig.builder()
                    .ssl(
                        SslConfig.builder()
                            .keyPath("classpath:ssl/test.key")
                            .certPath("classpath:ssl/test.crt")
                            .build())
                    .address("127.0.0.1")
                    .httpsPort(httpsPort)
                    .httpPort(httpPort)
                    .build())
            .sites(
                Map.of(
                    "example.com",
                    SiteConfig.builder()
                        .response(ResponseConfig.builder().text("a test response").build())
                        .protection(ProtectionConfig.builder().disable(true).build())
                        .build()))
            .metrics(MetricsConfig.builder().enable(false).build())
            .build();
    proxy = new Proxy(config, new RequestLogCapture());
    Thread thread =
        new Thread(
            () -> {
              try {
                proxy.start();
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            },
            "proxy-under-test");
    thread.setDaemon(true);
    thread.start();
    awaitListening();
  }

  @Test
  void plainHttpRequestGetsPermanentRedirect() throws Exception {
    startProxy();
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress("127.0.0.1", httpPort), (int) TIMEOUT.toMillis());
      socket.setSoTimeout((int) TIMEOUT.toMillis());
      socket
          .getOutputStream()
          .write(
              "GET /path?q=1 HTTP/1.1\r\nHost: example.com\r\nConnection: close\r\n\r\n"
                  .getBytes(StandardCharsets.US_ASCII));
      socket.getOutputStream().flush();
      String response =
          new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
      assertThat(response).startsWith("HTTP/1.1 308");
      assertThat(response.toLowerCase(Locale.ROOT))
          .contains("location: https://example.com:" + httpsPort + "/path?q=1");
    }
  }

  @Test
  void shutdownClosesBothListeners() throws Exception {
    startProxy();
    proxy.shutdown();
    for (int port : new int[] {httpPort, httpsPort}) {
      try (Socket probe = new Socket()) {
        assertThatThrownBy(
                () ->
                    probe.connect(
                        new InetSocketAddress("127.0.0.1", port), (int) TIMEOUT.toMillis()))
            .isInstanceOf(IOException.class);
      }
    }
  }

  private static int freePort() throws IOException {
    try (ServerSocket probe = new ServerSocket(0)) {
      return probe.getLocalPort();
    }
  }

  /// Waits for both listeners before a test opens its client connection.
  private void awaitListening() throws Exception {
    for (int port : new int[] {httpsPort, httpPort}) {
      await(
          "proxy binds port " + port,
          () -> {
            try (Socket probe = new Socket()) {
              probe.connect(new InetSocketAddress("127.0.0.1", port), 250);
              return true;
            } catch (IOException notUpYet) {
              return false;
            }
          });
    }
  }
}
