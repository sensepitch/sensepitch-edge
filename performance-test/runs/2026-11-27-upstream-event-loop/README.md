# Upstream on the client's event loop, 2026-11-27

Compares the latency of the edge with the upstream connection on any event loop of the group
(`separate-loops`, commit `4cc68cf`, `DefaultUpstream` bootstraps with
`.group(ctx.eventLoopGroup())`) and on the event loop of its client channel (`same-loop`, commit
`6b4a5c7`, `bootstrap.clone(ingress.eventLoop())`). NGINX as TLS terminating proxy in front of
the same upstream is the reference. Setup as [`start.sh`](../../start.sh): the upstream is the
`nginx-static` container of [`docker-compose.yml`](../../docker-compose.yml), the request is
`GET /10kb.img`, with deflector protection on and the URI bypassed.

Machine: AMD Ryzen AI 7 PRO 360 (8 cores, 16 threads), 61 GiB, Linux 7.0, Temurin 25.0.5 with
`-XX:+UseZGC`, NGINX `1.29.0-alpine`, vegeta on the same machine. Other containers (a shop with
its database, grist) were running and idle, so the numbers carry some noise.

## Result

At 300 requests per second (`results-300rps`), 20 s per run after a discarded warm-up of 5 s,
three rounds with alternating order:

| Variant | mean | p50 | p90 | p99 | success |
| --- | --- | --- | --- | --- | --- |
| NGINX | 0.27 ms | 0.23 ms | 0.40 ms | 0.76 ms | 100% |
| separate-loops | 0.56-0.61 ms | 0.51-0.58 ms | 0.79-0.88 ms | 1.12-1.24 ms | 100% |
| same-loop | 0.47-0.49 ms | 0.41-0.44 ms | 0.72-0.75 ms | 1.15-1.22 ms | 100% |

The same loop saves about 0.1 ms per request, 18 % of the mean and 21 % of the median; the
ranges of the rounds do not overlap. The p99 is the same.

## The saturating runs fail without an upstream pool

`results-saturating` repeats the rates of the main README, 50000 and 10000 requests per second.
Both variants answer only 3 to 58 % of the requests; the rest are `502 Upstream connection
problem` and client side `bind: address already in use`. The edge opens an upstream connection
per request and closes it first, so each one holds a local port in `TIME_WAIT` for 60 s. With
`ip_local_port_range` `32768 60999` (28232 ports) that allows about 470 new connections per second
to one upstream address. `tcp_tw_reuse` is `2`, reuse for loopback only, and the upstream is on
the Docker bridge. These runs therefore compare nothing; they are kept as the evidence of the
limit. The 300 requests per second stay below it, with 65 s pauses between the runs so the
`TIME_WAIT` of the previous run is gone.

## Reproduce

Build both fat JARs and put them next to the scripts, e.g. with a worktree for the old commit:

```sh
git worktree add --detach /tmp/separate-loops 4cc68cf
(cd /tmp/separate-loops && ./mvnw package -DskipTests)
cp /tmp/separate-loops/target/sensepitch-edge-*-with-dependencies.jar separate-loops.jar
git worktree remove /tmp/separate-loops
(cd ../../.. && git checkout 6b4a5c7 && ./mvnw package -DskipTests)
cp ../../../target/sensepitch-edge-*-with-dependencies.jar same-loop.jar
```

Run `../../start.sh` once before, it creates the certificate and the files served by the upstream.
Then `./run-300rps.sh` (about 17 minutes) or `./run-saturating.sh`. The scripts start and stop
the containers, write a vegeta report per run and the proxy output as `*.log` (not kept in git,
mostly request log).
