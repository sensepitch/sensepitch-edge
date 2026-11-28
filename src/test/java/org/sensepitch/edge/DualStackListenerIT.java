package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.sensepitch.edge.TestingParameters.EVENT_TIMEOUT;
import static org.sensepitch.edge.TestingParameters.EVENT_TIMEOUT_SECONDS;
import static org.sensepitch.edge.TestingParameters.TEST_TIMEOUT_SECONDS;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/// The HTTPS listener on a real dual-stack socket: `listen.address` `"::"` accepts IPv4 and IPv6
/// clients through one listening channel, and an IPv4 client keeps its plain IPv4 representation in
/// the client-facing parts of the proxy.
///
/// @author Jens Wilke
@Timeout(TEST_TIMEOUT_SECONDS)
class DualStackListenerIT {

  private static final String HOST = "example.com";
  private static final String IPV4_LOOPBACK = "127.0.0.1";
  private static final String IPV6_LOOPBACK = "::1";
  private static final String IPV6_LOOPBACK_HOST_ADDRESS = "0:0:0:0:0:0:0:1";

  private final BlockingQueue<String> ipTraitsHeaders = new LinkedBlockingQueue<>();
  private final BlockingQueue<String> forwardedForHeaders = new LinkedBlockingQueue<>();
  private final RequestLogCapture requestLog = new RequestLogCapture();

  private Proxy proxy;
  private HttpServer upstream;
  private int port;
  @TempDir private Path temporaryDirectory;

  @BeforeEach
  void startProxy() throws Exception {
    upstream = Loopback.httpServer();
    upstream.createContext(
        "/",
        exchange -> {
          forwardedForHeaders.add(exchange.getRequestHeaders().getFirst("X-Forwarded-For"));
          ipTraitsHeaders.add(exchange.getRequestHeaders().getFirst(IpTraitsHandler.TRAITS_HEADER));
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    Path countryDatabase = temporaryDirectory.resolve("country.mmdb");
    TinyMmdb.writeGeoLite2Country(countryDatabase, "DE");
    upstream.start();
    port = Loopback.freePort();
    ProxyConfig config =
        ProxyConfig.builder()
            .listen(
                ListenConfig.builder()
                    .ssl(
                        SslConfig.builder()
                            .keyPath("classpath:ssl/test.key")
                            .certPath("classpath:ssl/test.crt")
                            .build())
                    .address("::")
                    .httpsPort(port)
                    .build())
            .ipLookup(
                IpLookupConfig.builder()
                    .geoIp2(
                        GeoIp2Config.builder().countryDbPath(countryDatabase.toString()).build())
                    .build())
            .sites(
                Map.of(
                    HOST,
                    SiteConfig.builder()
                        .upstream(
                            UpstreamConfig.builder()
                                .target(IPV4_LOOPBACK + ":" + upstream.getAddress().getPort())
                                .build())
                        .protection(
                            ProtectionConfig.builder()
                                .bypass(new ProtectionBypassConfig(null, List.of(IPV4_LOOPBACK)))
                                .cookieGates(
                                    List.of(CookieGateConfig.builder().name("gate").build()))
                                .build())
                        .build()))
            .metrics(MetricsConfig.builder().disabled(true).build())
            .requestLog(RequestLogConfig.builder().plainText(true).build())
            .build();
    proxy = new Proxy(config, requestLog);
    startProxyThread();
    awaitBothListeners();
  }

  @AfterEach
  void stopProxy() throws Exception {
    if (proxy != null) {
      proxy.shutdown();
    }
    TestingParameters.stop(upstream);
  }

  @Test
  void ipv4ClientKeepsItsPlainAddress() throws Exception {
    request(IPV4_LOOPBACK);

    assertThat(take(forwardedForHeaders, "X-Forwarded-For")).isEqualTo(IPV4_LOOPBACK);
    assertThat(take(ipTraitsHeaders, "IP traits header")).contains("country=DE");
    requestLog.awaitLine("IPv4 request log record is written", line -> true);
    assertThat(requestLog.lines())
        .singleElement()
        .asString()
        .contains(" " + IPV4_LOOPBACK + " \"")
        .contains("\"config-ip-match\"")
        .doesNotContain("::ffff:" + IPV4_LOOPBACK);
  }

  @Test
  void ipv6ClientIsAcceptedByTheSameListener() throws Exception {
    request(IPV6_LOOPBACK, "Cookie: gate=1\r\n");

    assertThat(take(forwardedForHeaders, "X-Forwarded-For")).isEqualTo(IPV6_LOOPBACK_HOST_ADDRESS);
    requestLog.awaitLine("IPv6 request log record is written", line -> true);
    assertThat(requestLog.lines())
        .singleElement()
        .asString()
        .contains(" " + IPV6_LOOPBACK_HOST_ADDRESS + " \"")
        .contains("\"-\"")
        .doesNotContain("::ffff:" + IPV4_LOOPBACK);
  }

  private void request(String clientAddress, String... headers) throws Exception {
    try (SSLSocket socket = tlsSocket(clientAddress)) {
      socket.startHandshake();
      StringBuilder request = new StringBuilder();
      request.append("GET / HTTP/1.1\r\nHost: ").append(HOST).append("\r\n");
      for (String header : headers) {
        request.append(header);
      }
      request.append("Connection: close\r\n\r\n");
      socket.getOutputStream().write(request.toString().getBytes(StandardCharsets.US_ASCII));
      socket.getOutputStream().flush();
      assertThat(socket.getInputStream().readAllBytes())
          .describedAs("the proxy must answer the request")
          .isNotEmpty();
    }
  }

  private SSLSocket tlsSocket(String clientAddress) throws Exception {
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
        new SecureRandom());
    SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket();
    socket.connect(new InetSocketAddress(clientAddress, port), (int) EVENT_TIMEOUT.toMillis());
    socket.setSoTimeout((int) EVENT_TIMEOUT.toMillis());
    return socket;
  }

  private void startProxyThread() {
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
  }

  /// Waits until both client families can connect to the same configured port. A refused or failed
  /// connection means the listener is not up yet; the wait below reports whichever family did not
  /// become reachable.
  private void awaitBothListeners() throws Exception {
    awaitClientListener(IPV4_LOOPBACK);
    awaitClientListener(IPV6_LOOPBACK);
  }

  private void awaitClientListener(String clientAddress) throws Exception {
    TestingParameters.await(
        "listener accepts " + clientAddress,
        () -> {
          try (Socket probe = new Socket()) {
            probe.connect(
                new InetSocketAddress(clientAddress, port), (int) EVENT_TIMEOUT.toMillis());
            return true;
          } catch (IOException notUpYet) {
            return false;
          }
        });
  }

  private static <T> T take(BlockingQueue<T> queue, String description)
      throws InterruptedException {
    T value = queue.poll(EVENT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertThat(value).as(description).isNotNull();
    return value;
  }
}
