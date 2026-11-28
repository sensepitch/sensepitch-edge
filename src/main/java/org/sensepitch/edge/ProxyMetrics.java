package org.sensepitch.edge;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
import io.prometheus.metrics.core.metrics.Counter;
import io.prometheus.metrics.core.metrics.Gauge;
import io.prometheus.metrics.model.registry.Collector;
import io.prometheus.metrics.model.snapshots.GaugeSnapshot;
import io.prometheus.metrics.model.snapshots.Labels;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import java.util.function.Supplier;

/// Metrics of the proxy core: ingress requests and errors, deflector, request outcomes.
///
/// Every ingress metric (`sensepitch_ingress_*`, and every metric counting ingress requests, e.g.
/// `sensepitch_deflector_requests`) has the label `ingress`, the host pattern selected by
/// [SanitizeHostHandler]: a known host or one of [SanitizeHostHandler#SPECIAL_HOSTS], so the
/// number of label values is bounded. Take it with [#ingress(HttpRequest)], or with
/// [#ingress(ChannelHandlerContext)] where the request is not at hand; before a request was read
/// that is the TLS server name, sanitized only when an error or timeout is counted.
///
/// Exception: `sensepitch_ingress_connections_*` ([TrackIngressConnectionsHandler]) have no
/// label. A connection is counted before its server name is known, and sanitizing the name of
/// every connection would cost on every connection for a breakdown that is rarely needed.
///
/// `sensepitch_edge_server_live` has one series with the value 1 per known host, labelled
/// `ingress`, the hosts [SanitizeHostHandler] accepts, without the special hosts. Summed by
/// `ingress` over all instances it gives the number of servers of an ingress. The hosts are read at
/// every scrape, so a configuration reload adds and removes series without a restart. Incompatible
/// for queries that match the former series without labels exactly, e.g.
/// `sensepitch_edge_server_live == 1` in a join; aggregate first, e.g. `max without (ingress)`.
/// Without any known host there is no series.
///
/// @author Jens Wilke
public class ProxyMetrics implements HasMultipleMetrics {

  private final MetricSet metricSet = new MetricSet();

  /// The current known hosts, see [#registerCollectors]
  private final Supplier<? extends Set<String>> knownHosts;

  /// Metrics without known hosts, so without `sensepitch_edge_server_live` series, for tests
  public ProxyMetrics() {
    this(Set::of);
  }

  /// @param knownHosts supplies the current known hosts, as passed to [SanitizeHostHandler],
  ///   read at every scrape
  public ProxyMetrics(Supplier<? extends Set<String>> knownHosts) {
    this.knownHosts = knownHosts;
  }

  /// The `phase` of an ingress error before the TLS handshake is complete
  static final String HANDSHAKE_PHASE = "handshake";

  /// Errors on the ingress connection by ingress, phase and [IngressErrorType], see
  /// [ExceptionHandler]
  public final Counter ingressErrorCounter =
      metricSet.add(
          Counter.builder()
              .name("sensepitch_ingress_request_errors")
              .labelNames("ingress", "phase", "type")
              .build());

  /// The `type` label of [#ingressErrorCounter], the lower case name
  public enum IngressErrorType {
    /// A TLS decoding error
    SSL,
    /// The client reset the connection
    RESET,
    /// Upstream response or response write timeout, also in [#requestOutcomeCounter]
    TIMEOUT,
    /// A failed JA4 fingerprint, see [Ja4Handler]
    JA4,
    /// A second request on a connection before the exchange is complete (no pipelining, answered
    /// with 400), see [DownstreamHandler]
    ANOTHER_REQUEST,
    /// An unexpected exception
    OTHER;

    final String label = name().toLowerCase(Locale.ROOT);
  }

  /// Counts an exception on the ingress connection, see [ExceptionHandler]
  public void countIngressError(ChannelHandlerContext ctx, String phase, IngressErrorType type) {
    ingressErrorCounter.labelValues(ingress(ctx), phase, type.label).inc();
  }

  /// Upstream `500` and `503` responses replaced by a fallback page, by ingress and the replaced
  /// status, see [FallbackHandler]. The request log has them with [ResponseSource#FALLBACK]; a
  /// fallback whose status is not `5xx` masks the problem from the status codes, so its first
  /// occurrence is also logged.
  public final Counter fallbackResponseCounter =
      metricSet.add(
          Counter.builder()
              .name("sensepitch_fallback_responses")
              .help("Upstream 500 and 503 responses replaced by a fallback page")
              .labelNames("ingress", "upstream_status")
              .build());

  /// Requests on the plain HTTP listener by [PlainHttpOutcome], see [HttpToHttpsRedirectHandler].
  /// They are not in the request log, see `doc/design/encrypted-transport.md`.
  public final Counter plainHttpRequestCounter =
      metricSet.add(
          Counter.builder()
              .name("sensepitch_plain_http_requests")
              .help("Requests on the plain HTTP listener, by the answer of the HTTPS redirect")
              .labelNames("outcome")
              .build());

  /// The `outcome` label of [#plainHttpRequestCounter], the lower case name
  public enum PlainHttpOutcome {
    /// Redirected to HTTPS, the host is served
    REDIRECT,
    /// Rejected with 400, the host is not served
    UNKNOWN_HOST,
    /// Rejected with 400: no or several hosts, a host that is no URI host, or an undecodable
    /// request
    BAD_REQUEST;

    final String label = name().toLowerCase(Locale.ROOT);
  }

  /// Counts a request on the plain HTTP listener, see [HttpToHttpsRedirectHandler]
  void countPlainHttpRequest(PlainHttpOutcome outcome) {
    plainHttpRequestCounter.labelValues(outcome.label).inc();
  }

  /// Events on an upstream connection that reach no ingress exception, by upstream name and
  /// [UpstreamErrorType], see [ForwardHandler]. An exception during an exchange is counted as an
  /// ingress error instead.
  public final Counter upstreamErrorCounter =
      metricSet.add(
          Counter.builder()
              .name("sensepitch_upstream_errors")
              .help(
                  "Upstream connections closed before the response was complete, or with data or"
                      + " an exception after it, by type")
              .labelNames("upstream", "type")
              .build());

  /// The `type` label of [#upstreamErrorCounter], the lower case name
  public enum UpstreamErrorType {
    /// The upstream closed the connection before the response was complete, a close by the edge
    /// is not counted; the exchange ends at once with [RequestOutcome#UPSTREAM_CLOSED], see
    /// [DownstreamHandler#userEventTriggered]
    CLOSED_IN_FLIGHT,
    /// Data after the response
    UNEXPECTED_DATA,
    /// An exception after the response
    EXCEPTION_WITHOUT_REQUEST;

    final String label = name().toLowerCase(Locale.ROOT);
  }

  // upstream connection pool, see UpstreamConnectionPool

  /// Connections opened to the upstream
  public final Counter upstreamPoolConnectionsOpened =
      metricSet.add(
          Counter.builder()
              .name("sensepitch_upstream_pool_connections_opened")
              .help("Connections opened to the upstream")
              .labelNames("upstream")
              .build());

  /// Exchanges sent on an idle connection of the pool instead of a new one
  public final Counter upstreamPoolConnectionsReused =
      metricSet.add(
          Counter.builder()
              .name("sensepitch_upstream_pool_connections_reused")
              .help("Requests sent on a kept-alive connection instead of a new one")
              .labelNames("upstream")
              .build());

  /// Open connections to the upstream, in use, idle and connecting, of all event loops: what
  /// `connectionPool.maxConnections` limits
  public final Gauge upstreamPoolConnections =
      metricSet.add(
          Gauge.builder()
              .name("sensepitch_upstream_pool_connections")
              .help("Open connections to the upstream, in use, idle and connecting")
              .labelNames("upstream")
              .build());

  /// Exchanges rejected because their event loop had its share of `maxConnections` open, see
  /// [RequestOutcome#UPSTREAM_CONNECTION_LIMIT]
  public final Counter upstreamPoolRequestsRejected =
      metricSet.add(
          Counter.builder()
              .name("sensepitch_upstream_pool_requests_rejected")
              .help(
                  "Requests rejected with 503, the upstream had as many connections as the pool allows")
              .labelNames("upstream")
              .build());

  /// Idle connections in the pool, of all event loops
  public final Gauge upstreamPoolConnectionsIdle =
      metricSet.add(
          Gauge.builder()
              .name("sensepitch_upstream_pool_connections_idle")
              .help("Idle kept-alive connections to the upstream")
              .labelNames("upstream")
              .build());

  /// Connections closed, in use or idle, by [CloseReason]. Every connection counted in
  /// [#upstreamPoolConnectionsOpened] is counted here once when it closes.
  public final Counter upstreamPoolConnectionsClosed =
      metricSet.add(
          Counter.builder()
              .name("sensepitch_upstream_pool_connections_closed")
              .help("Connections to the upstream closed, by reason")
              .labelNames("upstream", "reason")
              .build());

  /// The `reason` label of [#upstreamPoolConnectionsClosed], the lower case name
  public enum CloseReason {
    /// Idle for the configured `idleTimeoutMillis`
    IDLE_TIMEOUT,
    /// The upstream closed the connection or reset it (an `IOException`, e.g. a reset or a broken
    /// pipe), in use or idle
    CLOSED_BY_UPSTREAM,
    /// Closed by the edge, because the upstream sent data without an exchange, see [ForwardHandler]
    UNEXPECTED_DATA,
    /// Closed by the edge after an exception on the connection that is no `IOException`, see
    /// [ForwardHandler]
    EXCEPTION,
    /// Closed by the event loops at shutdown
    SHUTDOWN,
    /// A reload removed or changed the upstream
    REMOVED,
    /// Closed by the edge after its exchange, because the exchange did not end cleanly (e.g. a
    /// timeout, an error, a response without keep-alive) or the pool is disabled, see
    /// [DownstreamHandler]
    NOT_REUSABLE;

    final String label = name().toLowerCase(Locale.ROOT);
  }

  /// Exchanges sent again on a new connection after a reused connection failed before the
  /// response, see [DownstreamHandler]
  public final Counter upstreamPoolRequestsRetried =
      metricSet.add(
          Counter.builder()
              .name("sensepitch_upstream_pool_requests_retried")
              .help(
                  "Requests sent again on a new connection, after a kept-alive connection was"
                      + " closed before the response")
              .labelNames("upstream")
              .build());

  /// Request log records that failed to render or write, see [RequestLoggingHandler]. The request
  /// outcome is counted before, in [#requestOutcomeCounter].
  public final Counter requestLogErrorCounter =
      metricSet.add(Counter.builder().name("sensepitch_request_log_errors").build());

  /// Exceptions of an IP database lookup, see [CombinedIpTraitsLookup]; the other databases are
  /// still queried
  public final Counter ipLookupErrorCounter =
      metricSet.add(Counter.builder().name("sensepitch_ip_lookup_errors").build());

  /// Read timeouts by ingress and phase, `first_request`, `keep_alive`, `after_response` or
  /// `request_total` (the total time to receive a request body), see [IngressTimeoutHandler]
  public final Counter ingressReceiveTimeoutCounter =
      metricSet.add(
          Counter.builder()
              .name("sensepitch_ingress_receive_timeout")
              .labelNames("ingress", "phase")
              .build());

  /// Counts a read timeout, see [IngressTimeoutHandler]
  public void countReceiveTimeout(ChannelHandlerContext ctx, String phase) {
    ingressReceiveTimeoutCounter.labelValues(ingress(ctx), phase).inc();
  }

  /// Requests by ingress, counted as soon as the request header is received
  public final Counter ingressRequestsStarted =
      metricSet.add(
          Counter.builder()
              .name("sensepitch_ingress_requests_started")
              .help("Increments as soon as an HTTP header from a client is received")
              .labelNames("ingress")
              .build());

  /// Requests by ingress whose response was sent completely
  public final Counter ingressRequestsCompleted =
      metricSet.add(
          Counter.builder()
              .name("sensepitch_ingress_requests_completed")
              .help("Increments once the response was received by the client")
              .labelNames("ingress")
              .build());

  /// Requests by ingress whose connection closed before the response was sent
  public final Counter ingressRequestsAborted =
      metricSet.add(
          Counter.builder()
              .name("sensepitch_ingress_requests_aborted")
              .help(
                  "The response could not be sent to the client, e.g. when the connection is reset")
              .labelNames("ingress")
              .build());

  /// Requests by ingress started and not yet completed or aborted
  public final Gauge ingressRequestsInFlightGauge =
      metricSet.add(
          Gauge.builder()
              .name("sensepitch_ingress_requests_in_flight")
              .help("Number of requests currently in flight")
              .labelNames("ingress")
              .build());

  /// Counts a request whose header was received, called by [RequestLoggingHandler]
  public void countRequestStarted(HttpRequest request) {
    String ingress = ingress(request);
    ingressRequestsStarted.labelValues(ingress).inc();
    ingressRequestsInFlightGauge.labelValues(ingress).inc();
  }

  /// Counts a request whose response was sent, called by [RequestLoggingHandler]
  public void countRequestCompleted(HttpRequest request) {
    String ingress = ingress(request);
    ingressRequestsCompleted.labelValues(ingress).inc();
    ingressRequestsInFlightGauge.labelValues(ingress).dec();
  }

  /// Counts a request whose connection closed before the response was sent, called by
  /// [RequestLoggingHandler]
  public void countRequestAborted(HttpRequest request) {
    String ingress = ingress(request);
    ingressRequestsAborted.labelValues(ingress).inc();
    ingressRequestsInFlightGauge.labelValues(ingress).dec();
  }

  /// Requests in flight of all ingresses, for the shutdown report, see [Proxy#shutdown()]
  public long ingressRequestsInFlight() {
    return (long)
        ingressRequestsInFlightGauge.collect().getDataPoints().stream()
            .mapToDouble(GaugeSnapshot.GaugeDataPointSnapshot::getValue)
            .sum();
  }

  /// Requests by ingress and [RequestOutcome], counted once per request when it is logged, so the
  /// counts match the request log, see [#countRequestOutcome]. The outcome label values are the
  /// fixed set of [RequestOutcome#value()].
  public final Counter requestOutcomeCounter =
      metricSet.add(
          Counter.builder()
              .name("sensepitch_ingress_request_outcomes")
              .help("Requests by ingress and outcome, counted when the request is logged")
              .labelNames("ingress", "outcome")
              .build());

  /// Counts a logged request, called by [RequestLoggingHandler] with the final outcome
  public void countRequestOutcome(HttpRequest request, RequestOutcome outcome) {
    requestOutcomeCounter.labelValues(ingress(request), outcome.value()).inc();
  }

  /// Requests handled by a [Deflector], by ingress and outcome, see [#countDeflectorRequest]
  public final Counter deflectorRequestCounter =
      metricSet.add(
          Counter.builder()
              .name("sensepitch_deflector_requests")
              .help("Requests handled by the deflector, by ingress and outcome")
              .labelNames("ingress", "outcome")
              .build());

  /// Outcome of a request handled by a [Deflector], the label value is the lower case name
  public enum DeflectorOutcome {
    /// Valid admission cookie, request passed on
    PASSED,
    /// No admission cookie, but bypass configured or crawler detected, request passed on
    BYPASSED,
    /// Method other than GET, without admission or bypass, refused with an empty 403
    METHOD_REJECTED,
    /// Challenge page sent
    CHALLENGE_SENT,
    /// Correct challenge answer, admission cookie issued
    ANSWER_ACCEPTED,
    /// Wrong or incomplete challenge answer
    ANSWER_REJECTED;

    final String label = name().toLowerCase(Locale.ROOT);

    /// Label value and logged value, the lower case name
    public String value() {
      return label;
    }
  }

  /// Counts the request for its ingress, see [#ingress(HttpRequest)]
  public void countDeflectorRequest(HttpRequest request, DeflectorOutcome outcome) {
    deflectorRequestCounter.labelValues(ingress(request), outcome.label).inc();
  }

  /// The `ingress` label value of a request, see the class doc. The host header is missing only
  /// when [SanitizeHostHandler] is not in the pipeline, e.g. in tests.
  static String ingress(HttpRequest request) {
    String pattern = request.headers().get(SanitizeHostHandler.INGRESS_PATTERN_HEADER);
    if (pattern != null) {
      return pattern;
    }
    String ingress = request.headers().get(HttpHeaderNames.HOST);
    return ingress != null ? ingress : SanitizeHostHandler.MISSING_HOST;
  }

  /// The `ingress` label value for an error or timeout on the connection: the host of the exchange
  /// in progress. Without one, e.g. a handshake error, or a timeout before the first HTTP request
  /// or between the exchanges of a keep-alive connection, the TLS server name, see
  /// [SanitizeHostHandler#serverName].
  /// Looked up only when counting, so connections without errors pay nothing for it.
  static String ingress(ChannelHandlerContext ctx) {
    RequestLoggingHandler handler = RequestLoggingHandler.find(ctx);
    HttpRequest request = handler != null ? handler.request() : null;
    if (request != null) {
      return ingress(request);
    }
    // not in the pipeline in tests of single handlers
    SanitizeHostHandler sanitizer = ctx.pipeline().get(SanitizeHostHandler.class);
    return sanitizer != null ? sanitizer.serverName(ctx.channel()) : SanitizeHostHandler.NIL_HOST;
  }

  /// Requests in flight per upstream name, for the shutdown report, see [Proxy#shutdown()]
  private final Map<String, LongAdder> upstreamRequestsInFlight = new ConcurrentHashMap<>();

  /// Counter of the requests in flight to the upstream, incremented when the upstream is selected
  /// and decremented when the request is logged
  LongAdder upstreamRequestsInFlight(Upstream upstream) {
    return upstreamRequestsInFlight.computeIfAbsent(upstreamLabel(upstream), k -> new LongAdder());
  }

  /// The `upstream` label of the upstream metrics: the name or, without one, the target
  static String upstreamLabel(Upstream upstream) {
    return upstream.name() != null ? upstream.name() : String.valueOf(upstream.target());
  }

  /// Upstreams with requests in flight, by name
  Map<String, Long> upstreamRequestsInFlight() {
    Map<String, Long> inFlight = new TreeMap<>();
    upstreamRequestsInFlight.forEach(
        (name, count) -> {
          long sum = count.sum();
          if (sum > 0) {
            inFlight.put(name, sum);
          }
        });
    return inFlight;
  }

  @Override
  public void registerCollectors(Consumer<Collector> consumer) {
    metricSet.forEach(consumer);
    consumer.accept(this::serverLive);
  }

  /// One data point with the value 1 per known host, sorted for a stable export, see class doc
  private GaugeSnapshot serverLive() {
    GaugeSnapshot.Builder builder =
        GaugeSnapshot.builder()
            .name("sensepitch_edge_server_live")
            .help(
                "Always 1 per ingress the server knows, might be 0 in the future when the server is"
                    + " not accepting connections, e.g. warming up");
    for (String ingress : new TreeSet<>(knownHosts.get())) {
      builder.dataPoint(
          GaugeSnapshot.GaugeDataPointSnapshot.builder()
              .value(1)
              .labels(Labels.of("ingress", ingress))
              .build());
    }
    return builder.build();
  }
}
