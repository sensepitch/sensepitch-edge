package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.sensepitch.edge.TestingParameters.EVENT_TIMEOUT;
import static org.sensepitch.edge.TestingParameters.EVENT_TIMEOUT_SECONDS;
import static org.sensepitch.edge.TestingParameters.OUT_OF_REACH_MILLIS;
import static org.sensepitch.edge.TestingParameters.TEST_TIMEOUT_SECONDS;
import static org.sensepitch.edge.TestingParameters.await;

import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.AbstractMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.parallel.Isolated;

/// Graceful shutdown on a real TCP port: the listener closes, requests in flight complete with
/// `Connection: close`, idle connections close at once, and the request log is sent before the
/// shutdown returns. The OTLP destination flushes only every minute, so its records arrive only
/// because the event loops write their end markers when they terminate.
///
/// @author Jens Wilke
@Timeout(TEST_TIMEOUT_SECONDS)
// alone: captures the log of all threads, the proxy logs from its event loops
@Isolated
class ProxyShutdownIT {

  @RegisterExtension LogCapture logs = LogCapture.ofAllThreads();

  private final RequestLogCapture requestLog = new RequestLogCapture();
  private final CountDownLatch upstreamGotRequest = new CountDownLatch(1);
  private final CountDownLatch testOver = new CountDownLatch(1);
  private final AtomicInteger otlpRecords = new AtomicInteger();

  private volatile long upstreamDelayMillis;

  /// Set by a test that waits for the drain to reach the in-flight connection, otherwise `null`
  private volatile CountDownLatch shutdownDrainReached;

  private HttpServer upstream;
  private HttpServer logServer;
  private Proxy proxy;
  private ProxyConfig config;
  private int port;

  /// @param drainTimeoutMillis `null` for the default, a third of the shutdown timeout
  void startProxy(long shutdownTimeoutMillis, Long drainTimeoutMillis) throws Exception {
    startProxy(shutdownTimeoutMillis, drainTimeoutMillis, System::nanoTime, LockSupport::parkNanos);
  }

  void startProxy(
      long shutdownTimeoutMillis, Long drainTimeoutMillis, LongSupplier clock, LongConsumer wait)
      throws Exception {
    upstream = Loopback.httpServer();
    upstream.createContext(
        "/",
        exchange -> {
          upstreamGotRequest.countDown();
          try {
            CountDownLatch drainReached = shutdownDrainReached;
            if (drainReached == null) {
              // the delay ends early when the test is over, otherwise stopping the upstream
              // waits for the rest of it
              testOver.await(upstreamDelayMillis, TimeUnit.MILLISECONDS);
            } else {
              // lets this test answer only once the response will close the connection
              drainReached.await(EVENT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          byte[] body = "from upstream".getBytes(StandardCharsets.US_ASCII);
          try {
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
          } catch (IOException closedByTheProxy) {
            // the proxy gave up on the request
          }
          exchange.close();
        });
    upstream.start();
    logServer = Loopback.httpServer();
    logServer.createContext(
        "/v1/logs",
        exchange -> {
          ExportLogsServiceRequest request = OtlpTestSupport.decode(exchange);
          otlpRecords.addAndGet(request.getResourceLogs(0).getScopeLogs(0).getLogRecordsCount());
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    logServer.start();
    // probed after the helper servers have their ports: probed earlier, one of them could be
    // given the port just released, the proxy then fails to bind and the test talks to the helper
    port = Loopback.freePort();
    ProtectionConfig noProtection = ProtectionConfig.builder().disable(true).build();
    config =
        ProxyConfig.builder()
            .listen(
                ListenConfig.builder()
                    .ssl(
                        SslConfig.builder()
                            .keyPath("classpath:ssl/test.key")
                            .certPath("classpath:ssl/test.crt")
                            .build())
                    .address("127.0.0.1")
                    .httpsPort(port)
                    .build())
            .sites(
                Map.of(
                    "example.com",
                    SiteConfig.builder()
                        .response(ResponseConfig.builder().text("a test response").build())
                        .protection(noProtection)
                        .build(),
                    "upstream.com",
                    SiteConfig.builder()
                        .upstream(
                            UpstreamConfig.builder()
                                .target("127.0.0.1:" + upstream.getAddress().getPort())
                                .build())
                        .protection(noProtection)
                        .build()))
            .requestLog(
                RequestLogConfig.builder()
                    .drainTimeoutMillis(drainTimeoutMillis)
                    .destinations(
                        List.of(
                            RequestLogDestinationConfig.builder().type("stdout").build(),
                            RequestLogDestinationConfig.builder()
                                .type("otlp")
                                .otlp(
                                    OtlpConfig.builder()
                                        .endpoint(
                                            "http://127.0.0.1:"
                                                + logServer.getAddress().getPort()
                                                + "/v1/logs")
                                        .flushIntervalMillis(OUT_OF_REACH_MILLIS)
                                        .build())
                                .build()))
                    .build())
            .metrics(MetricsConfig.builder().enable(false).build())
            .shutdownTimeoutMillis(shutdownTimeoutMillis)
            .build();
    proxy =
        new Proxy(
            config,
            requestLog,
            clock,
            wait,
            ignored -> {
              CountDownLatch drainReached = shutdownDrainReached;
              if (drainReached != null) {
                drainReached.countDown();
              }
            });
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

  @AfterEach
  void stop() {
    if (proxy != null) {
      proxy.shutdown();
    }
    testOver.countDown();
    if (upstream != null) {
      TestingParameters.stop(upstream);
    }
    if (logServer != null) {
      TestingParameters.stop(logServer);
    }
  }

  /// A request in flight completes, its response closes the connection, and it is logged in all
  /// destinations before the shutdown returns. The upstream answers only after the drain reached
  /// the connection, so the response follows the `Connection: close` decision. No phase runs into
  /// its timeout: each one would log a `Shutdown:` diagnostic.
  @Test
  void requestInFlightCompletes() throws Exception {
    CountDownLatch drainReached = new CountDownLatch(1);
    shutdownDrainReached = drainReached;
    startProxy(10_000, null);
    try (SSLSocket socket = tlsSocket()) {
      send(socket, "upstream.com");
      assertThat(upstreamGotRequest.await(EVENT_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
      CompletableFuture<Void> shutdown = CompletableFuture.runAsync(proxy::shutdown);
      assertThat(drainReached.await(EVENT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
          .as("shutdown drain reached the in-flight connection")
          .isTrue();
      String response = readResponse(socket.getInputStream());
      assertThat(response).startsWith("HTTP/1.1 200").contains("from upstream");
      assertThat(response.toLowerCase(Locale.ROOT)).contains("connection: close");
      assertThat(socket.getInputStream().read()).as("closed after the response").isEqualTo(-1);
      shutdown.get(EVENT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
    assertThat(requestLog.lines())
        .singleElement()
        .asString()
        .contains("\"http.response.status_code\":200");
    assertThat(otlpRecords.get()).as("sent before the shutdown returned").isOne();
    assertThat(logs.messages(Proxy.class))
        .noneMatch(m -> m.startsWith("Shutdown:"))
        .anySatisfy(
            m ->
                assertThat(m)
                    .startsWith(Proxy.SHUTDOWN_STARTED)
                    .endsWith(
                        "connections 1, requests in flight 1," + " unsent request log records 0"));
  }

  /// An idle keep-alive connection is closed at once, the shutdown does not wait for its timeout
  @Test
  void idleConnectionIsClosed() throws Exception {
    startProxy(10_000, null);
    try (SSLSocket socket = tlsSocket()) {
      send(socket, "example.com");
      assertThat(readResponse(socket.getInputStream()))
          .startsWith("HTTP/1.1 200")
          .contains("a test response");
      proxy.shutdown();
      assertThat(socket.getInputStream().read()).isEqualTo(-1);
    }
    assertThat(otlpRecords.get()).isOne();
    assertThat(logs.messages(Proxy.class)).noneMatch(m -> m.startsWith("Shutdown:"));
  }

  /// A request that takes longer than the shutdown allows is aborted when only the drain timeout
  /// is left. Its record, status 499, is still sent within the drain timeout, and the requests in
  /// flight are reported. The request log is drained, so that phase logs no diagnostic. The drain
  /// timeout is the full [TestingParameters#EVENT_TIMEOUT], so a delayed post cannot run out of it; the
  /// drain ends as soon as the record is sent.
  @Test
  void slowRequestIsAbortedAndStillLogged() throws Exception {
    startProxy(1_000 + EVENT_TIMEOUT.toMillis(), EVENT_TIMEOUT.toMillis());
    // answers only when the test is over, long after the abort
    upstreamDelayMillis = OUT_OF_REACH_MILLIS;
    try (SSLSocket socket = tlsSocket()) {
      send(socket, "upstream.com");
      assertThat(upstreamGotRequest.await(EVENT_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
      long start = System.nanoTime();
      proxy.shutdown();
      assertThat(millisSince(start)).as("waited for the request").isGreaterThanOrEqualTo(900);
    }
    assertThat(requestLog.lines())
        .singleElement()
        .asString()
        .contains("\"http.response.status_code\":499");
    assertThat(otlpRecords.get()).as("the aborted request is sent too").isOne();
    assertThat(logs.messages(Proxy.class))
        .anySatisfy(
            m ->
                assertThat(m)
                    .contains("requests still in flight after")
                    .contains("ms, total 1, by upstream {sites.upstream.com.upstream=1}"));
    assertThat(logs.messages(Proxy.class))
        .noneMatch(m -> m.startsWith("Shutdown: request log not drained"));
  }

  /// With the log server down, the shutdown ends at its timeout and reports the records not sent
  @Test
  void unsentRecordsAreReported() throws Exception {
    startProxy(1_000, 500L);
    try (SSLSocket socket = tlsSocket()) {
      send(socket, "example.com");
      assertThat(readResponse(socket.getInputStream())).startsWith("HTTP/1.1 200");
    }
    logServer.stop(0);
    long start = System.nanoTime();
    proxy.shutdown();
    assertThat(millisSince(start)).as("waited for the drain timeout").isGreaterThanOrEqualTo(400);
    assertThat(logs.messages(Proxy.class))
        .anySatisfy(
            m ->
                assertThat(m)
                    .contains("request log not drained after")
                    .contains("ms, unsent request log records 1"));
  }

  /// A reload after the shutdown started is rejected, it would create destinations that are
  /// never finished, and end markers for loops that are gone
  @Test
  void reloadAfterShutdownIsRejected() throws Exception {
    startProxy(1_000, null);
    proxy.shutdown();
    ReloadResult result = proxy.reload(config);
    assertThat(result.applied()).isFalse();
    assertThat(result.message()).isEqualTo("Shutting down");
  }

  /// A shutdown waits for an actual reload paused while reading its configuration. The test
  /// awaits the shutdown thread blocked on the monitor the paused reload thread owns, a state
  /// frozen while the reload is held, unlike a counter or signal before the lock. While it waits,
  /// the listener is still open. Releasing the reload lets both end.
  @Test
  void shutdownWaitsForReload() throws Exception {
    startProxy(10_000, null);
    CountDownLatch readingConfig = new CountDownLatch(1);
    AtomicReference<Thread> reloadThread = new AtomicReference<>();
    CountDownLatch continueReload = new CountDownLatch(1);
    Map<String, SiteConfig> pausedSites =
        new AbstractMap<>() {
          @Override
          public Set<Entry<String, SiteConfig>> entrySet() {
            reloadThread.set(Thread.currentThread());
            readingConfig.countDown();
            try {
              assertThat(continueReload.await(EVENT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                  .as("reload configuration released")
                  .isTrue();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              throw new AssertionError("reload interrupted", e);
            }
            return config.sites().entrySet();
          }
        };
    ProxyConfig next = config.toBuilder().sites(pausedSites).build();
    CompletableFuture<ReloadResult> reload =
        CompletableFuture.supplyAsync(() -> proxy.reload(next));
    FutureTask<Void> shutdown = new FutureTask<>(proxy::shutdown, null);
    try {
      assertThat(readingConfig.await(EVENT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
          .as("reload reading configuration")
          .isTrue();
      Thread shutdownThread = Thread.ofPlatform().name("shutdown-during-reload").start(shutdown);
      ThreadMXBean threads = ManagementFactory.getThreadMXBean();
      long reloadThreadId = reloadThread.get().threadId();
      TestingParameters.await(
          "shutdown blocked on the lock the reload holds",
          () -> {
            ThreadInfo info = threads.getThreadInfo(shutdownThread.threadId());
            return info != null
                && info.getThreadState() == Thread.State.BLOCKED
                && info.getLockOwnerId() == reloadThreadId;
          });
      assertThat(shutdown.isDone()).isFalse();
      assertThat(proxy.isShuttingDown()).isFalse();
      try (Socket probe = new Socket()) {
        probe.connect(new InetSocketAddress("127.0.0.1", port), (int) EVENT_TIMEOUT.toMillis());
      }
    } finally {
      continueReload.countDown();
    }
    assertThat(reload.get(EVENT_TIMEOUT_SECONDS, TimeUnit.SECONDS).applied()).isTrue();
    shutdown.get(EVENT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertThat(proxy.isShuttingDown()).isTrue();
    assertThat(proxy.reload(config).applied()).isFalse();
  }

  /// During the accept grace the proxy serves new and keep-alive traffic while readiness is false.
  /// The fake grace consumes the entire connection budget; a stalled request is then aborted and
  /// logged, while the reserved log budget remains available. No real sleep is used.
  ///
  /// The fake clock stands still after the grace, so the later shutdown phases wait in real time.
  /// The drain budget is therefore [TestingParameters#OUT_OF_REACH], the test's own wait for the
  /// shutdown, [TestingParameters#EVENT_TIMEOUT], bounds it.
  @Test
  void acceptGraceKeepsServingThenClosesWithinTheBudget() throws Exception {
    AtomicLong now = new AtomicLong(1234);
    CountDownLatch delaying = new CountDownLatch(1);
    CountDownLatch releaseDelay = new CountDownLatch(1);
    long graceMillis = 7000;
    long drainMillis = OUT_OF_REACH_MILLIS;
    startProxy(
        graceMillis + drainMillis,
        drainMillis,
        now::get,
        nanos -> {
          assertThat(nanos).isEqualTo(TimeUnit.MILLISECONDS.toNanos(graceMillis));
          delaying.countDown();
          try {
            assertThat(releaseDelay.await(EVENT_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
          } catch (InterruptedException e) {
            throw new AssertionError(e);
          }
          now.addAndGet(nanos);
        });
    assertThat(
            proxy
                .reload(config.toBuilder().shutdownAcceptGraceMillis(graceMillis).build())
                .applied())
        .isTrue();
    AdminServer admin =
        AdminServer.start(new InetSocketAddress("127.0.0.1", 0), null, proxy::isShuttingDown);
    try (SSLSocket keepAlive = tlsSocket()) {
      send(keepAlive, "example.com");
      assertThat(readResponse(keepAlive.getInputStream())).startsWith("HTTP/1.1 200");
      CompletableFuture<Void> shutdown = CompletableFuture.runAsync(proxy::shutdown);
      try {
        assertThat(delaying.await(EVENT_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        var ready =
            HttpClient.newHttpClient()
                .send(
                    HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + admin.port() + "/readyz"))
                        .build(),
                    HttpResponse.BodyHandlers.ofString());
        assertThat(ready.statusCode()).isEqualTo(503);
        send(keepAlive, "example.com");
        assertThat(readResponse(keepAlive.getInputStream())).startsWith("HTTP/1.1 200");
        try (SSLSocket fresh = tlsSocket()) {
          send(fresh, "example.com");
          assertThat(readResponse(fresh.getInputStream())).startsWith("HTTP/1.1 200");
        }
        upstreamDelayMillis = OUT_OF_REACH_MILLIS;
        send(keepAlive, "upstream.com");
        assertThat(upstreamGotRequest.await(EVENT_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
      } finally {
        releaseDelay.countDown();
      }
      shutdown.get(EVENT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertThat(logs.messages(Proxy.class)).noneMatch(m -> m.contains("request log not drained"));
      assertThat(logs.messages(Proxy.class))
          .anyMatch(m -> m.contains("requests still in flight after " + graceMillis + " ms"));
      assertThat(otlpRecords.get()).isEqualTo(4);
      assertThat(requestLog.lines()).hasSize(4);
      try (Socket probe = new Socket()) {
        assertThatThrownBy(
                () ->
                    probe.connect(
                        new InetSocketAddress("127.0.0.1", port), (int) EVENT_TIMEOUT.toMillis()))
            .isInstanceOf(IOException.class);
      }
    } finally {
      releaseDelay.countDown();
      admin.stop();
    }
  }

  static void send(SSLSocket socket, String host) throws IOException {
    socket.startHandshake();
    socket
        .getOutputStream()
        .write(
            ("GET / HTTP/1.1\r\nHost: " + host + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
    socket.getOutputStream().flush();
  }

  /// Reads one response with a `Content-Length`, the connection may stay open
  static String readResponse(InputStream in) throws IOException {
    ByteArrayOutputStream head = new ByteArrayOutputStream();
    while (!head.toString(StandardCharsets.US_ASCII).endsWith("\r\n\r\n")) {
      int b = in.read();
      if (b < 0) {
        throw new IOException("closed in the header: " + head);
      }
      head.write(b);
    }
    String header = head.toString(StandardCharsets.US_ASCII);
    int length = 0;
    for (String line : header.split("\r\n")) {
      if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
        length = Integer.parseInt(line.substring("content-length:".length()).trim());
      }
    }
    return header + new String(in.readNBytes(length), StandardCharsets.US_ASCII);
  }

  static long millisSince(long startNanos) {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
  }

  private SSLSocket tlsSocket() throws Exception {
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
    socket.connect(new InetSocketAddress("127.0.0.1", port), (int) EVENT_TIMEOUT.toMillis());
    socket.setSoTimeout((int) EVENT_TIMEOUT.toMillis());
    return socket;
  }

  /// Waits for the listener before a test opens its client connection.
  private void awaitListening() throws Exception {
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
