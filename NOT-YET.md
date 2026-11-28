# Not yet

Optional work, outside the current focus: running the standalone edge on Kubernetes with direct
node ingress, see [TODO.md](TODO.md). Moved out of `TODO.md` on 2026-11-21; the entries keep
their text, numbers and priorities from there. An entry moves back to `TODO.md` when it becomes a
need, e.g. a bug or a production requirement. Bugs and robustness issues are never parked here.

## Upstream pool fine tuning

- max age, see below
- put circuit breaker into it

## Memory / allocation bounds

- OtlpAllocationIT measures steady state allocations, can we have object counts as well? No, not possble
- Add an IT test for allocations per exchange
- is there a allocations / request metric?

## Benchmark: edge against NGINX, done fairly

- [ ] Rebuild the comparison in `performance-test/runs/2026-11-27-upstream-pool` on `shb` and
  replace the numbers of the README benchmark section
  - P2, operator-visible only through the README: today's README numbers (2025, vegeta, edge with
    the earlier pool) and most numbers of 2026-11-27 compare unequal setups.
  - Findings of 2026-11-27 that the plan builds on, in the run README
    [2026-11-27-upstream-pool](performance-test/runs/2026-11-27-upstream-pool/README.md):
    - vegeta is the bottleneck on one machine: at a requested 40k req/s it reached 28 to 30k, the
      machine 93 % busy with NGINX on 3.7 cores. `h2load` 1.71 and `shb` (io_uring) reach about
      50k for both servers at 100 connections.
    - A freshly started edge needs a warm-up (JIT): without one the first seconds take 10 cores
      and dominate the tail; 10 s at 1 000 req/s are enough.
    - NGINX logs every request to standard out (the image links `access.log` to `/dev/stdout`);
      that costs it 19 % (64.8k to 52.5k req/s with `shb`), the request log of the edge 7 %
      (47.3k to 43.8k). With both logs off the edge reaches 73 % of NGINX, with both on 83 %.
      Earlier "edge equals NGINX" results compared NGINX with its log against the edge without.
    - Without the warm-up and with vegeta, the latency tails at 5k and 10k came from the cold
      start, not from the request log.
  - Plan:
    1. `compare.sh` on `shb` instead of vegeta: per step a fresh server (edge JVM or NGINX
       container), warm-up with `shb` for 10 s (discarded), then 10 s measured with `-j` (JSON),
       the summary table built from the JSON: requests per second, errors, p50, p90, p99,
       p99.9.
    2. Variants per step: NGINX and the edge, each with its log on and off
       (`NGINX_PROXY_ACCESS_LOG=off`, `SENSEPITCH_EDGE_REQUEST_LOG_DISABLED=true`), at 10, 100
       and 1 000 connections (closed loop: `shb` sends as fast as answers come).
       Future variant parameters could be http1.1, http2, QUIC. Also HAPROXY and envoy should 
       be added for comparison in the future.
       There should be also different test scenarios, we only test with 10kb data.
       There should be a test scenario for 0 byte responses. 512 byte responses, and a size mix.
    3. Three runs per variant, the table reports median and range, so a single bad run (seen
       up to a factor of three in the p90) does not decide.
    4. CPU per variant: the machine busy share and the server's cores, average of the samples 6
       to 8 of 10 as in `compare.sh` today, and from it cores per 10 000 req/s, the fairest
       figure while the client shares the machine.
    5. Optional, if the machine stays above 80 % busy: pin `shb` and the server to separate
       cores (`taskset`) and run once more, to see how much the shared CPU changes the ratio.
    6. Optional: HTTP/2 (`shb --http2`, NGINX `http2 on`, the edge where it supports it) and
       later HTTP/3, which `shb` and `h2load` both speak.
    7. Results: the run README gets the new tables and replaces the probe tables measured
       without a warm-up; the README benchmark section gets the fair numbers (logs on and off,
       tool, machine, date) instead of the 2025 vegeta run, and keeps one sentence on why the
       old numbers differ.

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
  - Define which traits grant bypass, rule precedence and behavior for unknown or stale data.
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
  - P3: decide failover order versus round robin; shares the design with "multiple servers per
    upstream".
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
- [ ] Allow cache to be tiered on disk and Redis
  - P2 relatively important so we don't have restart spikes
- [ ] transparent compression: all responses to the client are compressed with low overhead brotli, add benchmarks
- [ ] best compression for cached content
  - first do low overhead brotli and cache it, recompress with more efficient compression with half
    of the available CPUs in niced priority, priotising hot resources
- [ ] multiple servers per upstream
  - P3: decide load balancing, retry safety, failover and configuration semantics.
  - One circuit breaker per server, see the `Endpoint` record in `HttpBatchSender`.
- [ ] upstream health and circuit breaker:
  [`CircuitBreaker`](src/main/java/org/sensepitch/edge/CircuitBreaker.java) exists for the request
  log, it opens on the first failure; for upstreams it needs a failure threshold (consecutive
  failures or a failure rate window, as in Armeria or resilience4j). This breaker replaces the TCP readiness probe.
  - P2: do research and design draft and give decisions here
  - P3: specify thresholds, recovery probes, per-server versus per-upstream state and retry policy.
  - Keep the backoff, `Retry-After`, single probe and metrics, the request log keeps a threshold
    of one, see [doc/design/circuit-breaker.md](doc/design/circuit-breaker.md).

## Circuit breaker

Limitations and the reasoning are in [doc/design/circuit-breaker.md](doc/design/circuit-breaker.md);
the failure threshold for upstreams is under Upstream.

- [ ] slow calls count as failures, as resilience4j's slow call rate
  - P3: for upstreams, once latency targets are known.

## Ingress, HTTP

- [ ] HTTP/2 support, downstream
  - P3: scope protocol negotiation, stream lifecycle, backpressure and compatibility tests.
- [ ] quic support
  - P3
- [ ] Implement server-timing, https://www.w3.org/TR/server-timing/
  - P3: enable/disable forward of upstream Server-Timing info, log all of it, add own timing info,
    e.g. upstream connect latency

## Metrics, logging

- [ ] Cleanup: Consolidate all sensepitch metrics prefixes as constants into one place including their descritpion P3

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
- [ ] JaCoCo coverage varies between runs: lines that passing tests execute are sometimes
  reported as missed, e.g. `AdminServer` lines 48 and 73–74 and `IpTraits` lines 31, 81 and 85
  (seen 2026-11-24 in `./mvnw verify`, while `AdminServerTest` and `CombinedIpTraitsLookupTest`
  passed). Cause not investigated; suspected the parallel unit test execution in one JVM, or
  probes of a class lost on a JVM or thread end. Known before: one branch of
  `AsyncRequestLogWriter` (see "Evergreen / Cleanups").
  - P3, no operator visible effect: the floor in `pom.xml` keeps a margin; a new gap seen once
    may be noise, rerun before writing a test or a TODO for it. Compare two `jacoco.xml` of the
    same commit to find the affected classes.
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

- [ ] Move a test case that is doing real socket connections or sleeps from test to verify and
  maintain code coverage
  - Goal: `./mvnw test` at about 5 s wall time. The classes run in parallel, the methods of a
    class in order, so one slow class sets the wall time and a few hundred ms count.
  - Move only what adds no coverage of its own: compare `target/site/jacoco/jacoco.csv` of
    `./mvnw clean test jacoco:report` with and without the test (exclude with
    `-Dtest='**/*Test,**/*BDDTest,!Class#method'`, a bare exclusion pulls in the `*IT`s). One
    branch of `AsyncRequestLogWriter` varies between runs, rerun before reading a difference.
  - Done 2026-11-16: `ReloadableRequestLoggerTest.noRecordIsLostWhileReloading` (0.3 s, no own
    coverage) to `ReloadableRequestLoggerIT`, which uses the unit test class as its fixture.
  - Next: `ReloadableRequestLoggerTest` still takes 3.3 s in the full run (2026-11-16):
    `finishCoversDrainingDestinations` 1.4 s (on simulated time since 2026-11-25),
    `finishedDestinationEndsWhenItsRecordsAgedOut` 0.3 s (on simulated time since 2026-11-24),
    `disableAndEnableByReload` 0.2 s, `removedDestinationDoesNotBlockTheReload` 0.15 s.
    Probably real timers (retry backoff after 503, age limit), not verified. On 2026-11-25 a
    focused run of the class took 0.94 s, the first test 0.55 s of it with warm-up and server
    start, the others 0.15 s or less. What cannot move for coverage gets a fake clock instead,
    see the planned improvements in the
    [test speed design](doc/design/test-speed.md#planned-improvements).
- [ ] Performance test: different Netty allocators, leak detection,
  `-XX:NativeMemoryTracking=summary`
  - P3: compare representative workloads and record throughput, latency and memory before choosing.

## Governance

- [ ] add CONTRIBUTING.md
