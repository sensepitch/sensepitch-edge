# Several targets per upstream

**Draft, nothing implemented.** Today an upstream has exactly one target
([`UpstreamConfig`](../../src/main/java/org/sensepitch/edge/UpstreamConfig.java) `target`,
`host:port`, resolved by the JVM resolver to its first address), one
[`UpstreamConnectionPool`](../../src/main/java/org/sensepitch/edge/UpstreamConnectionPool.java)
partitioned per event loop, see [upstream-connection-pool.md](upstream-connection-pool.md), and
is meant to get one [`CircuitBreaker`](../../src/main/java/org/sensepitch/edge/CircuitBreaker.java),
see [circuit-breaker.md](circuit-breaker.md). This document records how one upstream with several
targets is to be built and what was compared, before the implementation. It is tracked as
"multiple servers per upstream" in [NOT-YET.md](../../NOT-YET.md).

## Purpose and scope

An application runs on several instances. The edge should spread the requests over them, take an
instance out that is down, and keep serving with the others. Bounds:

- The hot path stays as it is: per request on the client's event loop, no lock, no state shared
  between the loops, see "One pool partition per event loop and upstream" in
  [upstream-connection-pool.md](upstream-connection-pool.md).
- Existing setups with one `target` keep working unchanged.
- Network input is untrusted, metric labels come from the configuration only.

## Overview

```yaml
upstreams:
  shop:
    targets: [shop-1:8080, shop-2:8080]                                  # new
    connectionPool: { maxConnections: 10000 }      # for the upstream, all targets together
    circuitBreaker: { failureThreshold: 5, probe: { path: /health } }    # per target
```

`target: x` stays valid and means `targets: [x]`; the builder normalises it, setting both is
rejected. No per target settings and no weights in the first version.

```
UpstreamConnectionPool "shop"
 └─ Partition (one per event loop, used only on its loop)
     ├─ open:  shop-1 = 3, shop-2 = 1        (sum <= maxConnections / loops)
     └─ idle:  shop-1 [c7, c3]  shop-2 [c9]  (most recent first)
Target shop-1:8080 ─ CircuitBreaker         Target shop-2:8080 ─ CircuitBreaker
```

A request picks the target first (least connections open on its loop, skipping targets whose
breaker refuses), then takes an idle connection of that target from the partition, else opens a
new one to it.

## Design decisions

### One pool and one connection limit per upstream

`maxConnections` limits the upstream as a whole, shared by the event loops as today. The
partition of a loop keeps one open count and one idle deque per target, so the limit check stays
a sum on one thread, without a lock or a shared counter. The operator protects the service, and
the number stays right when targets are added; with a limit per target (NGINX `max_conns`,
HAProxy `maxconn`) the total grows with the number of targets. Envoy also limits per cluster
(`max_connections`), not per host. A slow target that holds many connections gets fewer new
requests through the target choice below, so it does not use up the budget of the others. The
local ports run out per remote address, so a limit per upstream is on the safe side.

### The target first, least connections per event loop

Per request, on the client's event loop: the target with the fewest open connections in the
partition of this loop, on a tie round robin from an index per loop; then an idle connection of
that target, else a new one. Targets whose breaker refuses are skipped. The counts are in the
partition already, so the choice shares nothing between the loops and costs nothing extra. The
view is local, a loop does not see the connections of the others; with the clients spread evenly
over the loops the local counts balance as well.

An idle connection to another target is not used. That is a little less reuse, for a load that
follows the balancer: a target that recovered or was added gets requests at once, and draining a
target is simple, its idle connections are in one place. Every implementation reviewed below
picks the target first.

### A breaker per target, never all targets out

Each target has its own `CircuitBreaker`, with the threshold and the probe of the upstream, see
"Planned improvements" in [circuit-breaker.md](circuit-breaker.md). With other targets serving,
taking one out keeps the requests away from it; a failure rate or a slow call rate may make sense
here later, the first version keeps consecutive failures.

If every target is open, the request still goes to one: with probes by the edge to the one with
the latest successful probe, otherwise the request is the probe of a target. Refusing everything
would turn a partial failure into a total one, as for a single server; Envoy has a similar
safeguard, the panic threshold (from memory, not verified). The upstream counts as unavailable
only when no target is up.

When a breaker opens or is forced open, the idle connections of its target are closed, with the
close reason `removed` or a new one, decided with the implementation. An `INFO` line is logged
per target when it becomes unavailable and when it is alive again, and one when the whole
upstream becomes unavailable or available.

### Retry on another target after a connect failure

A connect failure sent nothing, so the request goes once to another target, whatever the method;
failing over after a connect failure is the main reason for several targets. The existing retry
after a stale pooled connection (idempotent, no body) stays on the same target, with a new
connection: the target is up, only the connection was stale. At most one retry per exchange, as
today, so a failing upstream does not get twice the load. A request that was sent is not retried
on another target, it may have had an effect.

### The connections per target are reported

`sensepitch_upstream_pool_connections`, the open connections that `maxConnections` limits, gets a
`target` label; `sum by (upstream)` gives the value of today. The values come from the
configuration, so the cardinality is bounded. It changes the label set of this one series:
queries that aggregate by `upstream` keep working, exact matches do not. The other pool metrics
(opened, reused, closed by reason, idle, rejected, retried) stay per upstream, a `target` label
can follow where a need shows up.

The breaker of a target is registered as `component="upstream"`, `name="<upstream>/<target>"`, so
the admin endpoint can take one target out for maintenance (`FORCED_OPEN`) without a new
protocol. The request log records the target used (`server.address`, `server.port`), not the
configured list.

### Readiness and reload

The edge is ready when every upstream has at least one target up; this replaces the TCP probe of
[`UpstreamReadinessProbe`](../../src/main/java/org/sensepitch/edge/UpstreamReadinessProbe.java)
once the breakers exist. A reload that changes the target list creates a new upstream,
[`UpstreamRegistry`](../../src/main/java/org/sensepitch/edge/UpstreamRegistry.java) compares the
whole configuration, so the idle connections of the unchanged targets are lost; acceptable at
first.

## Implementations investigated

The pool facts are from the notes in [upstream-connection-pool.md](upstream-connection-pool.md),
read from the sources on 2026-11-27. How each selects among several servers and where its limit
sits is **from memory, not verified**.

| Implementation | Picks | Idle connections | Connection limit |
|---|---|---|---|
| NGINX | the balancer picks the server, then the keepalive cache is searched for one to its address | one cache per `upstream` and worker, for all its servers | `max_conns` per server; the cache size per upstream |
| HAProxy | the balancer (`roundrobin`, `leastconn`, …) picks the server, then its idle list | per server and thread | `maxconn` per server, a queue in front |
| Envoy | the load balancer picks the host, then its pool | per host and worker thread | `max_connections` per cluster, shared by its hosts |
| Pingora | the load balancer picks the backend, then the pool by peer key | one global pool, keyed by peer | none in the pool |
| Armeria, Reactor Netty, Vert.x | the endpoint first | per endpoint or remote address | per endpoint |

## Alternatives considered

- **A pool per target**, with a limit per target, as NGINX and HAProxy. Protects each instance,
  but the budget grows with the number of targets, so "at most N to this service" has to be
  divided by hand, and the pool metrics would all need a `target` label.
- **The connection first**: take the most recently released idle connection of any target that
  is up, open a new one only when none is idle. The best reuse, but the load drifts to the
  fastest target, and a target that recovered only gets requests in bursts. None of the reviewed
  implementations does it.

## Limitations

- The target choice sees the connections of its own event loop only.
- A reload that changes the targets loses the idle connections of the unchanged ones.
- One address per target: a name that resolves to several addresses uses the first.

## Planned improvements

- Discovery of the targets from DNS, e.g. a Kubernetes headless Service, with re-resolution; it
  fills the same target structure.
- Taking over unchanged targets on a reload.
- Weights, sticky sessions, a slow start for a recovered target.
