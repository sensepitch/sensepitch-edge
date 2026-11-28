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

## Bugs

- [ ] Check the nightly workflows, "Flaky tests":
  https://github.com/sensepitch/sensepitch-edge/actions/workflows/flaky-tests.yml and
  https://github.com/sensepitch/sensepitch-edge/actions/workflows/nightly.yml
  - P1: a test that failed in any run of the flaky test hunt, or a failed nightly run, is a bug
    and has priority over all other work here; add an entry below per problem until it is fixed.
    The flaky test summary lists each failed test with the number of runs it failed in; a failed
    nightly run uploads `nightly-test-reports` (kept 7 days). An excessive test that fails is load
    sensitive or a real regression of allocation or timing.
  - See flaky-tests.yml how to get the rest result via GitHub CLI
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
  - BLOCKED: requires the passing kind deployment check in item 3.
  - P1.
  - Port binding, tick one; rule: first the option with the least extra configuration that works
    in the most setups:
    - [ ] `hostNetwork: true`: the edge binds the node addresses itself. Ports below 1024 as
      non-root need a capability; `capabilities.add: [NET_BIND_SERVICE]` is not effective for a
      non-root process without file capabilities on `java`, and the
      `net.ipv4.ip_unprivileged_port_start` sysctl is the node's in the host network namespace
      (inferred from the Kubernetes and Linux capability rules, verify on the cluster).
      `1-architecture.md` assumes the capability is enough.
    - [x] `hostPort` 80/443 to container ports 17080/17443: the pod stays non-root in its own
      network namespace; the CNI `portmap` plugin forwards with DNAT, which keeps the client
      address (verify with the chosen CNI). The scheduler allows one pod per host port and node,
      so a restart cannot overlap. Verify on kind first (its default CNI supports `portmap`);
      Cilium serves host ports only with its host port support enabled, list such CNI
      prerequisites in the chart README (item 15)
  - Restart: with host ports a DaemonSet update stops the old pod before the new one binds
    (`maxSurge: 0`), and the node refuses connections in between. Tick one:
    - [x] accept it: browsers try the next address of the name; check the clients that matter,
      e.g. payment provider webhooks, which may not retry
    - [ ] overlap with `SO_REUSEPORT`, parked in [NOT-YET.md](NOT-YET.md) under "Configuration,
      operations"
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
    - [x] edge gets writable volume and does regular updates by itself, access token and 
      maybe URL for IPInfo needs to be confiugres
  - Update interval, tick one; A and B reach the running edge through the watcher, C and D
    through a rollout restart:
    - [x] daily, IPinfo's cadence, confurable check interval with HTTP validator skipping if nothing is new
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

- [ ] Crawler detection from the Sensepitch Known Crawlers release: drop the embedded IP lists
  (`src/main/resources/crawler-ips/`, `CombinedIpTraitsLookup.readCrawlerIpLists`) and read the
  release zips from a directory instead. The fixture and the pointer to the format description
  are in `src/test/resources/sensepitch-known-crawlers/`; its `expectations.json` is the test
  oracle for the matching. Breaking change allowed. Decided 2026-11-27:
  - Scope: only the IP lists are replaced; the User-Agent list (`crawlers.tsv`,
    `DetectCrawlerConfig`) stays unchanged.
  - Configuration:
    - directory of the zip files in the local file system; not set means no IP crawler
      detection, startup logs one `WARNING`, the User-Agent list still applies
    - `admittedTierLevel`, default `1`: feeds of tier 1 up to this level are admitted as
      crawlers (tiers stand for trustworthiness, 1 is the most trusted)
    - `excludeTypes`: tags; a feed with any excluded tag is not admitted
  - Not admitted is not invisible: a match of a higher tier or an excluded feed is still
    recognised and reported, it only does not bypass the challenge.
  - Several matches (feeds overlap): the best admitted match decides, ordered by tier, then
    vendor and list name, as in `expectations.json`. The request log and the headers list every
    match in that order, each with vendor, list name, tier and tags; `IpTraits` carries a list
    instead of one `crawlerName`.
  - Updates: the directory is watched (`ConfigFileWatcher`), a zip renamed into place reloads
    the lists; a broken zip or an unknown `meta.json` `version` keeps the previous lists and is
    counted. The tier is taken from `meta.json`. The operator or a host job fills the directory.
  - Metrics: the crawler counter gets a `tier` label and a `tag` label, one increment per tag of
    the deciding match. Both values come from the configured data, not from the request, so the
    cardinality is bounded by the loaded lists; check it stays small with the real release.
  - Done: `KnownCrawlerLists` reads the zips of a directory and returns every matching feed in
    the order above, checked against `expectations.json`; not wired into the request path yet.
    Open: the configuration (directory, `admittedTierLevel`, `excludeTypes`, the `WARNING` when
    unset), the match list in `IpTraits`, the request log and headers, the reload by the watcher,
    the metric labels, and removing `src/main/resources/crawler-ips/` with
    `readCrawlerIpLists`.
- [ ] restrict admissions per solved challenge; see the
  [replay draft](doc/design/admission-replay-draft.md) and
  [PoW admission design](doc/design/pow-admission.md)
  - P3: today a challenge is a bare timestamp and an answer can be replayed for five minutes,
    each replay minting another admission. All choices are decided (2026-11-27); the next step
    is the implementation plan:
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

## Transport encryption

Decisions and reasons in [encrypted transport](doc/design/encrypted-transport.md).

- [ ] fail closed in the request pipeline when a request has no TLS session, instead of logging
  `url.scheme` `http`; needs a test seam that stands in for the TLS session of the
  `EmbeddedChannel` tests
- [ ] decide which TLS 1.2 cipher suites to allow, including whether to keep suites without
  forward secrecy or with CBC/SHA-1

## Ingress, HTTP

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
- [ ] Slow request protection: decide the options below, then implement the chosen ones. Research
  and sources in the draft
  [slow-request-protection-draft.md](doc/design/slow-request-protection-draft.md)
  - P2. Today (read and measured on 2026-11-26): the head is already bounded by a total of
    `readTimeoutSeconds` (30 s) from accept or from the end of the previous response, since the
    codec emits it only when complete; a request without `Content-Length` and
    `Transfer-Encoding` ends with its head (RFC 9112 6.3). The same timer is also the idle body
    timeout and the keep-alive wait, so the head cannot be tightened alone. The body total
    `requestTimeoutSeconds` is off by default, see the request size entry above.
  - Head timeout:
    - [ ] new `requestHeadTimeoutSeconds` in `listen.connection`, 408 when the head is not
      complete in time
    - [ ] keep `readTimeoutSeconds`, only document that it bounds the head in total
  - Start of the head timer:
    - [ ] at accept and at the end of the previous response, as today
    - [ ] at the first byte of the head, with a separate keep-alive wait
  - Requests without a body:
    - [ ] nothing separate, the head timeout is their limit
    - [ ] refuse a body in `GET`, `HEAD` and `DELETE`
  - Body:
    - [ ] minimum rate after a grace time, like Apache `MinRate`, capped by
      `requestTimeoutSeconds`
    - [ ] only `requestTimeoutSeconds`, on by default
    - [ ] idle only, as today
  - Overrides:
    - [ ] body limits per site and per path
    - [ ] listener level only
  - Defaults:
    - [ ] head 10 s
    - [ ] head 20 s
    - [ ] head 30 s, as today
    - [ ] minimum body rate off
    - [ ] minimum body rate 500 bytes/s after 20 s
  - Metric phases:
    - [ ] separate `head`, `body_idle`, `body_rate`, `request_total`
    - [ ] today's phases plus `body_rate`

## Evergreen / Cleanups

- [ ] Review this file and CI regularly: suggest removals or a lower priority for entries without
  a visible effect for the operator, move optional work to [NOT-YET.md](NOT-YET.md), and check the
  GitHub CI actions; add an entry or fix right away if something is broken
- [ ] Improve code according to rules
  - Ongoing review principle; record concrete inconsistencies as separate tasks.
- [ ] improve code coverage towards 100%
  - Prioritize uncovered risk and meaningful behavior rather than a percentage alone.
