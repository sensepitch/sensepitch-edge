# Upstream connection pool

Keep-alive connections to the upstreams, reused per event loop. Implemented in
[`UpstreamConnectionPool`](../../src/main/java/org/sensepitch/edge/UpstreamConnectionPool.java),
used by [`DefaultUpstream`](../../src/main/java/org/sensepitch/edge/DefaultUpstream.java) behind
the [`Upstream`](../../src/main/java/org/sensepitch/edge/Upstream.java) interface; the exchange in
[`DownstreamHandler`](../../src/main/java/org/sensepitch/edge/DownstreamHandler.java) decides
whether a connection is reused and retries a request on a stale one,
[`ForwardHandler`](../../src/main/java/org/sensepitch/edge/ForwardHandler.java) binds a pooled
connection to the next exchange. Configured by
[`ConnectionPoolConfig`](../../src/main/java/org/sensepitch/edge/ConnectionPoolConfig.java). This
document records why it is built this way and the research it is based on; it started as a
draft with open choices, decided on 2026-11-27.

## Purpose and scope

Before the pool, every exchange opened a TCP connection to the upstream on the event loop of its
client channel, and `DownstreamHandler` closed it after the response. A pool saves the connect per
exchange: a round trip of latency, an ephemeral port in `TIME_WAIT` on our side, and the accept
work of the upstream.

The ports were a hard limit. The edge closed the upstream connection first, so each one
holds a local port in `TIME_WAIT` for 60 s. With the Linux default `ip_local_port_range`
(28232 ports) and `tcp_tw_reuse` for loopback only, that allows about 470 requests per second to
one upstream address; above it the connects fail and the client gets `502 Upstream connection
problem`. Measured on 2026-11-27: at 10000 and 50000 requests per second only 3 to 58 % of the
requests succeeded, at 300 all, see
[performance-test/runs/2026-11-27-upstream-event-loop](../../performance-test/runs/2026-11-27-upstream-event-loop/README.md).

These rules bound the design:

- The exchange stays on the event loop of the client channel: `Upstream#connect` returns a
  channel on that loop, and `DownstreamHandler` relies on it, it does not hand the listeners of
  the connect and of the upstream writes over to its own loop.
- From `AGENTS.md`: never block an event loop; keep idle connections, timers and queues bounded;
  metric labels bounded (the upstream label is a name from the configuration); every request
  level event is counted; timeouts are tested on a simulated clock; config records carry their
  defaults in `builder()`; no static state, the pool lives as long as the proxy state and is
  replaced by a reload.
- HTTP/1.1 only, no pipelining, as the client side.
- RFC 9112 section 9.3.1: "Connections can be closed at any time ... Implementations ought to
  anticipate the need to recover from asynchronous close events." Section 9.5 describes the race:
  the upstream closes a connection it sees as idle while the proxy starts a request on it.
- RFC 9110 section 9.2.2: idempotent requests "can be repeated automatically if a communication
  failure occurs before the client is able to read the server's response", and "A proxy MUST NOT
  automatically retry non-idempotent requests."

History: an earlier pool with Netty's `SimpleChannelPool`, shared by all event loops, with an
`IdleStateHandler` set on release and the settings `idleTimeoutSeconds` 30 and `maxSize` 10000,
was disabled and removed in `dea0ab4`. Why it was disabled is not recorded.

## Overview: the implementations at a glance

Sources and versions are in "Implementations investigated". "Moves" means whether an idle
connection is handed to a request on another thread.

| Implementation | Partition | Moves | Idle default |
| --- | --- | --- | --- |
| NGINX 1.31.10 | per worker process | no | 32 per worker, 60 s |
| Envoy 1.36.0 | per worker thread and cluster | no | 1 h |
| HAProxy 3.2.0 | per server and thread | takeover if own list empty | purge 5 s |
| Pingora 0.9.1 | global | yes, shared | 128 per worker |
| Armeria | per event loop | no, request hops to the pool | 10 s |
| Reactor Netty | global per remote address | yes, any loop leases | none |
| Vert.x 5 | per endpoint, slot bound to a loop | borrows, never moves | 60 s |
| Netty `SimpleChannelPool` 4.2 | global | yes, any caller | none |

The two that keep a connection on its worker thread without hops, NGINX and Envoy, partition the
pool per worker. Only HAProxy moves an established connection to another thread, as a fallback.

## Design decisions

### One pool partition per event loop and upstream

A partition is used only by its own event loop, so it needs no lock and no task hop, and
`Upstream#connect` keeps returning a channel on the client's loop. This is the model of NGINX and
Envoy. The partitions are created on first use, keyed by the event loop that releases a
connection, so the pool needs no event loop group.

Consequence: a loop may open a new connection while another loop has idle ones. With the round
robin of accepted client connections over the loops, the partitions see about the same load.

The handlers do not depend on it: `DownstreamHandler` hands the listener of a failed upstream
write over to the client's loop when it runs on another one, and `ForwardHandler` reaches the
client channel only through Netty calls that do the same. Asserts in `DownstreamHandler` catch
exchange state changed off the client's loop, and
[`UpstreamOnAnotherLoopTest`](../../src/test/java/org/sensepitch/edge/UpstreamOnAnotherLoopTest.java)
runs an exchange with the upstream on a second loop. So a shared pool, or borrowing from another
partition, stays possible without changes to the handlers.

### No connection moves between event loops

Netty 4.2.21 can do it: `AbstractChannel.AbstractUnsafe.register` accepts a channel after
`deregister()` and clears the cached executors of its handler contexts "from prior event loop
registrations"; `deregister` is deferred with `invokeLater` (netty#4435) so a handler still
running on the old loop finishes first. Read in `netty-transport-4.2.21.Final-sources.jar`. Not
built on, because:

- Netty 5 removed it on purpose: netty#8513 says re-registration "defeats the threading model
  assumptions ... requires careful user level coordination", PR netty#8587 binds the event loop
  in the `Channel` constructor. Building on it blocks a later upgrade.
- Timers scheduled on the old loop do not move, e.g. the idle timeout of the pooled connection;
  they have to be cancelled before and armed again after.
- A move costs a task on the owning loop (deregister) and one on the new loop (register), about
  the hops the binding to the client's loop saves for a small exchange.
- None of Armeria, Reactor Netty, Vert.x or Netty's own pool does it.

A partition that is empty opens a new connection on the client's loop. Borrowing from another
partition (Vert.x) or moving a connection (HAProxy takeover) only pays off once there is a limit of
connections per upstream, see "Planned improvements".

### Idle connections keep reading

A pooled idle connection stays registered for reads, as with NGINX (`recv(MSG_PEEK)` in its read
handler), HAProxy and Pingora. A close by the upstream removes it at once, its close listener in
the partition; data closes it, `ForwardHandler` without an exchange counts it as
`unexpected_data` and closes the channel. So a connection in a partition is always open. The
release switches reading on: backpressure of the last exchange may have paused it, see
`DownstreamHandler.channelWritabilityChanged`, and then neither a close nor the next response
would be read. The removed pool of 2025 did the same on acquire.

### Most recently used first

The partition hands out the connection released last (LIFO, like Armeria and Netty's
`SimpleChannelPool`), so the surplus after a load peak stays idle at the tail and ages out by the
idle timeout. Reactor Netty leases FIFO by default, which keeps all connections warm.

### Idle connections end by the idle timeout only, there is no idle limit

A release keeps every open connection; the number of idle connections is not limited. The first
version had a `maxIdle` of 32 per event loop, like NGINX `keepalive` and Pingora
`upstream_keepalive_pool_size`: a release beyond it closed the connection idle for the longest
time. The benchmarks of 2026-11-27 showed that this churns under bursts: more exchanges were in
flight on a loop than `maxIdle`, their connections were closed when they ended (2 418 to 9 784
`limit` closes per 10 s run), were needed again milliseconds later, and each close held a local
port in `TIME_WAIT`, see
[performance-test/runs/2026-11-27-upstream-pool](../../performance-test/runs/2026-11-27-upstream-pool/README.md).
An idle limit cannot protect against a burst either, the connections of a burst are in use, not
idle; that is the job of `maxConnections`, see below. Without the idle limit, the surplus of a
burst is closed by the idle timeout once the burst is over, like with Envoy and Armeria, which have
an idle timeout and no idle limit. HAProxy shrinks the idle connections gradually instead
(`pool-purge-delay`, half of them per interval); one timeout is simpler to explain and to tune.

### A hard limit of 10 000 connections per upstream, shared by the event loops

`maxConnections` limits all connections to an upstream, in use, idle and connecting, so the edge
does not run out of local ports (about 28 000 on Linux with the default range) and does not
overwhelm the upstream. Each event loop may hold `maxConnections` divided by the number of loops,
rounded down, at least 1, counted in its partition: the loops together come near the limit but
never exceed it, and no counter is shared by the threads. An exchange that needs a further
connection gets `503` with the outcome `upstream_connection_limit`, counted in
`sensepitch_upstream_connection_limit`; an idle connection is still taken at the limit.

The name is the one of Envoy (`max_connections`) and Reactor Netty (`maxConnections`); HAProxy has
`maxconn`, NGINX `max_conns` (from memory, not verified). Envoy enables it by default (1024) and
queues up to `max_pending_requests` before answering 503; Reactor Netty and Vert.x queue the
acquire; NGINX and HAProxy have it off by default, open source NGINX answers 502 at once. Here the
default is high, a protection rather than a tuning value, so reaching it is exceptional and there
is no queue; a queue is a planned improvement.

### Idle timeout of 4 seconds

An idle connection is closed after `idleTimeoutMillis`, a timer on the event loop of its partition,
cancelled when the connection is taken, tested on the mock ticker of an embedded channel. It has
to be shorter than the keep-alive timeout of the upstream, or the race of RFC 9112, section 9.5,
becomes the normal case. The default 4 s is below the 5 s keep-alive timeout of Node.js and Apache
httpd (from memory, not verified); 10 s (Armeria) and 60 s (NGINX, Vert.x) were the other
candidates.

### Only a clean connection goes back to the pool

The connection is released as reusable only if the request was forwarded completely (its
`LastHttpContent` was written upstream), the final response ended with its `LastHttpContent`, the
upstream allows keep-alive (`HttpUtil.isKeepAlive` of the final response, read before
`DownstreamHandler` removes the connection headers), no `101` was seen, and no error, timeout or
client abort ended the exchange. Any other end closes the connection. A response delimited by the
close leaves the connection inactive, the partition does not keep it.

### Hop-by-hop headers are removed from the request too

Before, only the response lost its hop-by-hop headers. With pooling a client's `Connection: close`
would end the pooled connection, and RFC 9110, section 7.6.1, requires a proxy to remove them
anyway. `DownstreamHandler.stripHopByHopHeaders` serves both directions.

### Retry of idempotent requests without a body on a stale connection

The upstream may close an idle connection just as the edge sends a request on it. If a connection
taken from the pool fails before any response arrived (write failure, close, or an exception such
as a reset), and the method is idempotent (GET, HEAD, OPTIONS, TRACE, PUT, DELETE, RFC 9110,
section 9.2.2) and the request has no body, the request is sent once more on a new connection,
counted in `sensepitch_upstream_retries`. A new connection is never retried, so there is at most
one retry. Like NGINX and Pingora. The body condition keeps the edge from buffering request
bodies, it streams them. Not chosen: no retry (Envoy, Armeria), which turns the race into `502`s
that did not exist without the pool; HAProxy's `http-reuse safe`, which keeps the first request
of each client connection off reused connections and so loses most of the reuse for short client
connections.

### Lifecycle on reload and shutdown

A reload that removes or changes an upstream calls `Upstream#close()`: the pool closes the idle
connections on the loop of each partition, a connection in use is closed when its exchange
releases it. An unchanged upstream is taken over with its pool, see `UpstreamRegistry`. Shutdown
closes the idle connections with the event loops.

### On by default, configured per upstream

`connectionPool` on each upstream (`disabled`, `idleTimeoutMillis`, `maxConnections`), enabled by
default like NGINX since 1.29.7: without the pool the edge reaches the limit of local ports at
about 470 requests per second per upstream address, see "Purpose and scope". `disabled: true`
restores the connection per request. An off switch rather than `enable`, so the key is absent
normally and a later configuration layer only switches the default off, as with
`requestLog.disabled`. Metrics, labelled with the upstream name: connections opened, connections
reused, open connections and idle connections (gauges), idle connections closed by reason
(`idle_timeout`, `closed_by_upstream`, `removed`), refusals at the connection limit, retries.

## Implementations investigated

All on 2026-11-27, from the documentation and the source code at the named tag or commit, not
measured. Points marked **unverified** were not found in the sources.

### NGINX 1.31.10 (`ngx_http_upstream_keepalive_module`)

- Cache per worker process: `keepalive` is the number of idle connections "preserved in the
  cache of each worker process". When it is exceeded, "the least recently used connections are
  closed". It does not limit the total connections.
  [docs](https://nginx.org/en/docs/http/ngx_http_upstream_module.html)
- Defaults: `keepalive 32`, `keepalive_requests 1000` (100 before 1.19.10), `keepalive_time 1h`,
  `keepalive_timeout 60s`. Caching is on by default since 1.29.7.
- An idle connection gets `ngx_http_upstream_keepalive_close_handler`, which closes on EOF, error
  or unexpected data (`recv(fd, buf, 1, MSG_PEEK)`) and on the idle timer.
  [source](https://github.com/nginx/nginx/blob/release-1.31.10/src/http/modules/ngx_http_upstream_keepalive_module.c)
- A failure on a cached connection is retried without counting the try (`u->peer.tries++`).
  POST, LOCK and PATCH are not retried after they were sent, unless `non_idempotent` is set; no
  retry if the body was not buffered or a response was sent to the client.
  [source](https://github.com/nginx/nginx/blob/release-1.31.10/src/http/ngx_http_upstream.c)

### HAProxy 3.2.0

- Idle connections per server and thread (`srv->per_thr[tid].idle_conns`, `safe_conns`).
  `conn_backend_get` in `src/backend.c` takes one of the own thread first and takes one over
  from another thread (`conn->mux->takeover()`, `fd_takeover()`, since 2.2) only when both own
  lists are empty and the idle connections exceed `pool-low-conn`.
- `http-reuse safe` is the default: "The first request of a session is always sent over its own
  connection", so the browser can retry it. `aggressive` and `always` reuse more.
- `pool-max-conn -1`, `pool-low-conn 0`, `pool-purge-delay 5s` (half of the idle connections
  closed per interval), `max-reuse -1`. Idle connections stay subscribed for reads.
- `retry-on conn-failure` by default. Whether a failure on a reused connection is retried without
  `retry-on`: unverified.
  [configuration](https://github.com/haproxy/haproxy/blob/v3.2.0/doc/configuration.txt)

### Envoy 1.36.0

- "Each worker thread maintains its own connection pools for each cluster", nothing is shared.
  [docs](https://www.envoyproxy.io/docs/envoy/latest/intro/arch_overview/upstream/connection_pooling)
- `idle_timeout` 1 h, `max_requests_per_connection` and `max_connection_duration` unlimited;
  circuit breaker `max_connections` 1024 per cluster; `per_upstream_preconnect_ratio` 1.
- No retry unless configured (`reset`, `reset-before-request`, `connect-failure`). An automatic
  retry for stale pooled connections: not found with `gh search issues` on `envoyproxy/envoy`.

### Pingora 0.9.1

- One global `ConnectionPool`: per key a lock-free hot queue of 16 plus a locked overflow map,
  and a global LRU. `upstream_keepalive_pool_size` 128 per worker, times the threads.
  [source](https://github.com/cloudflare/pingora/blob/0.9.1/pingora-pool/src/connection.rs)
- An idle connection has a task reading one byte with the idle timeout; EOF, data, error or
  timeout remove it.
- A read error on a reused connection is retryable (`RetryType::ReusedOnly`); the default policy
  retries only idempotent methods with a complete retry buffer.

### Armeria (main `431ffa1`)

- One `HttpChannelPool` per event loop. The loop is chosen per endpoint by the
  `EventLoopScheduler`, one loop per endpoint by default (`maxNumEventLoopsPerEndpoint` 1), not
  by the loop of the server request; a request from another loop hops there.
- Most recently released first, `isActive()` check on acquire and release, idle timeout 10 s, no
  automatic retry (`RetryingClient` with `RetryRule.onUnprocessed()`).
  [source](https://github.com/line/armeria/blob/431ffa1672e078e5bad31dc0aa97aae40eee1ad7/core/src/main/java/com/linecorp/armeria/client/HttpChannelPool.java)

### Reactor Netty (main `4e7c80e`)

- One pool per remote address for all loops. With colocation a new connection is opened on the
  caller's loop, but an idle one is leased to any caller. reactor-pool dropped its thread affinity
  pool (reactor-pool#54); colocation made HTTP/2 load uneven (reactor-netty#2604).
- FIFO, `maxConnections` max(CPUs, 8) x 2, no idle time by default; a leased inactive connection
  is replaced once; a connection reset before the headers were sent is retried once, for any
  method.
  [source](https://github.com/reactor/reactor-netty/blob/4e7c80e5b371483d1d3ae03c49fb576bc4ecde78/reactor-netty-core/src/main/java/reactor/netty/resources/PooledConnectionProvider.java)

### Vert.x 5 (main `435a2a4`)

- One pool per endpoint, each connection bound to the loop that opened it. An acquire takes a
  connection of the same loop, else opens one on the caller's loop, else borrows a free one of
  another loop. Connections never move.
- HTTP/1 max 5 per endpoint, keep-alive 60 s, a cleaner every second.
  [source](https://github.com/eclipse-vertx/vert.x/blob/435a2a4ac4bd6e074d5102e5e48a16e1e65bdffa/vertx-core/src/main/java/io/vertx/core/internal/pool/SimpleConnectionPool.java)

### Netty 4.2 `io.netty.channel.pool`

- `SimpleChannelPool`: one deque for all callers, LIFO, `ChannelHealthChecker.ACTIVE` on acquire
  and release, new channels from the bootstrap group round robin, no idle eviction.
  `FixedChannelPool` adds a limit with bookkeeping on one loop of the group.

## Alternatives considered

- A pool shared by all event loops (Reactor Netty, Pingora, `SimpleChannelPool`, the removed pool
  of `dea0ab4`): a connection leased by another loop brings back the hops between the client's
  and the upstream's loop on every exchange.
- Armeria's choice of the loop per endpoint: by default all requests to one endpoint run on one
  loop, the opposite of keeping the exchange on the client's loop.
- Moving connections between loops: see "No connection moves between event loops".

## Limitations

- The connections of a burst stay open until the idle timeout after it; the edge closes them, so
  they hold local ports in `TIME_WAIT` for 60 s afterwards. `maxConnections` bounds how many.
- At the connection limit an exchange is refused at once, there is no queue.
- The limit is split evenly over the event loops: a loop that has its share open refuses although
  another loop has room.

- HTTP/1.1 only, no HTTP/2 multiplexing to the upstream, no pipelining.
- No preconnect (Envoy `per_upstream_preconnect_ratio`).
- One server per upstream; multiple servers are a separate entry in `NOT-YET.md`.

## Planned improvements

Listed in [`NOT-YET.md`](../../NOT-YET.md) under "Upstream": a maximum of requests per connection
and a maximum connection age (NGINX `keepalive_requests` 1000 and `keepalive_time` 1 h; the age
lets a DNS change of the upstream take effect), a queue before refusing at the connection limit,
borrowing an idle connection of another event loop, preconnect, pool metrics on a dashboard.
