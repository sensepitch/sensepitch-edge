# Encrypted transport

This document covers how client connections enter the proxy: the plain HTTP listener in
[`Proxy#bindPlainHttpListener`](../../src/main/java/org/sensepitch/edge/Proxy.java) with the
[`HttpToHttpsRedirectHandler`](../../src/main/java/org/sensepitch/edge/HttpToHttpsRedirectHandler.java),
the TLS listener with Netty's `SniHandler` and the contexts of
[`SslSetup`](../../src/main/java/org/sensepitch/edge/SslSetup.java), and the request pipeline
built by [`Proxy#addHttpHandlers`](../../src/main/java/org/sensepitch/edge/Proxy.java). It records
why unencrypted client traffic never reaches routing, and what the rest of the code may assume
because of that.

## Purpose and scope

Sensepitch Edge is an opinionated gateway for modern web applications, not a universal reverse
proxy. Modern browsers and clients speak TLS. Letting plaintext into the routing logic would be a
security issue and would add obligations to every part of the pipeline:

- credentials and session cookies could arrive over an unencrypted connection
- every route, protection and cache rule would have to tell secure from insecure transport
- a redirect to HTTPS would have to happen before authentication, protection and caching
- forwarded headers could claim a secure connection that does not exist
- a configuration mistake could expose an application over plaintext

Each of these widens the security relevant code and what has to be tested. The design avoids
them with three rules:

- **Encrypted transport is an invariant, not an option.** No listener, route or site setting
  lets unencrypted client traffic into the request pipeline,
  [`Proxy#addHttpHandlers`](../../src/main/java/org/sensepitch/edge/Proxy.java).
- **The encryption properties are verified where they are relied on, and kept apart.** A
  component that depends on the encryption checks that it is there and fails closed. Protocol
  version, cipher, ALPN protocol and server name stay available per connection, so that a later
  decision can tell them apart, e.g. a cipher policy or the request log.
- **TLS proves the channel, nothing more.** It does not identify the client and does not
  authorize a request; the client side certificate is not requested (`ClientAuth.NONE`).

The connection to the upstream is a separate matter, see
[Upstream encryption is a separate decision](#upstream-encryption-is-a-separate-decision).

## Overview

| Client protocol | Behaviour | Status |
|---|---|---|
| HTTP/1.1 cleartext | redirect to HTTPS only, later an ACME HTTP-01 responder | redirect done |
| HTTP/2 cleartext (h2c) | not supported | done, nothing listens for it |
| HTTP/1.1 over TLS | full request processing | done |
| HTTP/2 over TLS | full request processing | planned, needs ALPN |
| HTTP/3 over QUIC | full request processing | not started |

```text
TCP listen.httpPort (80)
  └── HttpServerCodec
      └── HttpToHttpsRedirectHandler: 308 to https, or 400; connection closed
          (later: ACME HTTP-01 challenge)

TCP listen.httpsPort (443)
  └── [Ja4Handler, reads the ClientHello]
      └── SniHandler → SslHandler (TLS terminated here)
          └── HttpServerCodec
              └── Proxy#addHttpHandlers: the request pipeline
                  ├── fingerprints, host check, request log
                  ├── routing (site selection), protection
                  └── upstream forwarding

UDP 443, QUIC, HTTP/3: not started
```

On 2026-11-26, before the edge pinned its TLS versions, a measurement with Netty 4.2.21 and its
BoringSSL build found the following defaults by reading the enabled protocols and cipher suites
from a new server engine. The split of the suites was corrected on 2026-11-28 against Netty's
list, `SslUtils.DEFAULT_CIPHER_SUITES` in 4.2.21, all of which BoringSSL supports.

- protocols TLS 1.3 and TLS 1.2; `SSLv2Hello` is listed as well, Netty adds it to every OpenSSL
  engine and has no way to remove it
- 12 cipher suites: the 3 of TLS 1.3, 6 ECDHE suites, and 3 suites with RSA key exchange, which
  have no forward secrecy; 4 of the 12 use CBC with SHA-1. No ChaCha20 for TLS 1.2.
- key exchange groups: `X25519MLKEM768` first, the hybrid post-quantum group, then `x25519`,
  `secp256r1`, `secp384r1`, `secp521r1` (Netty's `OpenSsl` with BoringSSL, read from the source)
- no ALPN protocols, so every client speaks HTTP/1.1

The edge keeps the protocols and groups and replaces the suites, see the next two decisions.
The test `SslSetupTest` pins the result.

## Design decisions

### Pin the client-facing TLS versions

`SslSetup` enables TLS 1.2 and TLS 1.3 on the shared server context builder for all certificates.
This keeps the supported versions stable across provider upgrades and changes to provider defaults.
TLS 1.0 and 1.1 are rejected with a `protocol_version` alert.

The `SSLv2Hello` that Netty reports among the enabled protocols negotiates nothing. BoringSSL
still reads a ClientHello in the SSL 2.0 format, but the format has no extensions, so no ECDHE
group can be agreed and the suites below leave nothing to negotiate: the server answers with a
`handshake_failure` alert, as to a TLS 1.2 hello without extensions (measured 2026-11-28, Netty
4.2.21, `SslSetupTest#sslV2HelloIsRejected`). With Netty's default suites, a `TLS_RSA_*` suite
would need no group; that such a hello was accepted before is inferred, not tested.

### TLS 1.2 suites by rule: ECDHE and AEAD only

The edge serves public websites, so it needs to reach old but still used clients, not every
client ever built. The TLS 1.2 suites follow the Mozilla server side TLS "intermediate" profile
(version 5.7): an ephemeral ECDHE key exchange authenticated by the certificate (ECDSA or RSA),
for forward secrecy, and an AEAD cipher, AES-GCM or ChaCha20-Poly1305. BoringSSL has no DHE, so
the profile's DHE suites are left out. Gone compared to Netty's defaults are the static RSA key
exchange (no forward secrecy) and CBC with SHA-1; ChaCha20 is added, which helps devices without
AES instructions.

The suites are selected by a rule, `SslSetup.CIPHER_SUITES`, a Netty `CipherSuiteFilter` over
all suites the library supports, not by a list: the edge owns the policy, the library the
algorithms. TLS 1.3 suites pass the rule unchanged; BoringSSL ignores the selection anyway, and
Netty disables TLS 1.3 when the list holds no TLS 1.3 suite. The key exchange groups stay
Netty's defaults, including the post-quantum `X25519MLKEM768`.

A rule can let through what nobody looked at: BoringSSL also supports
`TLS_ECDHE_PSK_WITH_CHACHA20_POLY1305_SHA256`, which a rule of "ECDHE and AEAD" without the
certificate authentication would have enabled. So `SslSetupTest#enabledCipherSuitesAreKnown`
compares the outcome with a known list. A suite that a library upgrade adds or drops fails the
build; it is reviewed against this policy, then the list and this document are updated.

There is no configuration for suites or protocols. Clients that offer only suites without
forward secrecy or only CBC suites cannot connect; by the Mozilla compatibility tables that is IE
11 on Windows 7 and 8.1 against an RSA certificate (the profile reaches it only through DHE),
Android 4.3 and older, Java 7 and older OpenSSL builds. Current browsers, Android 4.4.2 and later
and Java 8 and later are unaffected.

### Port 80 is a separate pipeline, not a route

The plain HTTP listener has a pipeline of its own: the codec and the
[`HttpToHttpsRedirectHandler`](../../src/main/java/org/sensepitch/edge/HttpToHttpsRedirectHandler.java),
which answers every request with a `308` to the same resource on HTTPS, or a `400` without a
usable host, and closes the connection. It shares no handler with
[`Proxy#addHttpHandlers`](../../src/main/java/org/sensepitch/edge/Proxy.java), so no site, route,
protection or upstream is reachable in plaintext, whatever the configuration says. The redirect
happens before anything else looks at the request.

The redirect is only given for a host the edge serves: the handler matches the normalized `Host`
against the known hosts like
[`SanitizeHostHandler`](../../src/main/java/org/sensepitch/edge/SanitizeHostHandler.java) does,
with the host list of the active configuration, and answers an unknown host with `400`, as the TLS
side does. Before, any syntactically valid host was redirected, so `Host: anything.example` got
`308 Location: https://anything.example/…`. That was no open redirect, it only led to the host
the client asked for itself, but a forged `Host` could put a redirect to a foreign domain into a
cache in front of port 80, scanners got a plausible answer, and the client spent a TLS handshake
on a host the edge has no site for.

An ACME HTTP-01 responder, needed to issue certificates without DNS (Kubernetes plan item 12, see
[`3-gap-analysis.md`](../kubernetes-support/3-gap-analysis.md)), belongs into this pipeline as
well, next to the redirect, not into routing.

### Port 80 is counted, not logged

Requests on the plain HTTP listener are not written to the request log, and that is intended:
the request log records requests the edge processed, its records carry what the encrypted
pipeline determined (TLS properties, JA4, the site, the protection outcome), and a record of a
plaintext request in the same log would treat it as traffic of the edge, against the invariant.
It would also put the client address, the path and the headers of an unencrypted request into
the log, data that was readable on the way and that the edge does not need to answer with a
redirect.

What the listener does is observable through counters instead:
`sensepitch_plain_http_requests_total` counts the answers of the redirect handler by outcome
(`redirect`, `unknown_host`, `bad_request`), so a missing redirect for a served site, a wave of
unknown hosts or malformed requests shows in the metrics. The labels are a fixed set, no host or
path, see the cardinality rule in [`AGENTS.md`](../../AGENTS.md). The request counter is
separate from the metrics of the TLS listener, with a name of its own and no shared label. The
plain HTTP listener has no connection metrics of its own: every connection carries one request
and is closed after the answer, so the connections in flight say nothing the request counter
does not. Its connections pass the shared
[`TrackIngressConnectionsHandler`](../../src/main/java/org/sensepitch/edge/TrackIngressConnectionsHandler.java),
so a shutdown drains them, and are included in `sensepitch_ingress_connections_*`.

### The invariant is structural, and verified where it is relied on

The invariant holds by construction: in production the only way into `addHttpHandlers` leads
through the `SslHandler` of the TLS listener. No runtime check per request is needed to keep
plaintext out.

On top of that, a component that relies on the encryption verifies it where it uses it, and
fails closed: a missing TLS session is an error, not a default. This catches a wiring mistake,
e.g. a new listener that calls `addHttpHandlers` without TLS, instead of serving it quietly. The
code does not do this everywhere yet: the
[`RequestLoggingHandler`](../../src/main/java/org/sensepitch/edge/RequestLoggingHandler.java)
treats a missing `SslHandler` as "no TLS", and the request log then reports `url.scheme` `http`.
That branch exists because the unit tests drive `addHttpHandlers` on an `EmbeddedChannel`
without TLS; closing it needs a test seam that stands in for the TLS session, see
[Planned improvements](#planned-improvements).

The properties of the encryption are kept per connection, so they can be told apart later: the
request log has the protocol and cipher (`tls.protocol.*`, `tls.cipher`) and the JA4 fingerprint
of the ClientHello, see [browser fingerprinting](browser-fingerprinting.md).

### TLS terminates at the edge

A load balancer that terminates TLS in front of the edge would bring the plaintext back: the
edge would see an unencrypted connection and would have to trust an `X-Forwarded-Proto` header
for the scheme. That is not supported. TLS terminates at the edge, and a load balancer in front
works on layer 4, passing the TCP connection through; the client address then comes with the
PROXY protocol, which the edge does not support yet. This replaces the trust in
`X-Forwarded-Proto` from configured proxy addresses that the Kubernetes plan contemplated.

It also keeps the TLS properties at the edge: the JA4 fingerprint and the negotiated protocol
are only visible where the handshake happens.

### The external scheme is always `https`

Because every routed request arrived over TLS, the code states the scheme instead of checking it
per request:

- [`DownstreamHandler`](../../src/main/java/org/sensepitch/edge/DownstreamHandler.java) sets
  `X-Forwarded-Proto: https` and replaces a client's `X-Forwarded-For` with the peer address
- the admission and protection cookies are `Secure`, see
  [`Deflector`](../../src/main/java/org/sensepitch/edge/Deflector.java),
  [`CookieGate`](../../src/main/java/org/sensepitch/edge/CookieGate.java) and
  [`SetCookieConfig`](../../src/main/java/org/sensepitch/edge/SetCookieConfig.java)
- redirects of the edge use `https`

A forwarding header sent by the client is never trusted for the scheme.

### HSTS always, not configurable

Every routed response goes out over TLS, and port 80 only redirects, so
`Strict-Transport-Security` is correct for every response. A switch to turn it off would only
allow the invariant to be undermined, so there is none. The value is fixed to
`max-age=31536000`, one year, without `includeSubDomains` and without `preload`: those two reach
beyond the hosts the edge serves, they would break a plaintext subdomain served elsewhere, and a
preload entry is hard to undo. If the upstream sends its own `Strict-Transport-Security`, the
edge keeps it and adds none, also `max-age=0` that clears a remembered policy, so an application
decides about `includeSubDomains` and `preload` itself, without configuration of the edge.

[`DownstreamHandler`](../../src/main/java/org/sensepitch/edge/DownstreamHandler.java) adds the
header after it removed the hop-by-hop headers, to forwarded responses and to the static
responses a site configures (`response:` with `location`, `text` or `file`). The static ones
need it from the edge, because no upstream stands behind them that could send it and their
configuration has no way to add headers. And a host that only redirects, like a bare domain to
`www`, needs it most: users type the bare domain, so its first request is the one a browser
would otherwise send in plaintext, and only an HSTS header from that host itself prevents that.

The consequence: a host that moves from the edge to a plaintext server stays HTTPS only in the
browsers that saw it, for up to a year. Under this design that is intended. Responses the edge
generates itself do not carry the header yet, see [Limitations](#limitations).

### Restricted Gateway API subset

The Kubernetes support implements the Gateway API only as far as it fits the invariant. An
`HTTP` listener redirects to HTTPS, nothing else. An `HTTPRoute` attached to an `HTTP` listener
that does more than redirect to HTTPS is rejected with a status condition on the route; a
configuration that would forward plaintext is refused, not silently ignored. Sensepitch accepts
the narrower compatibility for the simpler security model, see
[`2-gateway-api-scope.md`](../kubernetes-support/2-gateway-api-scope.md).

### Upstream encryption is a separate decision

The invariant is about the client side. Today the edge connects to its upstreams in plaintext,
which assumes a trusted network between them, e.g. the same host or the pod network. Encrypting
that leg has other trade-offs (certificates of internal services, latency of the second
handshake, connection reuse) and is decided on its own, see
[Planned improvements](#planned-improvements).

## Alternatives considered

- **Plaintext as a per-listener or per-route option**, as NGINX, Traefik or Caddy offer.
  Rejected: every handler would have to tell the transports apart, and a configuration mistake
  would expose an application.
- **Redirecting to HTTPS inside the routing pipeline.** Rejected: the request would pass
  decoding, logging, host checks and protection in plaintext before it is redirected, and every
  later change to the pipeline would have to keep the redirect first.
- **Trusting `X-Forwarded-Proto` from configured proxy addresses**, for a load balancer that
  terminates TLS. Rejected, see [TLS terminates at the edge](#tls-terminates-at-the-edge).

- **Netty's default suites minus an exclude list** (static RSA, CBC). Rejected: the base is a
  list fixed in Netty, not what BoringSSL offers, it lacks ChaCha20 for TLS 1.2, and a suite
  Netty adds later is enabled without review.
- **An explicit allow list of suites.** Works, but a suite a library adds needs a code change to
  be offered; the rule with the known list in the test asks for the same review with less code.
- **Mozilla "old" profile** for clients before 2014. Not for a gateway of modern web
  applications.
- **A `strict` profile, TLS 1.3 only, as an option.** Not now, nobody needs it; it would be a
  configuration value selecting a profile, never a list of suites.

## Limitations

- Clients without TLS get only the redirect.
- The plain HTTP listener is counted, not logged: an individual request on port 80 cannot be
  investigated afterwards, see [Port 80 is counted, not logged](#port-80-is-counted-not-logged).
- The fail closed check in the pipeline is missing, see [The invariant is structural, and
  verified where it is relied on](#the-invariant-is-structural-and-verified-where-it-is-relied-on).
- Responses the edge generates itself carry no HSTS header: the protection challenge, the
  fallback pages of `FallbackHandler`, the error pages of `ExceptionHandler` and the answers to
  an unserviced host. They are written before `DownstreamHandler` or after it dropped the
  upstream, see [HSTS always, not configurable](#hsts-always-not-configurable).
- No HTTP/2 or HTTP/3 yet, and the connection to the upstream is plaintext.

## Planned improvements

Listed in [`TODO.md`](../../TODO.md) under "Transport encryption": fail closed without a TLS
session, HTTP/2 over TLS, and upstream TLS.
