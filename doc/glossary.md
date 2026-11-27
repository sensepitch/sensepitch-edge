# Glossary

The terms used in the code, the configuration and the documents, so that one word means one thing.
An entry defines the term and says where it shows up; the behaviour is described in the linked
class docs and design documents. A word that turns out to be ambiguous gets an entry here.

**access log**: what NGINX, Envoy and other proxies call it; here it is the request log, see
there. Not renamed: the record carries more than the access (outcome, response source,
protection, fingerprints), and the name is in configuration keys and metric names.

**admission**: the proof that a client passed the protection: a signed token in a cookie, issued
for an accepted challenge answer. See
[`AdmissionTokenGenerator`](../src/main/java/org/sensepitch/edge/AdmissionTokenGenerator.java),
the README section "Admission configuration" and [PoW admission](design/pow-admission.md).

**bypass**: an exemption from the protection, configured (`bypass.remotes`, `bypass.uris`) or for
a detected crawler; the reason is logged as `sensepitch.protection.bypass`. See
[`BypassCheck`](../src/main/java/org/sensepitch/edge/BypassCheck.java).

**challenge**: the proof of work page the deflector sends to a client without admission, and its
answer. See [`Deflector`](../src/main/java/org/sensepitch/edge/Deflector.java).

**deflector**: the protection handler that answers requests without admission or bypass with the
challenge, see [`DeflectorHandler`](../src/main/java/org/sensepitch/edge/DeflectorHandler.java).

**diagnostic log**: the operational log of the proxy, through Log4j2 to standard error, see
[diagnostic logging](design/diagnostic-logging.md). Not the request log.

**downstream, upstream**: the client side and the application side of the edge. Downstream is the
client connection
([`DownstreamHandler`](../src/main/java/org/sensepitch/edge/DownstreamHandler.java)), upstream
the application a request is forwarded to (`upstreams` in the configuration,
[`ForwardHandler`](../src/main/java/org/sensepitch/edge/ForwardHandler.java)).

**edge**: Sensepitch Edge itself. "The edge answered" means no upstream was involved, the response
source `edge`.

**edge header**: a request header the edge adds for the upstream, named `Sp-*`; a client cannot
send one, see [`SensepitchHeaders`](../src/main/java/org/sensepitch/edge/SensepitchHeaders.java).

**exchange**: a request together with its response, on the client or on the upstream side, as in
RFC 9110 and in `HttpExchange` (JDK), `HttpServerExchange` (Undertow) or `ServerWebExchange`
(Spring). "The request" is the message of the client, "the exchange" the whole round trip: an
exchange is in flight until its response is complete, it ends with a request outcome. In the code:
`DownstreamHandler#failExchange`, `exchangePhase`.
- Externally, in all text operators see, a *request* is the complete exchange, as in "requests
  per second" or `sensepitch_ingress_requests_in_flight`, the common usage of other proxies and of
  OpenTelemetry; "exchange" does not appear there. That is the README, the docs of the
  configuration records (the configuration reference), metric names and help texts, log fields
  and their docs, log messages, the shipped Log4j2 configuration and configuration keys.
- Internally, in the design documents, code comments and our APIs, the round trip is an
  *exchange*: there are finished exchanges, not finished requests. The message of the client is
  the *HTTP request* or the *incoming request*; plain "request" only where the context leaves no
  doubt, e.g. "the request headers".

**fingerprint**: a value that identifies a UA variant from what the client sends: JA4 from the TLS
handshake, sp1 from the request headers. Never one that identifies a client, see
[browser fingerprinting](design/browser-fingerprinting.md) and
[`FingerprintConfig`](../src/main/java/org/sensepitch/edge/FingerprintConfig.java).

**fleet**: the edge instances that serve a set of Kubernetes Gateways, see the
[Kubernetes support plan](kubernetes-support/1-architecture.md).

**in flight**: an exchange that started and has not ended, e.g. the
`sensepitch_ingress_requests_in_flight` gauge or the `closed_in_flight` upstream error.

**ingress**: has two meanings, say which one:
- the client side as a whole, e.g. an ingress connection, counted by
  [`TrackIngressConnectionsHandler`](../src/main/java/org/sensepitch/edge/TrackIngressConnectionsHandler.java)
- the `ingress` label of the request metrics: the host pattern a request was selected by, a known
  host or a pseudo host, never the raw `Host` header, see
  [`ProxyMetrics`](../src/main/java/org/sensepitch/edge/ProxyMetrics.java)

**known host**: a host or wildcard the configuration serves, matched by
[`SanitizeHostHandler`](../src/main/java/org/sensepitch/edge/SanitizeHostHandler.java).

**listener**: a bound port. The TLS listener (`listen.httpsPort`) routes, the plain HTTP listener
(`listen.httpPort`) only redirects, see [encrypted transport](design/encrypted-transport.md).

**protection**: the feature that keeps unwanted clients from the upstream: deflector, challenge,
admission and bypass together, configured in `protection`, per site or globally.

**pseudo host**: a label that stands for no real host and contains an underscore, which is not
legal in a host name: `unknown_host`, `missing_host`, `nil_host`, the `SPECIAL_HOSTS` of
[`SanitizeHostHandler`](../src/main/java/org/sensepitch/edge/SanitizeHostHandler.java).

**reload**: applying a changed configuration without a restart, see the README section
"Configuration reload". **State**: everything built from the configuration that a reload replaces,
[`EdgeState`](../src/main/java/org/sensepitch/edge/EdgeState.java).

**request level event**: an event that can happen per request or connection, e.g. an upstream error.
It is counted; unless its request log record shows it with status `499` or `5xx`, it is also logged
at debug, its first occurrence at warning, never at `INFO` or above, see
[`ProxyLogger`](../src/main/java/org/sensepitch/edge/ProxyLogger.java).

**request log**: one record per exchange, to standard out or OTLP, see
[request log](design/request-log.md). Not the diagnostic log.

**request outcome**: how an exchange ended for the client, one value per request log record, e.g.
`completed`, `client_aborted`, `upstream_timeout`, see
[`RequestOutcome`](../src/main/java/org/sensepitch/edge/RequestOutcome.java). Not who answered,
that is the response source.

**response source**: who produced the response: `upstream`, `static`, `fallback` or `edge`,
logged as `sensepitch.response.source`, see
[`ResponseSource`](../src/main/java/org/sensepitch/edge/ResponseSource.java).

**site, route**: a site is a configured host with its upstream and protection (`sites`); a route
is the rule that selected the upstream within it, a path pattern (`paths`), logged as `http.route`.
See [`SiteConfig`](../src/main/java/org/sensepitch/edge/SiteConfig.java).

**traffic type**: `human`, `bot` or `unknown`, what the protection decided about a request, sent
as the `Sp-Traffic-Type` header. Formerly "flavor", which the metric
`sensepitch_ingress_request_flavor_count_total` still uses.

**UA variant**: a browser build: browser, version, platform and automation state. What a
fingerprint may identify, see [browser fingerprinting](design/browser-fingerprinting.md).

**unserviced host**: a host the configuration does not serve; it gets the `unservicedHost` answer,
see [`UnservicedHost`](../src/main/java/org/sensepitch/edge/UnservicedHost.java).
