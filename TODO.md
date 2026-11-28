# TODO

Open work, grouped by area, one entry per piece of work; finished work is removed, git keeps its
history. An entry from a `TODO`/`FIXME` comment names the class and method it is about.

Current focus (2026-11-21): first fast tests without flakiness, then run the standalone edge on
Kubernetes as the ingress of our own infrastructure, terminating TLS directly on the nodes,
without a provider load balancer. Optional work outside this focus is parked in
[NOT-YET.md](NOT-YET.md), among it the Gateway API controller, the failover endpoints and disk
spool of the request log, and the circuit breaker for upstreams. Bugs and robustness issues stay
here.

Priorities: P1 = fast and reliable tests, bugs, and what the direct node ingress on Kubernetes
needs; P2 = robustness; P3 = the rest. Notes record decisions to make before implementation.

## Bugs

- [ ] Check the nightly workflows, "Flaky tests":
  https://github.com/sensepitch/sensepitch-edge/actions/workflows/flaky-tests.yml and
  https://github.com/sensepitch/sensepitch-edge/actions/workflows/nightly.yml
  - P1: a test that failed in any run of the flaky test hunt, or a failed nightly run, is a bug
    and has priority over all other work here; add an entry below per problem until it is fixed.
    The flaky test summary lists each failed test with the number of runs it failed in; a failed
    nightly run uploads `nightly-test-reports` (kept 7 days). An excessive test that fails is load
    sensitive or a real regression of allocation or timing.
  - See flaky-tests.yml how to get the test result via GitHub CLI
  - Act only on what the workflows reported on the recent run; a failure seen once in a local
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
done or merged. Items after item 3 build on its deployment check on a local kind cluster.

- [ ] 3. manifests or a minimal Helm chart for a standalone edge: Deployment with probes and
  `terminationGracePeriodSeconds` above `shutdownTimeoutMillis`, Service, PodDisruptionBudget,
  config from a ConfigMap, secrets from Secrets, scrape annotations or ServiceMonitor
  - P1: production on Kubernetes; the node ingress mode is item 9.
  - The standalone chart is in [charts/sensepitch-edge](charts/sensepitch-edge/README.md).
  - Completion requires `helm template` and `kubeconform` validation plus a passing deployment
    test on a local kind cluster, run by a script. Create a disposable single-node kind
    cluster with pinned kind and node image versions. Build the edge image once, load it with
    `kind load docker-image`, and install the chart with `helm upgrade --install --wait` and
    `image.pullPolicy=Never`.
  - Verify pod readiness and HTTPS through the Service. Rotate a mounted Secret and verify the
    running edge uses the new value. Terminate a pod and verify graceful shutdown within
    `terminationGracePeriodSeconds`.
  - Later, not part of completion: run the same script in GitHub Actions on a hosted Ubuntu
    runner.
- [ ] 9. node ingress mode in the chart: DaemonSet on labelled ingress nodes, ports 80 and 443 on
  the node addresses.
  - BLOCKED: requires the passing local kind deployment check of item 3.
  - P1.
  - Decided: `hostPort` 80/443 to container ports 17080/17443. The pod stays non-root in its own
    network namespace; the CNI `portmap` plugin forwards with DNAT, which keeps the client address
    (verify with the chosen CNI, first on kind, whose default CNI supports `portmap`). Cilium
    serves host ports only with its host port support enabled; list such CNI prerequisites in the
    chart README (item 15). Not chosen: `hostNetwork: true`, ports below 1024 as non-root would
    need file capabilities on `java` or the node's `ip_unprivileged_port_start` (inferred, not
    verified); `1-architecture.md` assumes the capability is enough.
  - Decided: a restart refuses connections on the node in between, the scheduler allows one pod
    per host port and node (`maxSurge: 0`); browsers try the next address of the name. Check the
    clients that matter, e.g. payment provider webhooks, which may not retry. The overlap with
    `SO_REUSEPORT` is parked in [NOT-YET.md](NOT-YET.md) under "Configuration, operations".
  - The node firewall must let 80 and 443 through.
- [ ] 12. certificates on Kubernetes, today an ACME client on the host writes the Let's Encrypt
  directory `listen.letsEncrypt` reads.
  - P1.
  - Decided: cert-manager with HTTP-01. Not chosen: cert-manager with DNS-01 (needs a DNS
    provider with an API), an ACME client in the edge (account and keys shared by all nodes).
  - BLOCKED: implementation awaits the route choice and, for stateless mode, the thumbprint
    source choice below; the choice awaits the research of the next point.
  - Research first, before the route choice: read in detail how others answer HTTP-01 and add
    the findings to "Comparable products" in the draft, with versions and sources. For each:
    where the answer comes from (own ACME client, cert-manager solver, stateless), how several
    replicas or nodes all answer a challenge, concurrent challenges, the cert-manager self check.
    - ingress-nginx and Traefik with cert-manager: how they serve the solver Ingresses (read
      the code, the draft only assumes it).
    - Traefik's and Caddy's built-in ACME clients, HAProxy 3.2's native client and HAProxy
      Ingress, Envoy Gateway or Contour with cert-manager.
    - acme.sh stateless mode in practice, and whether anyone runs cert-manager with a stateless
      responder: search the cert-manager issues and discussions (e.g.
      `gh search issues --repo cert-manager/cert-manager stateless`).
  - Research (2026-11-28, cert-manager 1.21.3) in
    [acme-http01-route-draft.md](doc/design/acme-http01-route-draft.md): a fixed route to "the
    solver" does not work. cert-manager starts one solver pod and Service with a generated name
    per challenge, which answers 404 for every other token; a certificate with two names has two
    at once, and every node must answer every challenge (several CA network paths, DNS
    spreading). Choose how the port-80 pipeline answers:
    - [ ] stateless: answer `<token>.<account thumbprint>` itself, like acme.sh's stateless mode
      and HAProxy 3.2; cert-manager's solver objects serve nothing
    - [ ] forward to the solver pods through a headless Service, trying each pod
    - [ ] watch the solver Ingresses of a reserved class through the Kubernetes API, forward
    - [ ] back to DNS-01
  - If stateless, the thumbprint:
    - [ ] configured strings, a list for several accounts and rotation
    - [ ] computed from the mounted account key Secret
  - Then: the chart mounts one TLS Secret (`tls.existingSecret`), many hosts need a list of
    Secrets or the `letsEncrypt` directory layout from one volume.
- [ ] 13. IP database on the pods: production reads the IPinfo database (`ipLookup.ipInfoPath`).
  - P1.
  - Decided: option A of
    [ip-database-volume-draft.md](doc/design/ip-database-volume-draft.md), an init container
    plus a native sidecar (`restartPolicy: Always`) in the chart that download into a shared
    `emptyDir`, each to a temporary file in that directory renamed into place. The edge mounts the
    volume `readOnly: true` and points `ipLookup.ipInfoPath` at it; the token is a small Secret
    mounted only into the two downloader containers. Why: the token and the egress to ipinfo.io
    stay out of the public-facing process, the edge needs no download code, and the existing
    watcher reloads on the rename. Against: every pod downloads 24 MB per update and its sidecar
    needs egress; a pod cannot start while IPinfo is unreachable. Standalone setups use a host
    timer that writes the same way.
  - Facts: IPinfo Lite `ipinfo_lite.mmdb` is 23.71 MB, updated daily, CC BY-SA 4.0, downloaded
    with an account token; a Secret or ConfigMap holds at most 1 MiB. The reader memory-maps the
    file, so the download goes to a temporary file in the same directory and is renamed into
    place, never written in place; `ConfigFileWatcher` already reloads on the rename.
  - Next:
    - a pinned downloader image, or a small script on a minimal image
    - the chart values block: enable, Secret name, URL, interval (daily); an HTTP validator
      (`If-None-Match`/`ETag` or `If-Modified-Since`) in the script, if IPinfo supports it
      (verify)
    - verify on kind that the edge's watcher sees a rename made by the sidecar in the shared
      `emptyDir` (inferred from inotify on a shared node directory, not tested)
    - a test that a renamed database is picked up without a `reload` call
    - the operator documentation (item 15), with the host timer for standalone setups
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

- [ ] Crawler detection from the Sensepitch Known Crawlers release: replace the embedded IP lists
  (`src/main/resources/crawler-ips/`) by the release zips read from a directory. Decisions and
  the operator-visible changes are in
  [known-crawlers-draft.md](doc/design/known-crawlers-draft.md).
  - P2: the embedded lists go stale between releases. Breaking change allowed.
  - Done: `KnownCrawlerLists` reads the zips and returns every matching feed in order, checked
    against the fixture's `expectations.json`. `ipLookup.knownCrawlers` (directory,
    `admittedTierLevel`, `excludeTypes`) names the crawler of an address by the first admitted
    feed in the existing `crawler` trait; a missing or empty directory or a broken zip refuses
    the start; unset, the embedded lists stay in use with a `WARNING`. `DetectCrawler` checks the
    address before the User-Agent (`crawler-ip-match` when both match); the best match that is
    not admitted (tier level, excluded tags) is reported as the `crawler-not-admitted` trait
    without a bypass; full-pipeline scenarios from the fixture in `KnownCrawlersBDDTest`.
  - Open steps, each leaves the edge working:
    - [ ] the list of matches in `IpTraits`, the request log and the headers, replacing the
      `crawler` and `crawler-not-admitted` traits; README sections and a migration note
    - [ ] reload by `ConfigFileWatcher`, a counter for rejected zips
    - [ ] `sensepitch_crawler_requests_total{ingress,vendor,tier,tag,admitted}`, after recording
      the number of vendors, tiers and tags of the current release
    - [ ] remove `src/main/resources/crawler-ips/`, `readCrawlerIpLists`,
      `EmbeddedCrawlerIpLists` and its `update.sh`; unset then means no IP detection
    - [ ] delivery in the chart, the same mechanism as the IP database (item 13)
- [ ] restrict admissions per solved challenge; see the
  [replay draft](doc/design/admission-replay-draft.md) and
  [PoW admission design](doc/design/pow-admission.md)
  - P2, a security issue: today a challenge is a bare timestamp and an answer can be replayed
    for five minutes, each replay minting another admission. All choices are decided
    (2026-11-27); the next step is the implementation plan:
    - [x] enforcement, decided: Option A of the draft, a stateless idempotent token. The
      challenge becomes random and signed, and the admission token is derived
      deterministically from it, so a replay on any replica returns the same token without shared
      state. Not chosen: B (node-local consumed-challenge set), C (cluster-wide shared store),
      D (node-local set propagated to the peers).
    - [x] replay handling, decided: idempotent success, a replay gets the same token; no
      consumed set and no replay metric. Not chosen: strict rejection plus a replay metric
    - [x] permitted reuse, decided: unlimited requests per admission until expiry
      (`admissionMaxAgeMillis`). Not chosen: a per-token budget
    - [x] site binding, decided: none. A challenge and its admission are valid on every site
      sharing the generator secret, so an admission can be accepted across domains without a new
      proof of work; sites that must stay separate use separate secrets
    - [x] timing, decided: a challenge is valid for five minutes after its signed issue time and
      up to 30 seconds in the future (clock skew). The challenge key is derived from the
      generator secret with its own label; rotation reuses `tokenGenerators`, so a removed
      generator must have been unused for issuing for at least lifetime plus skew. The
      `sensepitch-ch` cookie's `Max-Age` follows the challenge lifetime
    - [x] migration, decided: unsigned challenges are rejected from the upgrade on, so a page
      loaded before it re-solves once; admission tokens of the old format stay valid until they
      expire, told apart by a version prefix
  - Stays here as a security issue until implemented. With Option A, replaying an answer on
    any node or site returns the same token, so replay no longer mints new admissions.
- [ ] pass the browser traits of the challenge answer (`webdriver`, timezone) on with the
  admission, so later requests and the upstream see them; see
  [browser fingerprinting](doc/design/browser-fingerprinting.md)
  - P3.

## Ingress, HTTP

- [ ] Slow and large requests: decide the options below, then implement the chosen ones.
  Research, measurements and sources in the drafts
  [request-body-limit-draft.md](doc/design/request-body-limit-draft.md) and
  [slow-request-protection-draft.md](doc/design/slow-request-protection-draft.md).
  - P2. Today (read and measured on 2026-11-20 and 2026-11-26):
    - Body: no limit, decided and documented in the README; the body streams with backpressure
      (64 KiB Netty water mark), so memory is bounded. NGINX 1.28.3 refuses bodies over 1 MiB with
      413 because it buffers them; HAProxy 3.2, Caddy 2.11 and Envoy 1.36 stream and have no
      default limit, Caddy and Envoy offer an opt-in 413.
    - Head: Netty's header (8192 bytes) and request line (4096) limits are answered with 431 and
      414 by `DecoderFailureHandler`, outcome `request_too_large`. The head is bounded by a total
      of `readTimeoutSeconds` (30 s) from accept or from the end of the previous response, since
      the codec emits it only when complete; a request without `Content-Length` and
      `Transfer-Encoding` ends with its head (RFC 9112 6.3). The same timer is the idle body
      timeout and the keep-alive wait, so the head cannot be tightened alone.
    - Slow upload: every body byte resets the read timeout, so one byte per 29 s keeps the client
      and upstream connections open, unless `listen.connection.requestTimeoutSeconds` is set: the
      total time to receive the body, answered with 408, phase `request_total` in
      `sensepitch_ingress_receive_timeout`, outcome `request_timeout`; `0`, the default, is off.
  - Default of `requestTimeoutSeconds`:
    - [ ] off, as today, operators opt in
    - [x] on, e.g. 300 s
  - Body limit beyond "no limit", if at all:
    - [x] optional `maxRequestBodyBytes`, off by default, outcome `request_too_large`
    - [ ] none
  - Minimum body rate, like Apache `MinRate`:
    - [ ] none, `requestTimeoutSeconds` and the idle timeout only
    - [x] 500 bytes/s after a grace time of 20 s, capped by `requestTimeoutSeconds`
  - Head timeout:
    - [x] new `requestHeadTimeoutSeconds` in `listen.connection` (10 s, 20 s or 30 s), 408 when the
      head is not complete in time
    - [ ] keep `readTimeoutSeconds`, only document that it bounds the head in total
  - Start of the head timer:
    - [ ] at accept and at the end of the previous response, as today
    - [x] at the first byte of the head, with a separate keep-alive wait
  - Requests without a body:
    - [ ] nothing separate, the head timeout is their limit
    - [x] refuse a body in `GET`, `HEAD` and `DELETE`
  - Header and request line limits (other proxies refused at 9000 to 70000 bytes):
    - [ ] keep Netty's defaults
    - [ ] raise the defaults
    - [x] configurable in `listen.connection` via `HttpDecoderConfig`
  - Overrides:
    - [ ] body limits per site and per path
    - [x] listener level only
  - Metric phases:
    - [x] separate `head`, `body_idle`, `body_rate`, `request_total`
    - [ ] today's phases plus `body_rate`

- [ ] QUIC support, HTTP/3 to the clients
  - The [benchmark](performance-test/benchmark/README.md) can measure it: `shb` speaks HTTP/3.
  - Fits the rule that only encrypted traffic enters the request pipeline: QUIC always runs
    TLS 1.3, see [encrypted-transport.md](doc/design/encrypted-transport.md).
  - enabled by default, listener gets quic.disable. QuickConfig just has disable for now, maybe we need more protocol options (ideas?)
  - Do some IT tests with a minimal Netty client, if that is possible, shb otherwise
  - Add QUIC to the benchmarks, meaning benchmark needs to run twice the time with HTTP1.1 and QUIC

## Evergreen / Cleanups

- [ ] Review this file and CI regularly: suggest removals or a lower priority for entries without
  a visible effect for the operator, move optional work to [NOT-YET.md](NOT-YET.md), and check the
  GitHub CI actions; add an entry or fix right away if something is broken
- [ ] Improve code according to rules; Ongoing review principle; record concrete inconsistencies as separate tasks.
- [ ] improve code coverage towards 100%, prioritize uncovered risk and meaningful behavior rather than a percentage alone.
- [ ] Check and fix that no unit tests waits in real time, replace with a simulated clock, see [doc/design/test-speed.md](doc/design/test-speed.md)
