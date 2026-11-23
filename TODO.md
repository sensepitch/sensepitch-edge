# TODO

Open work, grouped by area, one entry per piece of work; finished work is removed, git keeps its
history. An entry from a `TODO`/`FIXME` comment names the class and method it is about.

Current focus (2026-11-21): first fast tests without flakiness, then run the standalone edge on
Kubernetes as the ingress of our own infrastructure, terminating TLS directly on the nodes,
without a provider load balancer. Optional work outside this focus is parked in
[NOT-YET.md](NOT-YET.md), among it the Gateway API controller, the failover endpoints and disk
spool of the request log, and slow calls and the failure threshold of the circuit breaker for
upstreams. Bugs and robustness issues stay here.

Priorities: P1 = fast and reliable tests, bugs, and what the direct node ingress on Kubernetes
needs; P2 = robustness; P3 = the rest. Notes record decisions to make before implementation.

## Fast tests without flakiness

First, because every other task depends on it: a quick test loop and a failure that always
means a defect speed up and secure all work below. Measurements, the fake clock library and what
was tried are in [doc/design/test-speed.md](doc/design/test-speed.md).

- [ ] Real time and races in the remaining tests, against the rules for concurrent tests in
  `AGENTS.md`
  - P1: each one is slow or can fail on a loaded machine although the code is right.
  - On simulated time (`FakeTime`, `FakeLogServer`) once the class has a clock seam:
    `ReloadableRequestLoggerTest` and `RequestLoggerFactoryTest` (they build the destination via
    the public constructor), `AsyncRequestLogWriterTest` and `ConfigFileWatcherTest` (no clock or
    scheduler seam; the watcher also waits on the file system), `ProxyOptionsTest` (OTLP posting
    with a 1 ms flush interval), and the durations in `ProxyShutdownIT`. The tests stay in the
    test scope meanwhile, see the coverage rule in `AGENTS.md`.
  - Races with the test's own steps, they need a hook or a held state:
    - `ReloadableRequestLoggerIT.finishedDestinationEndsWhenItsRecordsAgedOut` (age 300 ms, then
      `awaitAttempts(1)`: a pause over 300 ms may age the records out before the first attempt,
      inferred, not reproduced).
  - Thread state waits that any park satisfies, a false pass rather than a flake:
    `ProxyAcceptGraceTest` (`WAITING`), `ProxyShutdownIT.shutdownWaitsForReload` (`BLOCKED`).
  - Not worth converting: the `MockTicker` users (`CircuitBreakerBDDTest`, `TimeoutsTest`,
    `ResponseTimeoutBDDTest`, `MetricsExpositionTest`) need a ticker only, no scheduler.
- [ ] Instance scoped diagnostic logging and event driven waits, plan in
  [plans/instance-scoped-logging.md](plans/instance-scoped-logging.md)
  - P1, test speed and clarity, no operator visible change: non-trace loggers become instance
    fields owned by a `LogScope` (proxy instance and component), trace stays on a `static final`
    `TraceLogger`. Tests capture by scope instead of by thread, so `LogCapture.ofAllThreads()`
    and the `@Isolated` it forces go away, and `LogCapture.await` waits for a record without
    polling.
  - Polling to replace: `ExternalTcpIT` (`awaitLogged`, `awaitRequestLog`,
    `awaitHandshakeErrors`), `ShadedJarIT.awaitOutput` (reads the stderr file) and the counter and
    gauge `await`s in `ReloadableRequestLoggerTest`. Where no log is involved: a test log server
    that signals each post with a `Semaphore` or queue, a latch in the reload callback.
    `TestingParameters.await` stays where no hook exists.

## Bugs

- [ ] Check the nightly workflows, "Flaky tests" (`./mvnw clean verify` ten times in a row) and
  "Nightly" (full gate plus the `@Tag("excessive")` tests, 21:47 Singapore time):
  https://github.com/sensepitch/sensepitch-edge/actions/workflows/flaky-tests.yml and
  https://github.com/sensepitch/sensepitch-edge/actions/workflows/nightly.yml
  - P1: a test that failed in any run of the flaky test hunt, or a failed nightly run, is a bug
    and has priority over all other work here; add an entry below per problem until it is fixed.
    The flaky test summary lists each failed test with the number of runs it failed in; a failed
    nightly run uploads `nightly-test-reports` (kept 7 days). An excessive test that fails is load
    sensitive or a real regression of allocation or timing.
  - Act only on what the workflows reported in their recent runs; a failure seen once in a local
    or agent build is not added here. The general remedies are in "Fast tests without
    flakiness" above.

## Kubernetes, direct node ingress

The target: cloud hosts, each with its own public IPv4 and IPv6 address on its network
interface, no NAT and no load balancer in front; certificates come from an ACME client on the
host (HTTP-01), the edge reads its Let's Encrypt directory. On Kubernetes the edge runs as a
DaemonSet on the ingress nodes, binds 80 and 443 on the node addresses, and DNS lists the node
addresses (DNS spreading, see "Exposure variants" in
[1-architecture.md](doc/kubernetes-support/1-architecture.md)). The TCP peer stays the client,
so `bypass.remotes` and the IP traits keep working without PROXY protocol. The edge runs today's
`config.yml` from the [standalone chart](charts/sensepitch-edge/README.md); the Gateway API
controller (Kubernetes items 6 to 8, milestones M1 to M3) is in [NOT-YET.md](NOT-YET.md).

The numbers are kept from the earlier plan, other documents refer to them; missing numbers are
done or merged. Items after item 3 build on its kind deployment check in CI.

- [ ] 2. OCI image: Dockerfile with a Java 25 runtime, non-root, `java` as PID 1 so `SIGTERM`
  arrives (exec form); CI builds and pushes it
  - P1.
  - Decided: Temurin JRE (glibc, same vendor as build), registry
    `ghcr.io/sensepitch/sensepitch-edge`, shaded jar kept; see
    [doc/design/oci-image.md](doc/design/oci-image.md).
  - `DockerfileTest` validates the stages, Maven command, shaded JAR copy, non-root user, PID 1
    entry point, exposed ports, build context and the proposed workflow's location.
- [ ] 3. manifests or a minimal Helm chart for a standalone edge: Deployment with probes and
  `terminationGracePeriodSeconds` above `shutdownTimeoutMillis`, Service, PodDisruptionBudget,
  config from a ConfigMap, secrets from Secrets, scrape annotations or ServiceMonitor
  - P1: production on Kubernetes; the node ingress mode is item 9.
  - The standalone chart is in [charts/sensepitch-edge](charts/sensepitch-edge/README.md).
  - Completion requires `helm template` and `kubeconform` validation plus a passing deployment
    test in GitHub Actions on a hosted Ubuntu runner. Create a disposable single-node kind
    cluster with pinned kind and node image versions. Build the edge image once, load it with
    `kind load docker-image`, and install the chart with `helm upgrade --install --wait` and
    `image.pullPolicy=Never`.
  - Verify pod readiness and HTTPS through the Service. Rotate a mounted Secret and verify the
    running edge uses the new value. Terminate a pod and verify graceful shutdown within
    `terminationGracePeriodSeconds`. Reuse the same setup and smoke check locally.
- [ ] 9. node ingress mode in the chart, and a restart without refused connections: DaemonSet on
  labelled ingress nodes, ports 80 and 443 on the node addresses. How the ports are bound decides
  whether a restart can overlap the old and the new pod.
  - P1.
  - Port binding, tick one:
    - [x] `hostNetwork: true`: the edge binds the node addresses itself. Ports below 1024 as
      non-root need a capability; `capabilities.add: [NET_BIND_SERVICE]` is not effective for a
      non-root process without file capabilities on `java`, and the
      `net.ipv4.ip_unprivileged_port_start` sysctl is the node's in the host network namespace
      (inferred from the Kubernetes and Linux capability rules, verify on the cluster).
      `1-architecture.md` assumes the capability is enough.
    - [ ] `hostPort` 80/443 to container ports 17080/17443: the pod stays non-root in its own
      network namespace; the CNI `portmap` plugin forwards with DNAT, which keeps the client
      address (verify with the chosen CNI). The scheduler allows one pod per host port and node,
      so a restart cannot overlap.
  - Restart: with host ports a DaemonSet update stops the old pod before the new one binds
    (`maxSurge: 0`), and the node refuses connections in between. Tick one:
    - [x] accept it: browsers try the next address of the name; check the clients that matter,
      e.g. payment provider webhooks, which may not retry
    - [ ] Move to NOT-YET: `SO_REUSEPORT` and DaemonSet `maxSurge: 1`: the new pod binds next to the old one,
      which stops accepting once the new one is ready (`shutdownAcceptGraceMillis`). Needs
      `hostNetwork` and the Netty epoll or io_uring transport (today NIO, `Proxy`).
  - The node firewall must let 80 and 443 through to the chosen path.
- [ ] 11. dual stack: the default `listen.address` `0.0.0.0` accepts IPv4 only, the nodes have
  IPv6 as well. Verify that `address: "::"` accepts both on one socket, and that an IPv4 client
  then appears as IPv4, not `::ffff:` mapped, in `bypass.remotes`, the IP traits, the request log
  and `X-Forwarded-For`. With `hostPort` the cluster and CNI need dual stack too.
  - P1, before AAAA records point to the nodes.
- [ ] 12. certificates on Kubernetes: an ACME client on the host writes the Let's Encrypt directory
  `listen.letsEncrypt` reads today. Tick one:
  - P1.
  - [ ] cert-manager with DNS-01, one Secret per host or wildcard, mounted through `snis`; a
    renewed Secret reloads, see [doc/design/config-files.md](doc/design/config-files.md). Needs a
    DNS provider with an API cert-manager supports
  - [x] cert-manager with HTTP-01: its solver publishes the challenge through Ingress or
    HTTPRoute objects, which only a controller serves; the standalone edge would need a fixed
    route for `/.well-known/acme-challenge/` to the solver, on the plain HTTP listener
    (`listen.httpPort`)
  - [ ] an ACME client in the edge, with the account and keys shared by all nodes
  - Also: the chart mounts one TLS Secret (`tls.existingSecret`); many hosts need a list of
    Secrets or the `letsEncrypt` directory layout from one volume.
- [ ] 13. IP database on the pods: production reads the IPinfo database (`ipLookup.ipInfoPath`).
  A Secret or ConfigMap holds at most 1 MiB (`extraSecretMounts` in the chart), the database is
  larger (inferred, check the file size). Decide the volume: init container download, image
  layer, or hostPath; and the update without a restart (check whether `ConfigFileWatcher`
  watches it).
  - P1.
- [ ] 14. draining with DNS spreading: a node's address stays in client caches for the DNS TTL
  after it is removed from DNS. Decide who maintains the records without the controller (by hand
  or external-dns with a node source), the TTL, and the order for
  a node drain or upgrade: remove from DNS, wait the TTL, then drain.
  `terminationGracePeriodSeconds` cannot cover a TTL of minutes.
  - P2: until then, drain at quiet times.
- [ ] 15. operator documentation for the direct node ingress: chart values, DNS, certificates,
  firewall, the restart and drain procedure, in the chart README
  - P1, with items 9 to 14.
- [ ] 16. port the two production configurations (shop production, staging and demo, see
  "Production configurations versus Gateway API" in
  [3-gap-analysis.md](doc/kubernetes-support/3-gap-analysis.md)) to the chart: `localhost:<port>`
  upstreams become Service names, the upstream still served by old infrastructure
  (`127.0.0.1:3080`) a host address on the private network; list what does not fit.
  - P1: shows early what the chart or the edge lacks.

## Protection, admission

- [ ] restrict admissions per solved challenge; see the
  [replay draft](doc/design/admission-replay-draft.md) and
  [PoW admission design](doc/design/pow-admission.md)
  - P3: today a challenge is a bare timestamp and an answer can be replayed for five minutes,
    each replay minting another admission. The draft's recommendation, not a decision: sign a
    random, host-bound challenge and derive one admission token deterministically from it, so a
    replay and every replica return the same token without shared state. Tick the open choices
    before implementing:
    - [ ] enforcement: stateless idempotent token, node-local consumed-challenge set, or
      cluster-wide shared store
    - [ ] replay handling: idempotent success, or strict rejection plus a replay metric
    - [ ] permitted reuse: unlimited requests per admission until expiry, or a per-token budget
    - [ ] bind the admission token to the site as well, or keep the challenge binding only
    - [ ] challenge lifetime and clock-skew window; key rotation and overlap period
    - [ ] migration policy for the current unsigned challenge and token format
  - Stays here as a security issue. With several nodes a node-local set lets a replay succeed
    once per node.

## Ingress, HTTP

- [ ] Request size limits: decide the options below, then implement the chosen ones. Research,
  measurements and sources in the draft
  [request-body-limit-draft.md](doc/design/request-body-limit-draft.md)
  - P2. Today: no body limit; the body streams with backpressure (64 KiB Netty water mark), so
    memory is bounded. Netty's header (8192 bytes) and request line (4096) limits answer 400,
    (inferred) as a missing host. Measured on 2026-11-20: NGINX 1.28.3 refuses bodies over 1 MiB
    with 413 because it buffers them; HAProxy 3.2, Caddy 2.11 and Envoy 1.36 stream and have no
    default limit, Caddy and Envoy offer an opt-in 413.
  - Found: a slow upload is not bounded. Every body byte resets the read timeout, so one byte per
    29 s keeps the client and upstream connections open (inferred from the code and a decoder
    experiment).
  - Body limit, tick one:
    - [ ] no limit, as today; document it in the README
    - [ ] optional `maxRequestBodyBytes`, off by default
    - [ ] default limit like NGINX (1 MiB)
  - With an optional limit, where:
    - [ ] `listen.connection`
    - [ ] per site and path route
    - [ ] both, a global value with overrides
  - With an optional limit, the refusal: 413 with `Connection: close` before the body when
    `Content-Length` is too large, mid-stream close of upstream and client for chunked bodies
    - [ ] as described
    - [ ] also answer `Expect: 100-continue` with 413 (check today's `Expect` handling first)
  - Visibility of refusals:
    - [ ] new `RequestOutcome` `request_too_large` in `sensepitch_ingress_request_outcomes_total`
    - [ ] status 413 in the request log only
  - Header and request line:
    - [ ] answer decoder failures explicitly: 431 too large header, 414 too long request line,
      400 other, with outcome or counter
    - [ ] raise the defaults (other proxies refused at 9000 to 70000 bytes)
    - [ ] configurable in `listen.connection` via `HttpDecoderConfig`
  - Slow uploads:
    - [ ] total request receive timeout in `listen.connection`, answered with 408
    - [ ] minimum upload rate
    - [ ] only correct the `ConnectionConfig` doc, which says a byte does not reset the timeout

## Evergreen / Cleanups

- [ ] Review this file and CI regularly: suggest removals or a lower priority for entries without
  a visible effect for the operator, move optional work to [NOT-YET.md](NOT-YET.md), and check the
  GitHub CI actions; add an entry or fix right away if something is broken
- [ ] Improve code according to styling rules
  - Ongoing review principle; record concrete inconsistencies as separate tasks.
- [ ] improve code coverage towards 100%
  - Prioritize uncovered risk and meaningful behavior rather than a percentage alone.

## Governance

- [ ] add LICENSE
  - P2 before an image is published (Kubernetes item 2): a public image needs a license.
