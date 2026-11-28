# Benchmark: the edge, NGINX and HAProxy

Measures the edge against NGINX and HAProxy as TLS reverse proxies in front of the same upstream,
`nginx-static` from [`../docker-compose.yml`](../docker-compose.yml), serving static files. It
replaces `compare.sh` of the
[2026-11-27 run](../runs/2026-11-27-upstream-pool/README.md#next-comparison), whose findings
set the rules here: `shb` instead of vegeta (vegeta was the bottleneck on one machine), a warm-up
before each measurement (a cold JVM dominated the tail), every proxy with its log on and off
(NGINX logged, the edge did not), and a fresh proxy per step.

## Run

```sh
./mvnw package -DskipTests                               # the edge JAR in target/
BENCH_MODE=quick performance-test/benchmark/bench.sh     # smoke, quick (default) or diligent
```

Needs `knockout.sh` (with `--junit-report`, 2026-11-28 or newer), `shb`, `docker`, `curl`,
`python3`, `java`. The results go to `performance-test/runs/<date>-<time>-benchmark-<mode>/`.

| `BENCH_MODE` | Matrix | Repeats | Warm-up edge / NGINX, HAProxy | Measured | Steps, time |
|---|---|---|---|---|---|
| `smoke` | each proxy, 10 KB, log on, 10 connections | 1 | 1 s / 1 s | 1 s | 3, about 15 s |
| `quick` | full | 1 | 5 s / 1 s | 5 s | 54, about 16 min |
| `diligent` | full | 3 | 10 s / 10 s | 10 s | 162, about 75 min |

The full matrix: the three proxies, the response sizes 0 B, 512 B and 10 KB, the log on and off,
10, 100 and 1 000 connections. `smoke` only checks that every part works, its numbers mean
nothing, the JVM is cold. `quick` gives NGINX and HAProxy a shorter warm-up than the edge: they
are native code without a JIT. `diligent` repeats every variant three times, the summary shows
the median and the range.

## How it works

`knockout.sh` runs [`knockout-tests/`](knockout-tests/):

- [`10-steps.suite.sh`](knockout-tests/10-steps.suite.sh) lists one test per step of the mode
  and runs each: wait until fewer than 200 sockets are in `TIME_WAIT`, start a fresh proxy, warm
  up with `shb`, measure with `shb -j` (closed loop: each connection sends as soon as the answer
  came) while sampling the machine and the proxy's processes once per second, scrape the edge's
  metrics before and after, stop the proxy. A step keeps its raw output only. Knockout shows the
  progress and the result per step: `[S 12/54] …::edge (512b off 100 1)  13.2s  -- 48000 req/s, p99 3.10 ms, 9.8 cores`.
- [`90-summary.sh`](knockout-tests/90-summary.sh) checks that every step has its result and
  writes the summary from the raw output.

The steps run one after the other; never use knockout's parallel jobs here, the steps would
measure each other. The proxies run with their defaults for the number of threads or workers
(one per core). NGINX and HAProxy run in containers and are reached over the Docker bridge, the
edge runs on the host and is reached over loopback; both are local, the difference is not
measured.

The edge runs with `-XX:+UseZGC`, the protection on, the three files bypassed, and an upstream
connection pool, see the [README](../../README.md#upstream-connections). NGINX keeps up to 1 000
idle upstream connections ([`nginx-proxy`](../nginx-proxy/default.conf.template)), HAProxy uses
its default connection reuse ([`haproxy.cfg`](../haproxy/haproxy.cfg)).

## What the steps measure

`shb` runs a closed loop: each connection sends its next request only after the answer to the
previous one. So the load follows the speed of the proxy, and the requests per second are the
result. The number of connections sets the load, and with it what a step shows. The examples are
from the quick run of 2026-11-28 (single values, 16 cores, the load generator on the same
machine):

| Connections | Machine busy | Requests per second show | Example |
|---|---|---|---|
| 10 | 58–77 %, not saturated | the time per request: req/s ≈ connections ÷ time per request (Little's law) | NGINX 0 B, p50 0.11 ms, 77k req/s; the edge 0.13 ms, 67k |
| 100 | 88–95 %, saturated | the throughput limit, how many requests the CPU manages | the edge at 53–67 % of NGINX |
| 1 000 | 95–99 % | many connections at once: scheduling, fairness between connections, memory per connection, and the tail latency under queueing | p99.9 of the edge 120–213 ms, of NGINX 40–190 ms |

At saturation the proxy shares the CPU with `shb`, so the requests per second partly measure that
competition; the cores per 10 000 requests per second are the fairer figure there.

Not measured: an open loop, which sends requests at a fixed rate whether or not the answers have
arrived, as thousands of independent clients do. There the load is fixed and the latency is the
result, e.g. the p99 of each proxy at 20 000 requests per second, a load all of them handle. The
difference shows in the tail: while a proxy stalls, e.g. in a garbage collection pause, a closed
loop sends nothing, so the requests that would have waited are missing from the latencies
("coordinated omission"), and the tail looks better than clients would see it. The latencies here
are those of a closed loop.

Why there is no open loop yet: `shb` has no rate option, its options are closed loop only
(checked 2026-11-28). vegeta is open loop and was reliable below about 28 000 requests per second
in the 2026-11-27 runs, but it does not speak HTTP/3 and QUIC, which this benchmark is to measure
with `shb`; a second load generator for one mode would not last. An open-loop mode in `shb` is a
planned improvement.

## Output

| File | Content |
|---|---|
| `summary.md` | the table per variant and the ratios of the edge to NGINX and HAProxy |
| `raw/<step>.*` | the raw output: `shb.json`, `samples.jsonl`, `edge-before.om`, `edge-after.om`, the warm-up report, and the first and last 50 lines of the proxy's log (the whole request logs of a quick run took 5.9 GB) |
| `host.json`, `junit.xml` | the machine and the versions; knockout's report |

For a database, e.g. before an upload, convert a run into one OpenMetrics file:

```sh
performance-test/benchmark/export-openmetrics.sh performance-test/runs/<run>   # writes <run>/run.om
```

The conversion is not part of the run: converting and writing every step while measuring cost
time and disk (3.6 MB of step files and 3.2 MB merged for a quick run), and the raw output holds
everything it needs; the quick run of 2026-11-28 converts in 0.2 s.
`run.om` is OpenMetrics text with a timestamp on every sample, so it can be stored and plotted
later: `promtool tsdb create-blocks-from openmetrics run.om <prometheus data dir>` backfills it
into Prometheus (checked with Prometheus 3.5.0), VictoriaMetrics imports it with
`curl --data-binary @run.om http://<host>:8428/api/v1/import/prometheus`. Every sample carries
the labels of its step: `bench_run`, `bench_mode`, `bench_proxy`, `bench_size`, `bench_log`,
`bench_connections`, `bench_repeat`, `bench_commit`, `bench_edge_version`.

| Metric | Meaning |
|---|---|
| `bench_requests_per_second` | answered requests per second, by `shb` |
| `bench_requests_total`, `bench_errors_total`, `bench_responses_total{code}` | requests sent; without a 2xx answer (errors and other status codes); answers by status |
| `bench_latency_seconds{quantile}` | latency quantiles 0.5 to 0.9999, a summary with `_count` and `_sum` |
| `bench_duration_seconds` | the measured time |
| `bench_machine_busy_ratio`, `bench_proxy_cpu_cores`, `bench_proxy_cpu_cores_per_10k_rps` | CPU of the machine and of the proxy's processes, the middle three fifths of the measurement |
| `bench_machine_cpu_busy_ratio`, `bench_proxy_cpu_cores_sample`, `bench_machine_memory_available_bytes`, `bench_machine_load1` | the same per second, for the load curve of a step |
| `bench_host_info{cpu_model,cpu_cores,kernel,os,java_version,nginx_version,haproxy_version,shb_version}` | the machine and the versions |
| `bench_machine_cpu_cores`, `bench_machine_memory_bytes`, `bench_machine_cpu_max_frequency_hertz` | the machine |
| `bench_machine_time_wait_sockets` | sockets in `TIME_WAIT` when the step started |
| `sensepitch_*`, `jvm_*`, `process_*` | the edge's own metrics, scraped before and after the measurement |

The conversion, the check and the summary are one script without dependencies,
[`benchtool.py`](benchtool.py). The machine is sampled from `/proc` by the same script, not with
`mpstat` and `pidstat`, so every sample has its exact time.

## Limitations

- The load generator shares the machine with the proxy, as in the earlier runs. The CPU per
  10 000 requests per second is the fairest figure while that is so.
- Closed loop only, see [What the steps measure](#what-the-steps-measure): the latencies leave
  out the requests a stalled proxy would have delayed.
- HTTP/1.1 only.
- `shb` has no version option; the summary names its binary by a hash.
- The upstream is NGINX serving files; its own limits are part of every result.

## Planned improvements

- An open-loop mode in `shb`: requests at a fixed arrival rate, the latency counted from the
  scheduled send time, so a stall delays the requests that would have been sent during it. Then
  fixed-rate steps at a load all proxies handle, next to the closed-loop steps.
- HTTP/2 and HTTP/3 (QUIC), which `shb` speaks, for the proxies that support them.
