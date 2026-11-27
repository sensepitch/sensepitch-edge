# Slow request protection (draft)

**Draft, nothing decided.** This is the research for the `TODO.md` entry "Protection for slow
requests" under "Ingress, HTTP": a better protection than one per request timeout, which gives
legitimate uploads more time and simple `GET` requests, which should arrive in one go, a stricter
limit. The options marked `[ ]` are open choices for the maintainer; the entry in `TODO.md` lists
them for ticking. It covers the receive timeouts in
[`IngressTimeoutHandler`](../../src/main/java/org/sensepitch/edge/IngressTimeoutHandler.java) with
its [`ConnectionConfig`](../../src/main/java/org/sensepitch/edge/ConnectionConfig.java), the
decoder refusals in
[`DecoderFailureHandler`](../../src/main/java/org/sensepitch/edge/DecoderFailureHandler.java) and
the pipeline set up in [`Proxy`](../../src/main/java/org/sensepitch/edge/Proxy.java) (`start()`
and `addHttpHandlers`). It continues the "Slow uploads" part of
[request-body-limit-draft.md](request-body-limit-draft.md), which added the total body timeout
`requestTimeoutSeconds`. Once the choices are made, the result goes into a design document for the
receive timeouts and this draft is removed.

The code facts were read on `main` at `735309f` on 2026-11-26, with Netty 4.2.21.Final (the
version in `pom.xml`).

## Purpose and scope

A slow client holds a connection, and once its request head is complete also an upstream
connection, for as long as the edge waits for it. The attacks are known as Slowloris (the head is
sent slowly) and slow POST (the body is sent slowly). The OWASP Denial of Service Cheat Sheet
(read on 2026-11-26, no version or date on the page) describes them: "Slow HTTP attacks deliver
HTTP requests very slow and fragmented, one at a time", so that "the server will keep resources
stalled while waiting for the missing incoming data".

These rules bound the design:

- The proxy is transparent: it forwards what the client sends and the upstream decides. A limit
  that a legitimate client can hit is a caveat for operators.
- Every timer and counter is bounded and runs on the connection's event loop, never blocking it
  (`AGENTS.md`).
- Keep the semantics and defaults of the existing configuration unless a choice changes them, and
  report each such change.
- Metric labels have a bounded set of values; a refused request is counted in
  `sensepitch_ingress_receive_timeout` (labels `ingress`, `phase`) and reported with the outcome
  `request_timeout` in the request log and `sensepitch_ingress_request_outcomes_total`.

## Overview: what bounds a request today

### One timer serves several phases

`IngressTimeoutHandler` keeps one current timer (`task`) and, for a request with a body, a second
fixed deadline (`requestDeadline`). The phases, in the order of a connection:

| Phase | Timer | Moved by | Answer, `phase` label |
| --- | --- | --- | --- |
| TLS handshake | `SniHandler` and `SslHandler`, 10 s each (Netty default) and `readTimeoutSeconds` from accept | nothing | close; 408 and `first_request` if the read timeout fires first |
| head of the first request | `readTimeoutSeconds` from accept | nothing, see below | 408, `first_request` |
| body | `readTimeoutSeconds`, idle | every `HttpContent` | 408, `first_request` or `keep_alive` |
| body, total | `requestTimeoutSeconds` from the complete head, `0` = off (default) | nothing | 408, `request_total` |
| wait for the response | `responseTimeoutSeconds` from the end of the request | nothing | 504, outcome `upstream_timeout` |
| writing the response | `writeTimeoutSeconds`, idle | every completed write | close, outcome `response_write_timeout` |
| rest of the body after an early response | `readTimeoutSeconds`, idle | every `HttpContent` | close, `after_response` |
| keep-alive wait and head of the next request | `readTimeoutSeconds` from the end of the response | nothing, see below | 408, `keep_alive` |

Defaults in `ConnectionConfig.DEFAULT`: read 30 s, write 30 s, response 15 s, request 0 (off).

Details, read from the code:

- The handler is added in the channel initializer, after `SniHandler` and before TLS is
  established, so `handlerAdded` creates the `first_request` timer at accept, not after the TLS
  handshake as the `ConnectionConfig` doc says. The handshake time counts into the read timeout.
- `SniHandler(Mapping)` uses `AbstractSniHandler.DEFAULT_HANDSHAKE_TIMEOUT_MILLIS` (10 s) to wait
  for the ClientHello and passes the same value to the `SslHandler` it creates
  (`newSslHandler` calls `setHandshakeTimeoutMillis`). Read with `javap -c` from
  `netty-handler-4.2.21.Final.jar`; not configured by the edge.
- The handler sits after `HttpServerCodec`, so it sees decoded messages only. The decoder emits
  the `HttpRequest` when the head is complete. Until then no message reaches the handler and the
  timer is not moved: **the head is already bounded by a total of `readTimeoutSeconds`**, from
  accept for the first request and from the end of the previous response on a keep-alive
  connection. The head size is bounded by the decoder limits, 4096 bytes request line and 8192
  bytes header, answered with 414 and 431 by `DecoderFailureHandler`.
- `hasBody` decides from the head: chunked, or `Content-Length` above 0. Only then the
  `requestDeadline` starts and the keep-alive decision waits for the end of the body.
- For a request without a body the codec emits the empty `LastHttpContent` right after the head,
  so the response timeout starts at once; there is no receive phase left after the head.
- Every `HttpContent`, however small, calls `task.touch()`. A body sent at one byte per 29 s stays
  open as long as `requestTimeoutSeconds` is off.
- The `Keep-Alive: timeout=` header of a response advertises `readTimeoutSeconds`.

### Experiment (measured)

Measured on 2026-11-26 on `735309f` in the turn's sandbox (2 cores), with a throw-away JUnit test
(not committed): an `EmbeddedChannel` with a mock ticker, `HttpServerCodec`,
`IngressTimeoutHandler` (`readTimeoutSeconds` 10, `requestTimeoutSeconds` 0) and a handler that
records the messages.

- A 36-byte `GET` head sent one byte per simulated second: no message reached the handler, and
  the connection was closed after 10 s with `first_request` counted once. The trickled head did
  not move the timer.
- `GET / HTTP/1.1` with `Host` only: `DefaultHttpRequest`, then the empty last content, in one
  read.
- `GET` with `Content-Length: 3`: the head first, then one `DefaultHttpContent` per one-byte
  read, then `DefaultLastHttpContent`. Netty reads the body of a `GET` like any other.
- `GET` with `Transfer-Encoding: chunked`: the head first, the empty last content only after
  `0\r\n\r\n`.

### The open choices this continues

`request-body-limit-draft.md`, "Slow uploads", leaves open: the default of
`requestTimeoutSeconds` (off, or on with e.g. 300 s), a minimum upload rate, and the maintainer's
note "slow upload needs a separate investigation. Normal requests without body should have a more
tight timeout. Would an upstream usually give us an error when a body is sent but not expected, or,
would the body always received?". The answers are in the next section and under "Design options".

## Requests without a body (RFC 9110, RFC 9112)

**The edge knows from the head whether a body follows.** RFC 9112 section 6: "The presence of a
message body in a request is signaled by a Content-Length or Transfer-Encoding header field." and
section 6.3, rule 7: "If this is a request message and none of the above are true, then the message
body length is zero." So a head without both fields is the whole request, whatever the method.
This is what `hasBody` already checks. The framing does not depend on the method: a `GET` or
`HEAD` with `Content-Length` or chunked encoding has a body that is framed like a `POST` body
(also measured above for Netty).

**A body in a `GET` is legal framing but has no meaning.** RFC 9110 section 9.3.1: "content
received in a GET request has no generally defined semantics", it "might lead some
implementations to reject the request and close the connection" because of request smuggling; the
same wording is in 9.3.2 (`HEAD`) and 9.3.5 (`DELETE`). For senders: "A client SHOULD NOT generate
content in a GET request unless it is made directly to an origin server" that has indicated
support (9.3.1), and "A user agent SHOULD NOT send a Content-Length header field when the request
message does not contain content" (8.6).

**Would an upstream answer with an error?** Not in general: the RFC allows both. Whether a given
upstream reads, ignores or refuses such a body was not measured here (unverified). Two
consequences hold without it:

- The edge cannot rely on the upstream to cut a slow body short. If the upstream answers before
  the body is complete, the edge already closes the connection once the rest has arrived or after
  the read timeout (`after_response`), so an early answer of the upstream does help.
- A tighter limit for "requests without a body" needs no method rule: a request without
  `Content-Length` and `Transfer-Encoding` ends with its head, so a tight head limit is the tight
  limit for it. A `GET` with a body is then treated like any request with a body.

## Implementations investigated

All read on 2026-11-26. Quotes are from the pages named; versions as shown on the page.

### NGINX (ngx_http_core_module, page shows no version)

- `client_header_timeout`, default 60 s: "If a client does not transmit the entire header within
  this time, the request is terminated with the 408 (Request Time-out) error." A total for the
  head.
- `client_body_timeout`, default 60 s, context includes `location`: "The timeout is set only for a
  period between two successive read operations, not for the transmission of the whole request
  body." An idle body timeout, like `readTimeoutSeconds`.
- `keepalive_timeout`, default 75 s, separate from the header timeout.
- No minimum rate and no total body timeout; the 1 MiB default body limit bounds a slow body
  indirectly (see the body limit draft).

### Apache httpd 2.4, mod_reqtimeout (docs/current, "Version 2.4")

- Default `RequestReadTimeout handshake=0 header=20-40,MinRate=500 body=20,MinRate=500`
  ("defaulted to disabled in version 2.3.14 and earlier"; `handshake` "since version 2.4.39").
- `stage=timeout`: "The time in seconds allowed for completing the whole stage". With `MinRate`:
  "whenever data is received, the timeout value is increased according to the specified minimum
  data rate (in bytes per second)", and `timeout-maxtimeout`: "the timeout will not be increased
  above the second value".
- Example: "Allow for 5 seconds to complete the TLS handshake, 10 seconds to receive the request
  headers and 30 seconds for receiving the request body".
- So: header total 20 s, extended by 1 s per 500 bytes up to 40 s; body 20 s, extended by 1 s per
  500 bytes without upper bound. This is the closest model for a minimum rate with a grace time.

### HAProxy 3.2 (`doc/configuration.txt` at tag `v3.2.0`)

- `timeout http-request`: the time to receive a complete request, answered with 408. "By default,
  this timeout only applies to the header part of the request," and "When combined with "option
  http-buffer-request", this timeout also applies to the body of the request.."
- `timeout http-keep-alive`: the wait for the next request; "If this parameter is not set, the
  "http-request" timeout applies". An expired keep-alive closes without an error.
- `timeout client` is the inactivity timeout; for the request phase "it is preferable to set the
  "timeout http-request" to better protect HAProxy from Slowloris like attacks."
- Streaming bodies are bounded only by the idle `timeout client`.

### Envoy (docs for 1.41.0-dev)

- `request_headers_timeout`: "The timer is activated when the first byte of the headers is
  received"; off by default.
- `request_timeout`: "activated when the request is initiated, and is disarmed when the last byte
  of the request is sent upstream"; off by default, the timeouts FAQ says it "is not compatible
  with streaming requests".
- `stream_idle_timeout`, default 5 minutes, ends the stream with 408 "if no upstream response
  header has been received". The FAQ: "strongly recommended for all requests".
- The edge best practices page sets `request_timeout: 300s` and `stream_idle_timeout: 300s`, both
  commented "must be disabled for long-lived and streaming requests"; connection `idle_timeout`
  3600 s. Route level timeouts can override per route.

### Caddy (caddyfile global options, page shows no version)

- `read_header`, default `1m`; `read_body`, default no timeout; `idle` (keep-alive), default 5
  minutes.
- `read_body_idle`, default `1m`: the deadline resets after every successful read, with an
  optional `<min_rate>`, bytes per second averaged from the start of reading the body; no minimum
  by default. `read_body` caps how far it can extend. The page advises: "to mitigate slowloris
  attacks, prefer `read_body_idle`" over a short `read_body`.

### Go net/http (pkg.go.dev, go1.27.4)

- `ReadHeaderTimeout` "is the amount of time allowed to read request headers." Afterwards "The
  connection's read deadline is reset after reading the headers and the Handler can decide what is
  considered too slow" for the body.
- `ReadTimeout` "is the maximum duration for reading the entire request, including the body", and
  "most users will prefer to use ReadHeaderTimeout".
- `IdleTimeout` "is the maximum amount of time to wait for the next request when keep-alives are
  enabled", falling back to `ReadTimeout`.

### Attack catalogues

- OWASP Denial of Service Cheat Sheet: "Define a minimum ingress data rate" and "Define an absolute
  connection timeout"; the caution "A minimum rate set too high can reject legitimate slow
  clients", "setting it too low weakens protection against slow senders."
- CAPEC-469 "HTTP DoS" (version 3.9): "The idea is to keep the HTTP session alive indefinitely
  and then repeat that hundreds of times." Mitigation: "Configure web server software to limit the
  waiting period on opened HTTP sessions".

### What they have in common

Every server separates the head from the body. The head gets a total timeout (NGINX 60 s, Apache
20-40 s, HAProxy and Envoy off unless set, Caddy 60 s, Go set by the application). The body gets an
idle timeout, and at most an opt-in total; only Apache (on by default) and Caddy (opt-in) have a
minimum rate. The keep-alive wait is separate from the head timeout in NGINX, HAProxy, Caddy and
Go.

## Design options

### A. Head timeout of its own

Today the head shares `readTimeoutSeconds` with the body idle time and the keep-alive wait, so it
cannot be tightened without tightening the others.

- [ ] A new `listen.connection.requestHeadTimeoutSeconds`: the total time to receive a complete
  head, answered with 408. `readTimeoutSeconds` stays the idle body timeout.
- [ ] Keep one `readTimeoutSeconds` and only document that it is a total for the head.

Start of the head timer, if A is chosen:

- [ ] From accept (first request) and from the end of the previous response (keep-alive), as
  today; simplest, needs no handler before the codec, and the keep-alive wait and the head are one
  budget (the HAProxy default when `http-keep-alive` is not set).
- [ ] From the first byte of the head, with a separate keep-alive wait before it (NGINX, Envoy,
  HAProxy with both set). Needs a handler between TLS and the codec that sees the first decrypted
  byte, and a new keep-alive setting, e.g. `keepAliveTimeoutSeconds`, which the `Keep-Alive`
  header then advertises.

### B. Tighter limit for requests without a body

- [ ] Nothing separate: a request without `Content-Length` and `Transfer-Encoding` is complete with
  its head (RFC 9112 6.3), so the head timeout of A is its limit.
- [ ] Refuse a body in `GET`, `HEAD` and `DELETE` (400 or 413), allowed by RFC 9110 9.3. Breaks
  APIs that send bodies with these methods (unverified which ones still do) and contradicts the
  transparency rule.

### C. Bound for the body

- [ ] Minimum body rate after a grace time, like Apache `MinRate`: the deadline is `grace +
  bytesReceived / minRate` from the complete head, checked when the timer fires, capped by
  `requestTimeoutSeconds` when that is set. New settings e.g. `bodyGraceSeconds` and
  `minBodyBytesPerSecond`, `0` = off. Counted with a new phase, e.g. `body_rate`.
- [ ] Only the total `requestTimeoutSeconds` (exists), with a default on (e.g. 300 s like the
  Envoy edge example). Simple, but large uploads over slow links fail.
- [ ] Idle only, as today with `requestTimeoutSeconds` off.

### D. Per site or per route overrides

The head arrives before the site is known (the `Host` header is part of it), so a head limit can
only be per listener; SNI is known earlier but may differ from the host. Body limits can be per
site or path, since `SiteSelectorHandler` has chosen the site when the body starts.

- [ ] Body limits overridable per site and per path (`SiteConfig`, `PathRouteConfig`), e.g. an
  upload path with a lower minimum rate or a longer total.
- [ ] Listener level only (`listen.connection`), as the timeouts are today.

### E. Defaults

Changing a default changes existing setups and is reported as such.

- [ ] Head timeout 10 s (the Apache example).
- [ ] Head timeout 20 s (Apache default lower bound).
- [ ] Head timeout stays 30 s (today's `readTimeoutSeconds`).
- [ ] Minimum body rate off by default.
- [ ] Minimum body rate on, 500 bytes/s after 20 s grace (Apache default).

### F. Outcome and metric of each refusal

The existing scheme is kept in every option: 408 with `Connection: close`, outcome
`request_timeout`, one count in `sensepitch_ingress_receive_timeout` with a fixed phase value.

- [ ] Separate phases: `head` (or keep `first_request` and `keep_alive` for the head), `body_idle`,
  `body_rate`, `request_total`. Also fixes that a body idle timeout is counted as
  `first_request` or `keep_alive` today, after the timer of the head it continues.
- [ ] Keep today's phases and add only `body_rate`.

## Alternatives considered

- A per-method timeout (shorter for `GET`): the method says nothing about the framing, RFC 9112
  frames by the header fields, so a method rule would only add a second, less exact signal.
- Buffering the whole body before connecting the upstream (NGINX's default, HAProxy's
  `http-buffer-request`): bounds the upstream side, but contradicts streaming with backpressure and
  costs memory; rejected in the body limit draft.
- Limiting connections per client address: a different control (rate limiting), which the project
  goals keep out of the edge.

## Limitations

- The experiment ran the codec and the handler on an `EmbeddedChannel` without TLS; the TLS
  handshake timeouts are read from the bytecode, not measured.
- Which upstreams refuse a body in a `GET` was not measured.
- Unread sources: the OWASP Slowloris attack page (`owasp.org/www-community/attacks/Slowloris`
  redirects to `community.owasp.org/attacks/Slowloris`, which answered 404);
  `httpd.apache.org/docs/2.4/mod/mod_reqtimeout.html` answered 404, `docs/current` was read
  instead; the HAProxy HTML manual was cut off before the timeout keywords by the page reader, the
  plain text at tag `v3.2.0` was read instead; RFC 9110 section 15.5.9 (408) was not read.
- HTTP/1.1 only; HTTP/2 and QUIC (`NOT-YET.md`) have their own flow control.

## Planned improvements

The open choices are in the `TODO.md` entry "Slow request protection" under "Ingress, HTTP".
