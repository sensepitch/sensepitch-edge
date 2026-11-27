# Upstream connection pool, 2026-11-27

The edge with the upstream connection pool, default configuration (`maxIdle` 32 per event loop,
`idleTimeoutMillis` 4000), against the setup of [`start.sh`](../../start.sh): `GET /10kb.img`
through TLS to the `nginx-static` container, vegeta on the same host. Machine as in
[2026-11-27-upstream-event-loop](../2026-11-27-upstream-event-loop/README.md): Ryzen AI 7 PRO 360
(16 threads), Temurin 25.0.5, ZGC; other containers running idle. The JAR is the working tree on
top of commit `deed951` with the pool: the first table and the saturating runs with its first
version (`maxIdle` 32, before the fix that resumes reading on release), "Without the idle limit"
with the version that replaced `maxIdle` by `maxConnections`.

## The port limit is gone

Without the pool the edge failed above about 470 requests per second per upstream address,
local ports in `TIME_WAIT`, see the event loop run. With the pool, single load steps with
`probe.sh` (10 s each, `TIME_WAIT` drained before each step):

| Rate | maxIdle | Success | p50 | p90 | p99 | Opened | Reused | Closed `limit` | Closed `idle_timeout` |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2 000 | 32 | 100 % | 0.25 ms | 0.72 ms | 226 ms | 352 | 19 648 | 0 | 330 |
| 2 000 | 32 | 100 % | | | | 262 | 19 738 | 0 | 240 |
| 5 000 | 32 | 100 % | 267 ms | 3.11 s | 4.22 s | 7 530 | 42 470 | 6 506 | 989 |
| 5 000 | 32 | 100 % | 0.40 ms | 1.32 s | 2.08 s | | | | |
| 10 000 | 32 | 87 % | | | | 11 015 | 17 268 | 9 784 | 207 |
| 10 000 | 32 | 100 % | 8.0 ms | 1.89 s | 3.55 s | 8 482 | 91 518 | 7 458 | 663 |
| 10 000 | 1000 | 100 % | 15.8 ms | 2.80 s | 5.06 s | 9 106 | 90 894 | 0 | 7 668 |

At 2 000 requests per second 98 % of the requests use a kept-alive connection. From 5 000 on the
tail latency is in seconds and the results vary between runs of the same step. The request log of
the second 5 000 step splits it: `receive_ns` p90 706 ms, `response_ns` p90 591 ms, and 8 746 of
the 50 000 requests came on a new client connection, each with a TLS handshake. Vegeta keeps its
connections alive; it opens another one only when it needs more requests in flight at once,
that is when responses come slower than the rate. So the new client connections are a
consequence of the slowdown, and their handshakes add load on top. Once the edge falls behind,
more exchanges are in flight on an event loop than its partition keeps (`limit` closes), and the
closed upstream connections occupy local ports again. What starts the slowdown is not
established.

## Without the idle limit

The first version closed a released connection when its event loop already kept `maxIdle` idle
ones (the `limit` column above). It was removed: idle connections end by the idle timeout only,
and `maxConnections` (10 000) limits all connections. The same steps with that version
(`results-probe/5000-pool.txt`, `results-probe/10000-pool.txt`):

| Rate | Success | p50 | p90 | p99 | Opened | Reused | Closed `idle_timeout` |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 5 000 | 100 % | 0.41 ms | 0.99 s | 1.66 s | 4 446 | 45 554 | 4 399 |
| 10 000 | 100 % | 6.8 ms | 1.01 s | 2.41 s | 6 657 | 93 343 | 6 575 |

Fewer connections are opened than with `maxIdle` 32 (5 295 to 7 530 at 5 000, 8 482 to 11 015 at
10 000), and none is closed at release. The ports in `TIME_WAIT` right after the step, by the side
that closed first (`probe.sh` prints this):

| Rate | vegeta closed a connection to the edge | edge closed an upstream connection |
| --- | --- | --- |
| 5 000 | 8 355 | 4 400 |
| 10 000 | 10 180 | 6 558 |

The edge never closed a client connection. Its part is the idle timeout closes after the burst;
the larger part is vegeta's, which only counts because it runs on the same host. Only 5 634 of
10 223 client connections of an earlier 5 000 step carried a request, so vegeta opens connections
it does not use; why is not verified. The tail latency from 5 000 on is unchanged.

## Without the request log

The request log goes to standard out, which the README names as a known bottleneck (lock
contention). The same steps with `SENSEPITCH_EDGE_REQUEST_LOG_DISABLED=true`
(`results-probe/<rate>-pool-nolog.txt`):

| Rate | Request log | Success | p50 | p90 | p99 | Opened | `TIME_WAIT` vegeta / edge |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 5 000 | on | 100 % | 0.41 ms | 0.99 s | 1.66 s | 4 446 | 8 355 / 4 400 |
| 5 000 | off | 100 % | 0.30 ms | 2.0 ms | 318 ms | 861 | 1 356 / 812 |
| 10 000 | on | 100 % | 6.8 ms | 1.01 s | 2.41 s | 6 657 | 10 180 / 6 558 |
| 10 000 | off | 100 % | 2.1 ms | 1.40 s | 2.92 s | 7 714 | 11 322 / 7 647 |
| 10 000 | off, again | 100 % | 0.50 ms | 428 ms | 2.20 s | 5 031 | 11 057 / 4 930 |

At 5 000 the request log causes the tail: without it the p90 is 2 ms instead of about a second,
and with less time in flight, fewer connections are needed on both sides. At 10 000 the tail
stays, a second limit of this host or of the edge, not investigated; two runs differ by a factor
of three in the p90 (`10000-pool-nolog-run1.txt` and `10000-pool-nolog.txt`).

## The saturating runs say little on this host

`run-saturating.sh` (50 000 and 10 000 requests per second, as in the main README), results in
`results-saturating`: 12 to 60 % success at 50 000, 10 to 99.7 % at 10 000, NGINX 100 % in both.
Most failures are vegeta's own `bind: address already in use`: the load generator and the edge
share the ephemeral ports of the host, while the NGINX proxy runs in its own network namespace
with its own port range. A fair comparison needs the load generator and the edge in separate
network namespaces, or on separate hosts.

## Reproduce

All commands from the folder of this README, `performance-test/runs/2026-11-27-upstream-pool`.
Needed: Docker with `docker compose`, `vegeta`, `curl`, Java 25.

### Step 1: build the edge

The JAR goes into this folder as `pooled.jar`, it is not kept in git.

```sh
(cd ../../.. && ./mvnw package -DskipTests)
cp ../../../target/sensepitch-edge-*-with-dependencies.jar pooled.jar
```

### Step 2: check the certificate and the files of the upstream

```sh
ls ../../ssl/nginx.crt ../../html/10kb.img
```

If they are missing, run `../../start.sh` once. It creates them, then runs a benchmark of its own
and leaves the edge running; stop it as its last line says (`kill <pid>; docker compose down` in
`performance-test`).

### Step 3: wait until no ports are left in `TIME_WAIT`

Before every load step and every saturating run. The count should be near 0; it drops within
60 s after the last run.

```sh
ss -tan state time-wait | wc -l
```

### Step 4: one load step

Starts the containers and the edge, sends `GET /10kb.img` for 10 s at the given rate, prints the
vegeta report, the ports in `TIME_WAIT` by the side that closed first, and the pool metrics of the
edge, and stops everything.

```sh
./probe.sh 5000
```

Without the request log:

```sh
SENSEPITCH_EDGE_REQUEST_LOG_DISABLED=true ./probe.sh 5000
```

The output is kept in `results-probe/<rate>-pool.txt`, the edge output with the request log in the
`.log` next to it (not kept in git). The first table above was taken with an earlier version of
the edge and of this script, with a `maxIdle` parameter, which printed the values without keeping
them.

### Step 5: the saturating runs

NGINX once and the edge three times, at 50 000 and 10 000 requests per second, about 4 minutes.

```sh
./run-saturating.sh
```

The results are in `results-saturating/`: a vegeta report per run, `nginx-50k.txt`,
`pooled-<round>-50k.txt` and so on. The ones there were taken with the first version, `maxIdle`
32.

### Step 6: split the latency of a load step

The `.log` file of step 4 holds the request log. Each JSON line has
`sensepitch.duration.receive_ns` (until the request is received),
`sensepitch.duration.response_ns` (until the response starts) and
`sensepitch.connection.request_count` (`0` for the first request on a client connection).

```sh
grep -c '"sensepitch.connection.request_count":0,' results-probe/5000-pool.log
```
