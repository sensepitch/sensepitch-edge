# Circuit breaker

Design notes for [`CircuitBreaker`](../../src/main/java/org/sensepitch/edge/CircuitBreaker.java),
[`Backoff`](../../src/main/java/org/sensepitch/edge/Backoff.java) and
[`CircuitBreakerMetrics`](../../src/main/java/org/sensepitch/edge/CircuitBreakerMetrics.java).
The class docs describe the behaviour; this document records why it is built this way and what
was compared.

## Purpose and scope

The breaker protects a remote that fails: while it is down, requests are refused locally instead
of piling up, and the remote is probed with a growing delay until it answers again.

The first user is the request log export, see
[`HttpBatchSender`](../../src/main/java/org/sensepitch/edge/HttpBatchSender.java): an OTLP log
server that is down must not be hammered by a fleet of proxies, and the proxy must keep the
records it could not send yet. The breaker is meant to guard upstream connections later, see
[Planned improvements](#planned-improvements).

## Implementations investigated

### Armeria

Armeria 1.32.0 (`com.linecorp.armeria.client.circuitbreaker`), read from the API of the jar, as a
mature breaker in a Netty-based client:

- States `CLOSED`, `OPEN`, `HALF_OPEN` and `FORCED_OPEN`, with `enterState` for manual control.
- Opens when the failure rate reaches 50 % within a 20 s sliding window, counted only once at
  least 10 requests were seen. The counters are updated every second.
- Stays open for a constant `circuitOpenWindow` of 10 s, then lets one trial request through
  every `trialRequestInterval` of 3 s.
- A `CircuitBreakerRule` classifies a response as success, failure, ignore or "next rule".
- Lock free: an `AtomicReference` holds the state, updated by compare and set. Time comes from an
  injected `Ticker`.
- A `CircuitBreakerListener` receives `onStateChanged`, `onEventCountUpdated` and
  `onRequestRejected`. The Micrometer binding exports, under the tag `name`: a single `state`
  gauge (1 closed, 0.5 half open, 0 open), the sliding window counts as `requests{result}`
  gauges, a `transitions{state}` counter and a `rejected.requests` counter.

### resilience4j

The de facto standard breaker on the JVM and the reference for the metric names:

- Opens on a failure rate (and optionally a slow call rate) over a count based or time based
  sliding window, with a minimum number of calls.
- A fixed wait in the open state, then a configurable number of permitted calls in the half open
  state.
- Outcomes are successful, failed, ignored (an exception configured to be ignored) and not
  permitted.
- The Micrometer binding exports `state` as one gauge per state (1 for the current one), the
  successful, failed and ignored calls as a timer with the tag `kind`, the not permitted calls as a
  counter, and the failure rate and buffered calls as gauges.

### What both have in common, and where this breaker differs

Both are general purpose: a rate over a window decides, and the open state lasts a fixed time.
Neither honours a `Retry-After` from the server or grows the wait while a remote stays down. Both
assume many independent calls, while the request log sender has a queue that drains in order and
retries the same batch.

## Design decisions

### Opens after a threshold of failures in a row

Unlike Armeria and resilience4j, there is no failure rate: the breaker opens after
`Policy.failureThreshold` failures in a row that a retry may fix; a success or an ignored failure
resets the count. The request log uses a threshold of one (`Policy.OPEN_ON_FIRST_FAILURE`). After
a failure the sender drops its concurrency to one request and puts the batch back to the head of
the queue, so there is no stream of independent calls a rate could be computed over, and every
further request during the outage would only add load to a log server that is in trouble. An
upstream fails one request at a time under load and will use a higher threshold, so it opens only
when the upstream is down, see [Planned improvements](#planned-improvements).

The backoff starts at the trip: it grows with the failures since the breaker opened (the trip
and the failed probes), not with the failures before the threshold was reached. With a threshold
of one both counts are the same, so the request log keeps its delays.

### Who probes: the next request, or the caller

By default the first request after the backoff is the probe (`tryAcquire()` grants it). That fits
the request log: its next batch is exactly what has to get through. For an upstream with a health
endpoint it does not: the probe would be a client request that waits up to the connect or
response timeout, while every other request gets `503` until it answers. With
`Policy.callerProbes`, `tryAcquire()` grants requests only while closed, and the caller sends a
probe of its own after `tryProbe()` granted it, scheduled after `retryDelayNanos()`. A probe is
no client call, so it is not counted as one; its result is reported like any other. Both modes
share the backoff, the single probe and the probe timeout.

### Starts open when the caller probes

With `Policy.startOpen` the breaker starts open with the backoff already over, so the caller
probes the remote before the first request is let through, e.g. an upstream at startup. It was
never up, so the start is no trip: no transition is reported and the trip time stays `0`; the
first successful probe reports `open → closed: recovered` as after an outage. `startOpen` requires
`callerProbes`, otherwise the first client request would be the startup probe.

### Exponential backoff with jitter, `Retry-After` wins

The open state lasts a backoff, not a constant window:

- It starts at 1 s and doubles with each failed probe, up to 30 s by default. Per-destination
  `OtlpConfig.initialRetryDelayMillis` and `maxRetryDelayMillis` set these nominal bounds in
  milliseconds; omitted values retain the defaults. Zero or negative values and a maximum below
  the initial delay are rejected during destination validation.
- Of the nominal delay, a random half is taken: half the delay plus a random part of the other
  half ("equal jitter"). A fleet of proxies that lost the same log server does not retry in
  lockstep, and the delay never drops near zero as with full jitter.
- A `Retry-After` in seconds or as an HTTP date that is longer than the backoff wins, a shorter
  one does not shorten it. It is capped at 300 s, so a misconfigured server cannot stop the export
  for hours.
- The OTLP specification considers a connection error, a timeout and the status codes 429, 502,
  503 and 504 retryable. The caller classifies, see below.

A constant 10 s window as in Armeria would either retry a log server that is down for an hour 360
times per proxy, or wait too long after a short hiccup.

### Configure retry bounds per destination

Different collectors can need different retry frequencies. The bounds belong to
[`OtlpConfig`](../../src/main/java/org/sensepitch/edge/OtlpConfig.java), whose builder presets
the defaults, so the built values go into the existing `Backoff`; jitter and the precedence of a
longer `Retry-After` stay unchanged. Either bound can be overridden independently, provided the
built pair is valid. Zero was first kept as the default sentinel; it is rejected since the config
records are complete (2026-11-20), an omitted key keeps the default.
The nominal maximum is not a cap on `Retry-After`, which retains its separate 300 s cap.

A reload uses the existing destination replacement mechanism: changing either option creates
a new sender and breaker for new records. The replaced sender drains buffered records with its
original delays and age limit. Updating a live breaker in place would mix old queued work with
new settings and require a separate reconfiguration protocol. Upstream retry settings remain
future work.

### Reuse Netty's HTTP-date parser and convert once at receipt

`Backoff` uses `DateFormatter.parseHttpDate` from the existing Netty 4.2.20.Final dependency
instead of maintaining another date parser. The installed library bytecode was inspected with
`javap -c -p` on 2026-11-17: it returns null for unparseable dates and throws for inputs longer
than 64 characters. We reject those oversized inputs before calling it. Tests exercise the
standard date form and the older RFC 850 and asctime forms, alongside numeric regression cases.

Dates are compared with the current system wall clock when the response is handled. The parser
also accepts an explicit epoch-millisecond time, so tests need no sleeps or real clock deadlines.
The result is a duration; the breaker continues to measure that duration with its monotonic
`Ticker`. Later wall clock adjustments do not change an already scheduled delay. An incorrect
local clock can affect a date delay; the exponential backoff and 300 s cap still bound it.
Numeric seconds remain independent of the clock.

### One probe at a time, replaced after a timeout

When the backoff is over, `tryAcquire()` grants exactly one request, the probe, and moves to
`HALF_OPEN`. Its success closes the breaker, as does an ignored failure (see below), its failure
opens it again with the next, doubled backoff. If the probe reports nothing within the probe timeout, the next caller gets a new probe,
so a lost completion cannot keep the breaker half open forever. The sender sets the probe timeout
to its request timeout plus 1 s, so a probe is only replaced when it must have timed out.

A single probe matches Armeria's one trial request; resilience4j permits several calls in the half
open state, which needs a rate to evaluate them.

### Failures of requests in flight count once

Requests that were already in flight when the breaker opened fail as well. In the `OPEN` state,
such a failure is reported (as a call and an error) but does not open the breaker again or grow
the backoff. Only a longer `Retry-After` it carries extends the backoff. `onFailure` returns the
state before, so the caller knows whether it has to schedule a retry: only the failure that opened
the breaker does.

A result of an old request that arrives in `HALF_OPEN` is taken as the probe's result: a success
closes the breaker, a failure opens it again. Armeria behaves the same. For the sender a late
success is real evidence that the server is back, and a late failure only costs one more backoff.

### The caller classifies the outcome

The breaker has no rule engine. The caller reports `onSuccess()`, `onFailure(retryAfter, error)`
for a failure a retry may fix, or `onIgnored(error)` for a failure it cannot fix, e.g. a 400: that
batch is dropped and the breaker does not open. An open or half open breaker closes, as on a
success: the server answered, so it is up. Only releasing the probe would cost one more round
trip, which then most likely succeeds, before the sender returns to its slow start. Up to
2026-11-15 an ignored probe changed no state and held the probe slot until the probe timeout,
11 s with the default request timeout. Otherwise this is resilience4j's "ignored" and Armeria's
`ignore()` decision, without the configuration surface; the one rule set the sender needs is in
`HttpBatchSender.result` and `HttpBatchSender.failure`.

The `error` is a short code, `http_<status>`, `connect`, `timeout`, `io` or `unexpected`, never an
exception message, so it can be a metric label.

### Passive, no thread or timer

The breaker only reacts to calls. It owns no thread, no timer and no executor, so it costs nothing
while idle and needs no shutdown. A caller that wants to retry by itself schedules after
`retryDelayNanos()`; the sender does that with `CompletableFuture.delayedExecutor`.
`skipBackoff()` ends the backoff at once, for a last attempt when the proxy shuts down.

### Cheap in the closed state

`tryAcquire()` and `onSuccess()` in the closed state read volatile fields and return, they
neither lock nor allocate, so they can be called for each request on an event loop (a test checks
the allocation). A refusal while open or half open is lock free as well: during an outage every
request of every event loop is refused, and they must not contend on one lock exactly then. Only
a state change takes the private lock object of the breaker, so no outside code can hold it:
granting a probe, a failure, the close after an outage, and a success that resets failures
counted before. These are rare, so the simpler locking was preferred over Armeria's compare and
set on immutable state objects. A caller that saw the open state and then waits for the lock
re-checks the state inside it. The state, the failures in a row, the retry time and the probe
time are volatile for the lock free reads; the other fields, including the trip and restart
times for the metrics, are read under the lock.

The failures in a row are counted across threads, so the count is approximate: a success that
runs at the same time as a failure may see no failure yet and not reset it.

### `Ticker` for durations, system clock for timestamps

The durations (backoff, probe timeout) come from Netty's `io.netty.util.concurrent.Ticker`, which
the proxy already uses in `RequestLoggingHandler`; Armeria injects a ticker the same way. Tests
drive it with `Ticker.newMockTicker()`.

The wall clock times for the metrics (start, last trip, last restart) are read from the system
clock, injected as a `java.time.InstantSource` so a test passes a clock that follows its mock
ticker. `InstantSource` is the standard type for a wall clock and narrower than `Clock`, which
carries a time zone; `InstantSource.system().millis()` reads `System.currentTimeMillis()` without
allocating an `Instant`. At first they were derived from the ticker, the wall clock time at
construction plus the ticker time passed since, to have one time source. That was dropped: the
derived times drift from the system clock after NTP steps, and on Linux the monotonic clock stops
while the machine is suspended, so the gauges would disagree with the timestamps of the logs.

Netty's system ticker counts from its own origin, not from `System.nanoTime()`. So the breaker
hands out a delay (`retryDelayNanos()`), never an absolute ticker time a caller might compare with
another clock.

### Events instead of built-in metrics

The breaker reports to an `Events` interface: each call with its kind, each error code, and each
transition with its reason (the error code for a trip, `backoff_over`, `recovered`,
`forced_open`, `forced_close`). This mirrors Armeria's `CircuitBreakerListener`. The events of a
state change are delivered with the lock held, so an implementation must not call back into the
breaker; all events are, since an ignored failure may close the breaker too. The sender logs only
the transitions (start of an outage, recovery), everything else is counted.

### What is not in the breaker

The slow start after an outage is part of the sender, not the breaker: after the recovery one
request is in flight, one more with each success, up to `maxConcurrentRequests`, so a log server
that just came back is not flooded. The age limit of waiting batches (`maximumUnsentLogAge`) and
the limit of buffered bytes are also the sender's. They end the retries for a remote that is gone
for good.

### Manual control: a `FORCED_OPEN` state

Armeria has a `FORCED_OPEN` state entered with `enterState`; the breaker adopts it for manual
maintenance. `forceOpen()` moves the breaker to `FORCED_OPEN` whatever its current state, and
there it refuses every request without a backoff: no probe, `tryAcquire()` or reported outcome
leaves it, only `forceClose()` does. A request that was already in flight when the breaker was
forced open is counted but does not close or reopen it. `forceClose()` closes the breaker and
resets the consecutive failures, so the next failure starts a fresh backoff.

The transition is counted with the reasons `forced_open` and `forced_close`, so a dashboard can
distinguish a manual outage from a trip (the error code) and a recovery (`recovered`). The trip
and restart times are not set by the manual change: they keep meaning "a failure opened it" and
"it closed after a trip".

The control surface is the admin endpoint, see `AdminServer`:
`POST /circuit-breakers/<component>/<name>/open` and `/close`. It looks the breaker up by the
component and name labels via `CircuitBreakerMetrics.find`, so only registered breakers can be
controlled, and a destination removed by a reload is simply gone (`404`). There is no upstream
breaker yet, so the endpoint controls the request log destinations today and the upstream
breakers later, once they exist, without a new protocol.

## Metrics

All breakers share one set of metrics, `sensepitch_circuitbreaker_*`, with two labels:
`component`, what the breaker protects (e.g. `requestLog`), and `name`, the instance within it
(e.g. the destination name, the same value as the `destination` label of the request log
metrics). Armeria has only a name; the component allows one dashboard over all kinds of breakers.

| Metric | Type | Meaning |
|---|---|---|
| `_state{state}` | gauge | 1 for the current state, 0 for the others |
| `_calls_total{kind}` | counter | `successful`, `failed`, `ignored`, `not_permitted` |
| `_transitions_total{from,to,reason}` | counter | each state change; the reason of a trip is the error code |
| `_errors_total{error}` | counter | each failed or ignored call by error code |
| `_consecutive_failures` | gauge | the failures the backoff is based on |
| `_start_time_seconds`, `_last_trip_time_seconds`, `_last_restart_time_seconds` | gauge | wall clock, 0 if it did not happen yet |

Decisions:

- The state is one-hot as in resilience4j, not Armeria's single gauge with 1 / 0.5 / 0: a query
  for `state="open"` needs no magic values, and a new state is a new label value.
- The kinds of calls use resilience4j's names, but as one counter: the breaker measures no call
  duration, so the timer resilience4j uses for completed calls would carry nothing extra.
- Instead of the sliding window gauges of Armeria and resilience4j, which do not exist here, the
  breaker exports the consecutive failures, the transitions with their reason and the times of
  the last trip and restart. They answer what an operator asks during an outage: since when, and
  why.
- The error codes are a small, fixed set, so `reason` and `error` keep the cardinality bounded.
- The data points of the calls are created with the breaker and cached, so they are exported with
  zero before the first call, and counting a refusal does not allocate.
- The gauges report the registered breakers only; the counters keep their values after a breaker
  is removed.
- One breaker is reported per component and name, the one added last. A configuration reload
  that changes a destination starts a new sender and breaker under the same name while the old
  one still drains. Reporting both would put the same label set twice into a scrape, which the
  Prometheus client rejects, so the whole scrape would fail. The counters of both breakers go to
  the same series.

For the request log sender, `not_permitted` counts refused dispatch attempts, not batches: the
breaker is asked when a batch arrives while nothing waits, and on each dispatch. The waiting
batches show in `sensepitch_request_log_buffered_bytes`.

## Limitations

Known limits of the current implementation, checked against the code on 2026-11-20:

- **Failures in a row are approximate across threads.** A success that runs at the same time as
  a failure on another event loop may not reset it, see "Cheap in the closed state".
- **Slow responses do not count.** A response within the request timeout is a success, however
  slow. Only the timeout itself is a failure. There is no slow call rate as in resilience4j.
- **Results of old requests decide the probe.** A result of a request sent before the breaker
  opened that arrives in `HALF_OPEN` counts as the probe's result, see above. Armeria behaves the
  same.
- **Two probes can be in flight.** A probe replaced after the probe timeout may still complete;
  its result then counts as well. With the probe timeout above the request timeout, this needs a
  completion that is lost or very late.
- **`Retry-After` cap.** A server can request at most 300 s of delay.
- **The maximum delay can be out of reach.** `Backoff` doubles at most 20 times, so the nominal
  delay stops at `initialRetryDelayMillis × 2^20`; with an initial delay of 1 ms that is about
  17.5 minutes, whatever larger maximum is configured.
- **One endpoint per sender.** A request log destination has one URI and one breaker; there is no
  failover to a second log server.
- **Metrics:**
  - During a reload that changes a destination, the gauges show the new breaker only. The old
    one still drains, and its state is visible only through the shared counters.
  - The counters of removed breakers stay exported until restart.
  - `not_permitted` depends on how often the sender dispatches, see [Metrics](#metrics).

## Planned improvements

In order of priority, all tracked in [NOT-YET.md](../../NOT-YET.md), sections "Circuit breaker" and
"Upstream":

1. **Upstreams: open only when the upstream is down.** Open after a number of consecutive
   failures (connect failures, timeouts), and any success resets the count; no failure rate while
   an upstream has a single server, which is all the edge supports today. Opening the breaker
   turns a partial failure into a total one: with no other server to send the request to, the
   requests that would have succeeded get `503` too. Partial overload is bounded by
   `connectionPool.maxConnections` and the timeouts instead, see
   [upstream-connection-pool.md](upstream-connection-pool.md). NGINX decides the same, from the
   [upstream module docs](https://nginx.org/en/docs/http/ngx_http_upstream_module.html), read on
   2026-11-28: "If there is only a single server in a group, `max_fails`, `fail_timeout` and
   `slow_start` parameters are ignored, and such a server will never be considered unavailable."
   The breaker has the threshold, both probe modes and the start state for it (see the design
   decisions above); what remains is the upstream side:
   - A probe endpoint per upstream, optional. If configured, the breaker uses
     `callerProbes` and `startOpen`: the upstream is probed at startup and closes on the first
     successful probe, then the edge sends a probe after each backoff while open. Without it, the
     next client request after the backoff is the probe.
   - An `INFO` line when an upstream becomes unavailable (the trip) and when it is alive again
     (the close after a trip), from `Events.transition`, as the request log sender logs its
     outages; one line per transition, not per request.
   - A short upstream connect timeout: `DefaultUpstream` sets no `CONNECT_TIMEOUT_MILLIS`, so
     Netty's default of 30 s applies to a client request that is the probe.

   Two rules for classifying an exchange:
   - By the `RequestOutcome` or the exception, never by the status sent to the client: the
     edge's own `503` and one of the upstream look the same. A refusal at the pool limit
     (`upstream_connection_limit`) is no failure, the upstream was not asked.
   - The permit is taken only after a pool connection was reserved or taken, so a request refused
     at the limit cannot use up the probe of a half open breaker; where that order cannot be
     kept, the refused request reports `onIgnored`.
2. **Several endpoints.** One breaker per endpoint of a destination or upstream, see the
   `Endpoint` record in `HttpBatchSender`, and for upstreams the draft
   [upstream-targets.md](upstream-targets.md). Only
   here a failure rate over a window with a minimum number of calls (Armeria, resilience4j) and a
   slow call rate (resilience4j) make sense: they take one server out while the others serve, and
   never all of them, like Envoy's `max_ejection_percent` (from memory, not verified). The window
   counts may become gauges like Armeria's `requests{result}`.
