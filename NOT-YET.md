# Not yet

Optional work, outside the current focus: running the standalone edge on Kubernetes with direct
node ingress, see [TODO.md](TODO.md). Moved out of `TODO.md` on 2026-11-21; the entries keep
their text, numbers and priorities from there. An entry moves back to `TODO.md` when it becomes a
need, e.g. a bug or a production requirement. Bugs and robustness issues are never parked here.

## Kubernetes, Gateway API controller

The Gateway API implementation with its Go controller (milestones M1 to M3, plan in
[doc/kubernetes-support/](doc/kubernetes-support/)). Not needed to run today's `config.yml` edge
from the standalone chart. The numbers continue those of the Kubernetes section in `TODO.md`.

- [ ] 6. PROXY protocol v1/v2 per listener and `X-Forwarded-For`/`-Proto` only from trusted
  proxy CIDRs
  - P2: behind a cloud load balancer the client IP is lost today, and the deflector and IP traits
    depend on it.
  - Not needed with direct node ingress: the TCP peer is the client.
- [ ] 7. M1 path, see `6-phases.md` "Development order": Gateway API Java records and snapshot
  schema, then the `config.yml` converter run against the production configurations, then the
  Go controller
  - P2: start once 1-4 are done; the converter shows early whether the model covers production.
    Gateway API hostnames use wildcards, see the `SiteSelector` entry under Ingress.
- [ ] 8. extend the kind environment from item 3 with pinned Gateway API standard-channel CRDs,
  the controller image, an echo backend, and cloud-provider-kind for LoadBalancer Services.
  See `6-phases.md` "Development order" item 5 and
  `7-testing-and-release.md`.
  - P2: needed before the end-to-end routing test of item 7.

## Protection, admission

- [ ] bypass config should be in the protection and not in the protection providers => we need a
  proper API for the protection provider
  - P3: define provider-independent bypass semantics and migration of existing configuration.
- [ ] minimise + compress challenge resources
  - P3: measure transfer and CPU costs; decide build-time compression and cache behavior.
- [ ] abnormal admission usage detection
  - P3: Define abnormal behavior, data sources, alerting versus enforcement, and privacy/retention.
    Needs a stream per admission token
- [ ] Admission performance: skip validation for keep-alive connections
  - P3: benchmark first; define behavior when requests on one connection have different cookies
    or a token expires or configuration reloads.
- [ ] Admission performance: deliver the challenge via cookie and serve static HTML
  (`Deflector.outputChallengeHtml` renders the page per request, only the challenge changes)
  - P3: benchmark and define caching, cookie scope and security semantics.
- [ ] improve bypass matching (maybe ASN, geo)
  - P3: define which traits grant bypass, rule precedence and behavior for unknown or stale data.
- [ ] `TrieIpLabelLookup`: consolidate into `IpTraitsLookup`
  - P3, no operator-visible effect: clarify the desired API and whether collector composition
    already covers the use case.
- [ ] naming: the deflector is one component of the protection system, next to cookie gates and
  the bypass. Decide whether to rename the metric `sensepitch_deflector_requests_total` to
  `sensepitch_protection_requests_total` (with a label for the component, once more components
  report outcomes) and "deflector" to "protection" in general, or keep it as is. A metric rename
  breaks dashboards and alerts.
  - P3: decide ownership and migration/deprecation before renaming.
- [ ] carry an admission to another domain, since cookies are host-only, e.g. a redirect that
  sets the cookie there or a cookie on a common parent domain; see the limitations of the
  [replay draft](doc/design/admission-replay-draft.md). Without it a browser solves once per
  domain, as today

## Request log

Decisions, alternatives and limitations are in
[doc/design/request-log.md](doc/design/request-log.md).

- [ ] failover endpoints: several endpoints per OTLP destination, each with its own breaker, see
  the `Endpoint` record in `HttpBatchSender`
  - P3: decide failover order versus round robin; the upstream side is drafted in
    [doc/design/upstream-targets.md](doc/design/upstream-targets.md).
- [ ] disk spool: waiting batches are lost when the proxy restarts during a log server outage or
  hits the shutdown deadline
  - P3: only if deployments ask for it; needs a volume, a disk budget, fsync policy and replay
    order.
- [ ] Allocation free stdout request log without Jackson
  - P3, no operator visible effect: today each JSON record becomes a `String` (Jackson into a
    `StringWriter`), `AsyncRequestLogWriter` turns it into UTF-8 bytes and queues one `byte[]` per
    record for its writer thread.
  - Write the JSON line with the `RequestLogSink` primitives directly into the per-event-loop
    batch, as the OTLP payload does with `ProtobufWriter`; hand whole batches to the stdout writer
    thread instead of one `byte[]` per record.
  - Make the OTLP batching generic and reuse it: `ThreadBatcher`, `Batch`, `BatchPayload` and the
    end markers are tied to `HttpBatchSender`; split the batching from the HTTP sending so a
    stream sender (standard out) plugs in.
  - Removes Jackson from the request log, the `String` and `getBytes` per record, the exception
    type parameter of `RequestLogSink` and the last `catch` in `JsonRequestLogger`.
  - Decide before: the escaping stays byte identical to Jackson's (short escapes, `\u00XX` for
    other control characters, raw UTF-8, a lone surrogate as `?`), pinned by tests against
    Jackson; an idle record appears only after the flush interval unless the writer flushes partly
    filled batches when idle; the stdout queue size (planned improvement 3) becomes a batch
    budget.
  - See [doc/design/request-log.md](doc/design/request-log.md), planned improvement 6 and
    "Batching the stdout path the same way".

## Upstream

- [ ] upstream connection pool, follow-ups of
  [upstream-connection-pool.md](doc/design/upstream-connection-pool.md):
  - maximum requests per connection and maximum connection age (NGINX: 1000, 1 h), so a DNS change
    of the upstream takes effect
  - a queue at `maxConnections` instead of the immediate `503`, with a timeout (Envoy
    `max_pending_requests`, HAProxy `timeout queue`)
  - borrowing an idle connection of another event loop when the own partition is empty, or its
    share of `maxConnections` is used up
  - preconnect (Envoy `per_upstream_preconnect_ratio`)
  - P3: no operator asked yet; the pool works without them.
- [ ] cache upstream responses with cache2k in netty buffers
  - P3.
- [ ] Allow cache to be tiered on disk and Redis
  - P3, after the cache: relatively important then, so a restart does not cause load spikes.
- [ ] transparent compression: all responses to the client are compressed with low overhead
  brotli, add benchmarks
  - P3.
- [ ] best compression for cached content
  - P3, after the cache: first do low overhead brotli and cache it, recompress with more efficient
    compression with half of the available CPUs in niced priority, prioritising hot resources.
- [ ] multiple servers per upstream
  - P3: a design draft exists, see [doc/design/upstream-targets.md](doc/design/upstream-targets.md):
    a common pool and limit per upstream, the target chosen first by least connections per event
    loop, a breaker per target and never all out, a retry on another target after a connect
    failure, the connections per target reported.
- [ ] upstream health and circuit breaker
  - P3. Done: [`CircuitBreaker`](src/main/java/org/sensepitch/edge/CircuitBreaker.java) supports
    upstreams (a threshold of failures in a row, probes by the caller, a start in the open state,
    lock free per request), and the policy is decided: open only when the upstream is down, no
    failure rate while there is one server, pool limit refusals do not count, see "Planned
    improvements" in [doc/design/circuit-breaker.md](doc/design/circuit-breaker.md).
  - Next: the integration listed there (configuration, permit after the pool reservation,
    classification by outcome, an optional probe endpoint, `INFO` lines on unavailable and alive
    again, a short upstream connect timeout); slow calls as failures once latency targets are
    known.
  - Open decisions: the breaker on or off by default per upstream; readiness from the breakers,
    which replace the TCP readiness probe (`UpstreamReadinessProbe`).

## Ingress, HTTP

- [ ] HTTP/2 support, downstream
  - P3: scope protocol negotiation, stream lifecycle, backpressure and compatibility tests.
- [ ] Implement server-timing, https://www.w3.org/TR/server-timing/
  - P3: enable/disable forward of upstream Server-Timing info, log all of it, add own timing info,
    e.g. upstream connect latency

## Metrics, logging

- [ ] Cleanup: consolidate all sensepitch metric prefixes as constants into one place, including
  their description
  - P3.

## Configuration, operations

- [ ] warmup before ready
  - P3: makes sense after cache is implemented, reload a previous cache content or define some URLs
    to spider through
- [ ] restart a node ingress pod without refused connections: `SO_REUSEPORT` and DaemonSet
  `maxSurge: 1`, the new pod binds next to the old one, which stops accepting once the new one
  is ready (`shutdownAcceptGraceMillis`). Needs `hostNetwork` instead of the chosen `hostPort`,
  and the Netty epoll or io_uring transport (today NIO, `Proxy`). See `TODO.md` Kubernetes
  item 9.
  - P3: until then a restart refuses connections on that node, accepted in item 9.
- [ ] have good documentation, nice examples are: Envoy
  - Choose audience and deliverables: operator setup, reference configuration or architecture.
  - The operator documentation for the Kubernetes setup is in `TODO.md`, Kubernetes item 15.

## Build, tests

- [ ] JaCoCo agent opt-in for `./mvnw test`, always on for `verify`: `jacoco-prepare-agent` has no
  phase, so it runs in `initialize` and every `./mvnw test` collects coverage, about 0.9 s of
  5.75 s (4.85 s with `-Djacoco.skip`, three runs each, 2026-11-28, 16 cores).
  - P3, no operator visible effect: the quick loop exists already with `-Djacoco.skip` or
    `-Dfast`.
  - Also: the agent appends to `target/jacoco.exec`, so a `./mvnw verify` without `clean` reports
    the union of all runs since the last `clean` and may pass the floor locally where a clean run
    fails; CI starts from a fresh checkout. Fix both together: e.g. a separate data file per
    phase (unit, integration) merged by `jacoco:merge` before the report, the unit one written
    fresh per run.
  - Not a one-liner: surefire and failsafe share the data file today, see `jacocoArgLine` in
    `pom.xml`.
- [ ] an IT for the allocations per exchange; `OtlpAllocationIT` measures the steady state
  allocations of the request log only
  - P3, no operator visible effect.
- [ ] google-java-format 2, https://github.com/google/google-java-format/releases#release-v2.0.0
  (stuck at 1.36.1: Spotless cannot load 1.37+, 2.x changes the lambda layout). Impact: one-time
  reformat of about 40 files / 930 lines, lambda bodies hug the call (`.forEach(t -> {`) and indent
  less. P3, no operator-visible effect: verify formatter/tool compatibility first. Done in its own
  commit, `formatJavadoc` stays off so the `///` comments remain untouched.
- [ ] Serenity reports (`-Dserenity.reports`) are of little use: no step is recorded for any
  class, the reports list tests only. The byte-buddy agent (`-javaagent` copy step) and
  `net.bytebuddy.nexus.disabled` are kept for that mode; whether they are needed with the
  listener off was not tried.
  - P3, no operator-visible effect: decide whether to make the reports useful or drop the report
    mode and the agent.
- [ ] Component level config tests, against the rule in `AGENTS.md`
  - P3, no operator visible effect: `RequestLoggerFactoryTest.environmentVariables` loads
    destinations from env vars; `environmentVariableMapEntryNeedsValue` tests the env loader
    itself; `ConfigTest` has cases built on component configs (`configDeflector`,
    `readRoutingFromYaml`, `readRoutingFromEnvironment`).
  - Remove a case if `./mvnw clean test jacoco:report` shows no coverage loss; a case that
    covers a loader path moves to `ConfigTest` with a generic record.
- [ ] Investigate cache2k pinpoint for the test patterns
  - P3: `cache2k-pinpoint` (module of cache2k, 2.6.1) has `PinpointParameters.TIMEOUT`,
    `Await` with a `TimeoutError` naming the condition, `TimeBox` (timing assertions only count when
    run within the time box, for "no retry within 100 ms" and the duration ranges in
    `ProxyShutdownIT`), and `SupervisedExecutor`/`TaskSuccessGuardian` for exceptions of worker
    threads. It is not on Maven Central (its deploy is skipped), so decide: publish it with a
    cache2k release, or copy the needed classes.
- [ ] Unify request level logging and metrics, so a request problem is reported with one method
    call that logs and counts, see `ProxyLogger.requestProblem`, and unify it with the instance
    level logging change. The `RequestProblem` constants already name the metric that counts them.
    `RequestLoggingHandler` now reports request-log failures with a `RequestProblem` and
    increments the error counter separately. P3

## Evergreen / Cleanups

- [ ] Performance test: different Netty allocators, leak detection,
  `-XX:NativeMemoryTracking=summary`
  - P3: compare representative workloads and record throughput, latency and memory before choosing.

## Governance

- [ ] add CONTRIBUTING.md
