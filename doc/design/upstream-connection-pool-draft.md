# Upstream connection pool (draft)

**Draft, nothing decided.** This is the research for the `NOT-YET.md` entry "upstream connection
pool with keep-alive" under "Upstream", which asks for a draft design first. The options marked
`[ ]` are open choices for the maintainer. It covers the upstream connect in
[`DefaultUpstream`](../../src/main/java/org/sensepitch/edge/DefaultUpstream.java) behind the
[`Upstream`](../../src/main/java/org/sensepitch/edge/Upstream.java) interface, and the exchange in
[`DownstreamHandler`](../../src/main/java/org/sensepitch/edge/DownstreamHandler.java) and
[`ForwardHandler`](../../src/main/java/org/sensepitch/edge/ForwardHandler.java). Once the choices
are made, the document becomes `upstream-connection-pool.md` and records the decisions.

## Purpose and scope

Today every exchange opens a TCP connection to the upstream on the event loop of its client
channel, and `DownstreamHandler` closes it after the response. A pool saves the connect per
request: a round trip of latency, an ephemeral port in `TIME_WAIT` on our side, and the accept
work of the upstream.

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

## Design decisions (open)

None of these is decided.

### One pool partition per event loop and upstream

Proposed. A partition is used only by its own event loop, so it needs no lock and no task hop,
and `Upstream#connect` keeps returning a channel on the client's loop. This is the model of NGINX
and Envoy. `DefaultUpstream` creates the partitions when it is built, one per event loop of the
group. `ProxyContext` no longer offers the group, it was dropped when nothing used it, so the pool
passes it in again; there is still no static or thread local state.

Consequence: up to (event loops) x (max idle) idle connections per upstream, and a loop may open
a new connection while another loop has idle ones. With the round robin of accepted client
connections over the loops, the partitions see about the same load.

### No connection moves between event loops

Proposed. Netty 4.2.21 can do it: `AbstractChannel.AbstractUnsafe.register` accepts a channel
after `deregister()` and clears the cached executors of its handler contexts "from prior event
loop registrations"; `deregister` is deferred with `invokeLater` (netty#4435) so a handler still
running on the old loop finishes first. Read in `netty-transport-4.2.21.Final-sources.jar`. Not
built on, because:

- Netty 5 removed it on purpose: netty#8513 says re-registration "defeats the threading model
  assumptions ... requires careful user level coordination", PR netty#8587 binds the event loop
  in the `Channel` constructor. Building on it blocks a later upgrade.
- Timers scheduled on the old loop do not move, e.g. an idle timeout of the pooled connection;
  they have to be cancelled before and armed again after.
- A move costs a task on the owning loop (deregister) and one on the new loop (register), about
  the hops the binding to the client's loop saves for a small exchange.
- None of Armeria, Reactor Netty, Vert.x or Netty's own pool does it.

For a partition that is empty while others have idle connections, which matters only once there
is a limit of connections per upstream:

- [ ] Connect a new connection on the client's loop. The default without a limit.
- [ ] Borrow an idle connection of another loop and accept the hops for that exchange, like
  Vert.x.
- [ ] Move it with deregister and register, like the HAProxy takeover. Netty 4 only.

### Idle connections keep reading

Proposed. A pooled idle connection stays registered for reads. A close, an error or any data on
it removes it from the partition and closes it, as NGINX (`recv(MSG_PEEK)` in its read handler),
HAProxy and Pingora do. `ForwardHandler` without an exchange already counts unexpected data as
`unexpected_data`; in the pool it also evicts the connection. An acquire checks `isActive()`.

### Most recently used first

Proposed. The partition hands out the connection released last (LIFO, like Armeria and Netty's
`SimpleChannelPool`), so the surplus after a load peak stays idle and ages out by the idle
timeout. Reactor Netty leases FIFO by default, which keeps all connections warm.

### Idle timeout

An idle connection is closed after a timeout, scheduled on the event loop of its partition and
tested on the simulated clock. It has to be shorter than the keep-alive timeout of the upstream,
or the race of RFC 9112 section 9.5 becomes the normal case.

- [ ] 4 s, below the 5 s keep-alive timeout of Node.js and Apache httpd. Unverified, from memory.
- [ ] 10 s, like Armeria.
- [ ] 60 s, like NGINX `keepalive_timeout` and Vert.x `keepAliveTimeout`.

### Only a clean connection goes back to the pool

Proposed. The connection is released to its partition only if the request was sent completely,
the final response ended with its `LastHttpContent`, the response was not delimited by the close,
the upstream sent no `Connection: close` (read before `DownstreamHandler` removes the connection
headers from the response), the status is not `101`, and no error, timeout or client abort ended
the exchange. Any other end closes the connection, as today.

### Retry on a stale connection

The upstream closes a reused connection while the request is on its way.

- [ ] No retry: the client gets a `502` and the event is counted, like Envoy and Armeria by
  default.
- [ ] Retry once on a new connection if the connection was reused, nothing of the response
  arrived, the method is idempotent and the request has no body, like NGINX and Pingora. The
  body condition keeps the edge from buffering request bodies, it streams them today.
- [ ] Never put the first request of a client connection on a reused connection, like HAProxy
  `http-reuse safe`, so the client itself can retry it.

### Limits

- [ ] Max idle connections per partition: 32, like NGINX `keepalive`. When it is reached the
  oldest idle connection is closed.
- [ ] Max requests per connection and max connection age: 1000 and 1 h, like NGINX
  `keepalive_requests` and `keepalive_time`. The age lets a DNS change of the upstream take
  effect.
- No limit of the total connections per upstream in the first step, as NGINX `keepalive`.

### Lifecycle on reload and shutdown

Proposed. A reload builds new upstreams. The partitions of the old one close their idle
connections, a connection in use closes after its exchange instead of going back. Shutdown closes
all idle connections at once.

### Configuration and metrics

- [ ] A `pool` record on `UpstreamConfig`, on by default (NGINX caches by default since 1.29.7).
- [ ] The same, off by default.

Counters labelled with the upstream name: connections created, connections reused, connections
closed by reason (`idle_timeout`, `closed_while_idle`, `not_reusable`, `limit`), retries on a
stale connection; a gauge of the idle connections.

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

- HTTP/1.1 only, no HTTP/2 multiplexing to the upstream, no pipelining.
- No preconnect (Envoy `per_upstream_preconnect_ratio`).
- One server per upstream; multiple servers are a separate entry in `NOT-YET.md`.

## Planned improvements

The entry "upstream connection pool with keep-alive" in [`NOT-YET.md`](../../NOT-YET.md).
