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
  - `ProxyShutdownIT`: the shutdown clock seam is there (`startProxy` with a nano time and wait,
    as in `acceptGraceKeepsServingThenClosesWithinTheBudget`); `slowRequestIsAbortedAndStillLogged`
    (about 1 s) and `unsentRecordsAreReported` (about 0.5 s) still wait real shutdown and drain
    timeouts. `awaitListening` probes with a literal 250 ms connect timeout.
  - `ConfigFileWatcherTest`: no clock or scheduler seam, and the watcher waits on the file
    system; the test stays in the unit scope meanwhile, see the coverage rule in `AGENTS.md`. One
    line and one branch of `ConfigFileWatcher` are covered only in some runs, so the JaCoCo floor
    in `pom.xml` has to sit below the best run.
  - `AsyncRequestLogWriter.reportError`: the branch for an error that was already reported is
    covered only when a test happens to produce a second write error, so the floor sits below
    the best run for it too. Cover it deterministically in `AsyncRequestLogWriterTest`.
  - Not worth converting: the `MockTicker` users (`CircuitBreakerBDDTest`, `TimeoutsTest`,
    `ResponseTimeoutBDDTest`, `MetricsExpositionTest`) need a ticker only, no scheduler.
    `AsyncRequestLogWriterTest` has no timer to simulate, it waits on latches only.
    `ProxyOptionsTest` (OTLP with a 1 ms flush) waits for the post as an event and asserts no
    timing.
- [ ] Instance scoped diagnostic logging and event driven waits, plan in
  [plans/instance-scoped-logging.md](plans/instance-scoped-logging.md)
  - P1, test speed and clarity, no operator visible change: non-trace loggers become instance
    fields owned by a `LogScope` (proxy instance and component), trace stays on a `static final`
    `TraceLogger`. Tests capture by scope instead of by thread, so `LogCapture.ofAllThreads()`
    and the `@Isolated` it forces go away, and `LogCapture.await` waits for a record without
    polling. Not started yet; the classes held `@Isolated` by it are listed in
    [doc/design/test-speed.md](doc/design/test-speed.md).
  - Polling to replace: `ExternalTcpIT` (`awaitLogged`, `awaitRequestLog`,
    `awaitHandshakeErrors`) and `ShadedJarIT.awaitOutput` (reads the stderr file). Where no log is
    involved: a test log server that signals each post with a `Semaphore` or queue, a latch in the
    reload callback.
    `TestingParameters.await` stays where no hook exists.
- [ ] Smaller repeated fixtures, checked 2026-11-26, not worth a change alone
  - The crawler IP lists (13.5 ms per parse) are parsed 8 times per run now: 5 in
    `ConfigReloadTest` (each rebuilt IP lookup), 3 in `CombinedIpTraitsLookupTest`. A rebuilt IP
    lookup could take the lists over from the previous state, like the SSL contexts.
  - `OtlpLogDestinationTest` starts a JDK `HttpServer` before each of its 28 tests, also those
    that use the simulated transport only; a few ms each. Start it only in the tests that post
    to it, when the class is touched anyway. Its slowest test, `limitDropsTheNewestRecords`
    (1.45 s), spends the time in logging enough records to exceed the buffer limit, not in setup.
  - `DetectCrawler` (0.4 ms) and `PublicIpv4Finder` (0.3 ms) per deflector: negligible.

## Bugs

- [ ] Check the nightly workflows, "Flaky tests":
  https://github.com/sensepitch/sensepitch-edge/actions/workflows/flaky-tests.yml and
  https://github.com/sensepitch/sensepitch-edge/actions/workflows/nightly.yml
  - BLOCKED: GitHub API unreachable during selection. Recent run artifacts needed.
  - P1: a test that failed in any run of the flaky test hunt, or a failed nightly run, is a bug
    and has priority over all other work here; add an entry below per problem until it is fixed.
    The flaky test summary lists each failed test with the number of runs it failed in; a failed
    nightly run uploads `nightly-test-reports` (kept 7 days). An excessive test that fails is load
    sensitive or a real regression of allocation or timing.
  - See flaky-tests.yml how to get the rest result via GitHub CLI
  - Act only on what the workflows reported on the recent run; a failure seen once in a local
    or agent build is not added here. The general remedies are in "Fast tests without
    flakiness" above.
- [ ] `KeyInjector`: a map key that is not a string leaves the record's `key` unset
  - P3: `getMethod("key", key.getClass())` finds no `key(Integer)` for a YAML key like `123:`,
    the `NoSuchMethodException` is ignored. Inferred from the code, not verified with YAML. Use
    the key as string or reject it with a clear message.

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
    - [x] Add as new task to NOT-YET.md: `SO_REUSEPORT` and DaemonSet `maxSurge: 1`: the new pod binds next to the old one,
      which stops accepting once the new one is ready (`shutdownAcceptGraceMillis`). Needs
      `hostNetwork` and the Netty epoll or io_uring transport (today NIO, `Proxy`).
  - The node firewall must let 80 and 443 through to the chosen path.
- [x] 11. dual stack: the default `listen.address` `0.0.0.0` accepts IPv4 only, the nodes have
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
  Research with sources and the evidence for each point:
  [ip-database-volume-draft.md](doc/design/ip-database-volume-draft.md).
  - P1.
  - BLOCKED: chart implementation awaits the volume and update interval choices below.
  - Facts: IPinfo Lite `ipinfo_lite.mmdb` is 23.71 MB, updated daily, CC BY-SA 4.0, downloaded
    with an account token. A Secret or ConfigMap holds at most 1 MiB, so neither can carry it.
    `ConfigFileWatcher` already reloads by itself when the file is renamed into place.
    The reader memory-maps the file, so an updater must write a temporary file in the same
    directory and rename it, never write in place.
  - Volume, tick one:
    - [ ] A. init container download plus a native sidecar that refreshes into an `emptyDir`;
      token from a small Secret; every pod downloads and needs egress to IPinfo
    - [ ] B. read-only `hostPath` directory (not the file) on the ingress nodes, filled by a
      host timer; node setup outside the chart
    - [ ] C. image volume with the database as an OCI artifact (stable since Kubernetes 1.36);
      every update is a pod restart
    - [ ] D. baked into the edge image; every update is an image build and rollout, and the
      public image redistributes CC BY-SA data
  - Update interval, tick one; A and B reach the running edge through the watcher, C and D
    through a rollout restart:
    - [ ] daily, IPinfo's cadence
    - [ ] less often, e.g. weekly, to save transfer
  - Then: a chart values block for the chosen volume, its operator documentation (item 15), and
    a test that the running watcher picks up a renamed database without a `reload` call.
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
  - BLOCKED: replay handling, permitted reuse, timing and migration choices remain open.
  - P3: today a challenge is a bare timestamp and an answer can be replayed for five minutes,
    each replay minting another admission. Tick the open choices before implementing:
    - [x] enforcement, decided: Option A of the draft, a stateless idempotent token. The
      challenge becomes random and signed, and the admission token is derived
      deterministically from it, so a replay on any replica returns the same token without shared
      state. Not chosen: B (node-local consumed-challenge set), C (cluster-wide shared store),
      D (node-local set propagated to the peers).
    - [ ] replay handling: idempotent success, or strict rejection plus a replay metric
    - [ ] permitted reuse: unlimited requests per admission until expiry, or a per-token budget
    - [x] site binding, decided: none. A challenge and its admission are valid on every site
      sharing the generator secret, so an admission can be accepted across domains without a new
      proof of work; sites that must stay separate use separate secrets
    - [ ] carry an admission to another domain, since cookies are host-only (see the draft's
      limitations)
    - [ ] challenge lifetime and clock-skew window; key rotation and overlap period
    - [ ] migration policy for the current unsigned challenge and token format
  - Stays here as a security issue until implemented. With Option A, replaying an answer on
    any node or site returns the same token, so replay no longer mints new admissions.
- [ ] pass the browser traits of the challenge answer (`webdriver`, timezone) on with the
  admission, so later requests and the upstream see them; see
  [browser fingerprinting](doc/design/browser-fingerprinting.md)

## Transport encryption

Decisions and reasons in [encrypted transport](doc/design/encrypted-transport.md).

- [ ] fail closed in the request pipeline when a request has no TLS session, instead of logging
  `url.scheme` `http`; needs a test seam that stands in for the TLS session of the
  `EmbeddedChannel` tests
- [ ] a TLS policy of the edge instead of the library defaults: pin and test the minimum
  TLS 1.2, decide on the suites without forward secrecy and with CBC/SHA-1
- [ ] send `Strict-Transport-Security: max-age=31536000` on every response the upstream sends
  without one, not configurable

## Ingress, HTTP

- [ ] Bind the upstream connection to the event loop of its client connection: `DefaultUpstream`
  bootstraps on the whole event loop group (`.group(ctx.eventLoopGroup())`), so the
  `ForwardHandler` and the `DownstreamHandler` of one exchange usually run on different threads
  and every hop between them is a task on the other loop. One loop per exchange would save the
  hops and make the state of the exchange single threaded; check the load balance across loops.

- [ ] Request size limits: decide the options below, then implement the chosen ones. Research,
  measurements and sources in the draft
  [request-body-limit-draft.md](doc/design/request-body-limit-draft.md)
  - P2. Today: no body limit; the body streams with backpressure (64 KiB Netty water mark), so
    memory is bounded. Netty's header (8192 bytes) and request line (4096) limits answer 431 and
    414 (`DecoderFailureHandler`). Measured on 2026-11-20: NGINX 1.28.3 refuses bodies over 1 MiB
    with 413 because it buffers them; HAProxy 3.2, Caddy 2.11 and Envoy 1.36 stream and have no
    default limit, Caddy and Envoy offer an opt-in 413.
  - Found: a slow upload is not bounded. Every body byte resets the read timeout, so one byte per
    29 s keeps the client and upstream connections open (inferred from the code and a decoder
    experiment). Bounded now when `listen.connection.requestTimeoutSeconds` is set, see below.
  - Body limit, tick one:
    - [x] no limit, as today; document it in the README
    - [ ] optional `maxRequestBodyBytes`, off by default
    - [ ] default limit like NGINX (1 MiB)
  - Visibility of refusals:
    - [x] new `RequestOutcome` `request_too_large` in `sensepitch_ingress_request_outcomes_total`
    - [ ] status 413 in the request log only
  - Header and request line:
    - [x] answer decoder failures explicitly: 431 too large header, 414 too long request line,
      400 other, with outcome or counter. Done: `DecoderFailureHandler`, outcome
      `request_too_large`
    - [ ] raise the defaults (other proxies refused at 9000 to 70000 bytes)
    - [ ] configurable in `listen.connection` via `HttpDecoderConfig`
  - Slow uploads:
    - [x] total request receive timeout in `listen.connection`, answered with 408. Done:
      `requestTimeoutSeconds` in `ConnectionConfig`, counted with phase `request_total` in
      `sensepitch_ingress_receive_timeout`, outcome `request_timeout`; `0` is off
    - Default of `requestTimeoutSeconds`, tick one (today `0`, off, so existing setups are
      unchanged):
      - [ ] off by default, operators opt in
      - [ ] on by default, e.g. 300 s
    - [ ] minimum upload rate
    - [ ] only correct the `ConnectionConfig` doc, which says a byte does not reset the timeout
- [ ] Protection for slow requests. Have a better protection than a per request timeout, which allows more time for legit uploads and stricter timerouts for simple GET requests which should arrive in one go. Do a research design draft first

## Evergreen / Cleanups

- [ ] Use *exchange* where docs, comments and internal names say "request" but mean the request
  with its response, see the [glossary](doc/glossary.md), e.g. "the request then ends by its
  timeout" in the class doc of `ForwardHandler`. Names that are part of an interface (metric
  names such as `sensepitch_ingress_requests_in_flight`, log fields, config keys) stay.
- [ ] Review this file and CI regularly: suggest removals or a lower priority for entries without
  a visible effect for the operator, move optional work to [NOT-YET.md](NOT-YET.md), and check the
  GitHub CI actions; add an entry or fix right away if something is broken
- [ ] Improve code according to rules
  - Ongoing review principle; record concrete inconsistencies as separate tasks.
- [ ] improve code coverage towards 100%
  - Prioritize uncovered risk and meaningful behavior rather than a percentage alone.
