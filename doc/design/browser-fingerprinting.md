# Browser fingerprinting

This document covers the client traits the proxy collects: the passive JA4 TLS fingerprint in
[`Ja4Handler`](../../src/main/java/org/sensepitch/edge/Ja4Handler.java),
[`ClientHelloParser`](../../src/main/java/org/sensepitch/edge/ClientHelloParser.java),
[`Ja4Fingerprint`](../../src/main/java/org/sensepitch/edge/Ja4Fingerprint.java) and
[`Ja4HeaderHandler`](../../src/main/java/org/sensepitch/edge/Ja4HeaderHandler.java), the
fingerprint of the request headers in
[`Sp1Fingerprint`](../../src/main/java/org/sensepitch/edge/Sp1Fingerprint.java) and
[`FingerprintHandler`](../../src/main/java/org/sensepitch/edge/FingerprintHandler.java), the
configuration of both and of the header samples in
[`FingerprintConfig`](../../src/main/java/org/sensepitch/edge/FingerprintConfig.java), and the
browser traits that the challenge script
[`script.js`](../../src/main/resources/challenge/files/script.js) reports with its answer. The
class docs and the [challenge test README](../../src/test/javascript/README.md) describe what is
sent. This document records why these signals were chosen and where the limits are.

## Purpose and scope

Fingerprinting here has one purpose: to detect a client that **falsely claims to be a browser**,
that is, one whose observable traits contradict the browser named in its `User-Agent`. A
scraper that sends a Chrome `User-Agent` over a Python TLS stack, or a headless browser driven
by automation, are the typical cases.

It is **not** for recognising or singling out a person or a device. Doing that would be
tracking without a legal basis under the GDPR (Art. 5(1)(c) data minimisation, Art. 6
lawfulness) and would read information from the terminal device beyond what the service
strictly needs (ePrivacy Directive Art. 5(3)). The scope gives these rules:

- **Collect only signals that a `User-Agent` claim can contradict.** A signal that does not help
  to check the claim is not collected, however distinctive it is.
- **A fingerprint identifies a UA variant, never a client.** A UA variant is a browser, its
  version, platform and automation state. A stable hash over traits is fine, and wanted, when
  its inputs are the same for every install of that variant. JA4 is the model: it hashes the
  TLS stack, which all users of one browser build share. To decide whether an input may go into
  such a hash, ask whether two people with the same browser build can differ in it. If they can
  (canvas rendering, installed fonts, screen size, timezone), it is per-device or per-person data
  and stays out of any hash.
- **Context signals are compared, not combined.** A timezone is a trait of the user, not of the
  browser. It is checked against other data of the same request, such as the country of the
  client address, and logged as a single value. It is never folded into a variant hash and
  never kept per client.
- **Metric labels only after sanitising to a bounded set.** The cardinality rule in
  [`AGENTS.md`](../../AGENTS.md) applies: an untrusted value is never a label as it is. A
  timezone can become a label once it is checked against the zone ids the JDK knows
  (`ZoneId.getAvailableZoneIds()`), with anything else mapped to a pseudo value such as
  `unknown_zone`. No such metric exists yet.

## Overview

| Signal | Source | Contradiction it can reveal | Variant or context |
|---|---|---|---|
| JA4 | TLS ClientHello, every TLS connection | TLS stack does not belong to the claimed browser family | variant |
| header names | every request, as the client sent them | set, order or spelling of the headers is not that of the claimed browser, e.g. a Chrome `User-Agent` without `sec-ch-ua` | variant |
| sp1 | hash of the header names and the invariant header values | the same, as one value to count and compare | variant |
| `webdriver` | `navigator.webdriver` in the challenge | `1`: the browser is under automation (WebDriver, CDP based tools) | variant |
| `tz` present or absent | `Intl.DateTimeFormat().resolvedOptions().timeZone` | absent although the claimed browser version has `Intl`: a stripped or non-browser engine | variant |
| `tz`, `tzOffset` | the same, and `Date.getTimezoneOffset()` | do not fit the country of the client address; `tz` and `tzOffset` do not fit each other | context |

The JA4 fingerprint is sent to the upstream (`Sp-Ja4`) and written to the request log
(`sensepitch.tls.client.ja4`), as is the sp1 fingerprint (`Sp-Fingerprint-Sp1`,
`sensepitch.request.fingerprint.sp1`). The header names are written to the request log on every
request (`sensepitch.request.header_names`), all headers of a sample of requests
(`sensepitch.request.sample_headers`). The challenge traits reach the request log as part
of the `url.query` of the answer request `/.sensepitch.challenge.complete`.

The country of the client address is a weak reference: VPNs, corporate proxies and travel
produce legitimate mismatches. A headless browser in a data centre often runs with `UTC`, which
is the more useful case. This is inferred from common bot detection practice, not measured on
our traffic.

## Design decisions

### Traits in the answer query, no new log field

The challenge answer already carries client telemetry (`completedAt`, `durationMs`, `method`)
that the server does not parse, and the request log records it in `url.query`. The new traits
use the same path, so the proxy needs no change: no new log field, no parser for untrusted
input. A dedicated field pays off once the server evaluates the traits.

### Observe only

The traits change no admission decision. How reliable they are, and how many legitimate
browsers would be hit (privacy extensions, unusual setups), can only be judged from request log
data first. Enforcing early would put a challenge loop in front of real users.

### Fingerprints identify UA variants, not clients

This is the rule from [Purpose and scope](#purpose-and-scope), repeated as a decision because
it decides what future signals are allowed. JA4 is acceptable, and so would be a hash over
variant traits such as `webdriver` and the presence of `Intl`: every install of the same browser
build yields the same value, so it names a browser, not a person. A hash that included the
timezone or device traits would split users of one browser into small groups and come close to
identifying them, so it is not built.

### Header names as sent, before the edge touches them

The edge changes the request headers before the request is logged: Netty's `HttpHeaders.set`
removes a header and appends it again, so the sanitized `Host` moved to the end in lower case,
a replaced `traceparent` moved as well, and the edge's own headers were listed. Measured with
Netty 4.2.21 on 2026-11-26, a request `Host, User-Agent, traceparent, Accept` was logged as
`User-Agent, Accept, host, traceparent, X-Forwarded-Proto`. The order and the spelling are the
signal, `Host` first in this spelling is typical for browsers, so the
[`FingerprintHandler`](../../src/main/java/org/sensepitch/edge/FingerprintHandler.java)
takes the names right after the client's edge headers are stripped, before any other handler.
It keeps references to the header names, the text is not copied. The sampled headers had the same
defect and are taken there as well.

### Own header hash instead of JA4H

JA4H, the HTTP part of the JA4+ suite, hashes the header names, but its readable part has the
first four letters of `Accept-Language` and two of its parts hash the cookie names and values.
Language and cookies belong to the person, so JA4H would split the users of one browser build
into small groups, against the rule in [Purpose and scope](#purpose-and-scope). The
[`Sp1Fingerprint`](../../src/main/java/org/sensepitch/edge/Sp1Fingerprint.java) hashes:

- the header names in order and spelling, without the headers whose presence depends on the
  request or the person: `Cookie`, `Referer`, `Authorization`, `Origin`, `Range`, `If-*`,
  `Cache-Control`, `Pragma`, `Content-Length`, `Content-Type`. A reload sends `Cache-Control`,
  a revisit `If-None-Match`, neither says anything about the browser.
- the values of headers that are fixed by the browser build: `Accept`, `Accept-Encoding`,
  `sec-ch-ua`, `sec-ch-ua-mobile`, `sec-ch-ua-platform`, `Upgrade-Insecure-Requests`. The
  `User-Agent` value is the claim under test and stays out, so the fingerprint can be compared
  with it.

The hash is the truncated SHA-256 of JA4, 12 hex digits. Like JA4 it is off by default
(`fingerprint.sp1.enable`), it costs one hash per request. The header names are logged in any
case.

### The version is in the name

The inputs of `sp1` are a judgement that will change: a header turns out to vary within a
browser build, or a new client hint appears. A fingerprint value is only comparable with values
of the same algorithm, so the algorithm carries a version, and the version is part of every name:
the log field `sensepitch.request.fingerprint.sp1`, the header `Sp-Fingerprint-Sp1` and the
configuration `fingerprint.sp1`. A change makes `sp2` with names of its own, `sp1` stays as it
is. During a migration both can be switched on, so the log has both values for the same requests
and a mapping can be built. A version inside the value (`v1_…`) was considered: it keeps one
field, but a query over that field mixes the versions unless every query filters on the prefix.

### One configuration block

JA4, `sp1` and the header samples serve the same study, so they are configured together in
`fingerprint`. The sampling was a setting of each request log destination before, each
destination drew its own random numbers, so two destinations sampled different requests. Now
the [`FingerprintHandler`](../../src/main/java/org/sensepitch/edge/FingerprintHandler.java)
decides once per request, and every destination logs the sample of the same requests. The block
is read per connection (JA4) and per request (the rest), so a reload applies it; before, `ja4`
needed a restart.

### Tri-state `webdriver`

`webdriver=1` and `webdriver=0` report the boolean. The parameter is left out when the property
is not a boolean: old browsers do not have it, and some automation tools delete it to hide. An
absent value in a browser version that defines the property is therefore a signal of its own.

### Timezone name and offset

`tzOffset` works in every browser, so a timezone is known even without `Intl`. `tz` tells apart
regions that share an offset, and whether it is present at all says something about the engine.
Both together allow a consistency check: an IANA name whose offset at that moment differs from
`tzOffset` hints at a spoofed value.

## Alternatives considered

- **Canvas, WebGL, audio and font fingerprinting, screen size.** Rejected. These have high
  entropy because they vary with hardware, drivers and installation, which is exactly what makes
  them identify devices. That is out of scope and a privacy risk, and they add little to
  checking a `User-Agent` claim.
- **JA4H.** Rejected, it hashes language and cookies, see
  [Own header hash instead of JA4H](#own-header-hash-instead-of-ja4h).
- **A hash over all collected traits.** Rejected, because it would include the timezone, see
  [Fingerprints identify UA variants, not clients](#fingerprints-identify-ua-variants-not-clients).

## Limitations

- Every value the script reports can be spoofed. A careful bot sets `webdriver` to `false` and
  a timezone that matches its exit IP. These traits catch careless impersonation only. JA4 is
  harder to fake, since the TLS stack has to be replaced, but tools that mimic browser TLS
  stacks exist.
- Only the answer request carries the browser traits. Later requests of the same client do not
  carry them, and the upstream never sees them.
- JA4 needs TLS; plain HTTP requests have no fingerprint.
- The HTTP fingerprint differs per kind of request: a browser sends other headers and another
  `Accept` for a navigation than for a script, an image or a `fetch`. One browser build has a
  handful of fingerprints, not one. Which header is invariant is inferred from how Chromium and
  Firefox behave, not measured on our traffic.
- Only HTTP/1.1 is served, browsers fall back to it. With HTTP/2 the header names are lower case
  and the pseudo headers and frame settings would be the signal, see JA4H and the Akamai HTTP/2
  fingerprint.

## Planned improvements

Listed in [`TODO.md`](../../TODO.md) under "Protection, admission": passing the challenge traits
on with the admission.
