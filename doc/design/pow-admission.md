# Proof-of-work admission

The deflector asks an unadmitted browser to perform a small SHA-256 search before forwarding
requests to the upstream. A successful answer earns an admission cookie, so subsequent requests
can pass without repeating the work. This raises the cost of clients that do not retain an
admission; it does not establish a person's identity or prevent automation.

This document describes the current flow and limitations, checked against the implementation on
2026-11-14; the browser side (hashing, difficulty, cookie check) on 2026-11-25. Implementation
details belong in the JavaDoc of
[`Deflector`](../../src/main/java/org/sensepitch/edge/Deflector.java),
[`ChallengeGenerationAndVerification`](../../src/main/java/org/sensepitch/edge/ChallengeGenerationAndVerification.java)
and
[`DefaultAdmissionTokenGenerator`](../../src/main/java/org/sensepitch/edge/DefaultAdmissionTokenGenerator.java),
and in the browser script [`script.js`](../../src/main/resources/challenge/files/script.js).

## Purpose and scope

The admission mechanism raises the cost of repeated unadmitted requests while allowing a browser
to reuse an admission. The following overview records the present design and its limits.

## Overview

Protection runs before upstream forwarding. A protection-wide bypass skips both cookie gates
and the deflector; otherwise configured cookie gates run first. Within the deflector, the order
is:

1. A valid `sensepitch-tk` admission cookie within its server-side age limit passes the request,
   including methods other than GET.
2. A configured deflector bypass or accepted crawler passes without solving a challenge.
   `noBypass` suppresses this deflector bypass for matching URIs; it does not invalidate an
   admission or override the protection-wide bypass.
3. Other methods than GET receive an empty, non-cacheable 403. Request bodies are not saved for
   replay after admission.
4. Unadmitted GET requests to challenge resources, progress reporting and answer endpoints are
   handled locally. Other GET requests receive a non-cacheable 403 HTML challenge page.

The page embeds a challenge and the configured hash prefix. It searches for a nonce such that
the hexadecimal SHA-256 digest of `challenge + nonce` starts with that prefix. The default prefix
is `8888`; a four-digit hexadecimal prefix takes about 65,536 attempts on average. Every browser
uses the same synchronous JavaScript SHA-256, see
[One lean JavaScript SHA-256, no WebCrypto](#one-lean-javascript-sha-256-no-webcrypto). The
default `powMaxIterations` is 1,000,000, a browser search budget rather than a server-side
admission rule. JavaScript and cookies are needed to complete the normal browser flow. An
initially hidden tab shows a checkbox before starting; this is a browser UI condition, not a
server-verified gesture.

The browser submits the challenge and nonce by GET to `/.sensepitch.challenge.complete`. The
server checks freshness and the hash prefix. A correct answer receives 200 and a new
`sensepitch-tk` cookie; an invalid answer receives 400. After success the browser reloads the
original page, which can then pass with the cookie. Exhausted searches or failed submissions
show a message asking the user to refresh. A browser that blocks cookies gets a message instead
of a reload, see [Stored cookie checked before reload](#stored-cookie-checked-before-reload).

The page also requests assets under `/.sensepitch.challenge.files`. The script reports to
`/.sensepitch.challenge.step` with a fixed `event` value: `init-hidden` for a hidden tab, and
`no-cookie`, `max-iterations` or `answer-rejected` when a visitor gets stuck. The step requests
appear in the request log by their URI. Browser timing and method parameters are telemetry;
acceptance depends on the challenge and nonce alone.

## Design decisions

### One lean JavaScript SHA-256, no WebCrypto

The script hashes with one synchronous, allocation-free JavaScript SHA-256 in every browser. It
writes the ASCII input straight into a reused `Int32Array` of message words and compares the
prefix as numbers against the digest words, without a hex string. The goal is the fastest method
a browser offers: the closer a visitor's speed is to an attacker's on the same hardware, the more
the work costs the attacker relative to the visitor.

Measured 2026-11-25 on the development machine (AMD Ryzen AI 7 PRO 360, Linux). The real
`script.js`, former and current, ran in headless Chromium 153 (Playwright's
`chrome-headless-shell`) on a test page with a prefix that no nonce below the budget matches, the
time taken from the start to the failure message, three runs each:

| Script in the browser | Million hashes/s |
|---|---|
| Former: WebCrypto `crypto.subtle.digest`, one `await` per hash | 0.19 to 0.22 |
| Current, yielding with `setTimeout` | 1.34 to 1.38 |
| Current, yielding with a `MessageChannel` message (as shipped) | 2.9 to 3.2 |

The current script is about 15 times as fast in the browser. Single methods in a loop, one core,
Node.js v18.19.1 (V8 as in Chrome), input of about 15 ASCII bytes, ad-hoc `node -` scripts with
200,000 hashes per method (2,000,000 for the lean JavaScript), the native rate with
`openssl speed -seconds 2 -evp sha256 -bytes 16`:

| Method | Million hashes/s |
|---|---|
| WebCrypto, one `await` per hash | 0.13 |
| WebCrypto, 256 digests in flight | 0.21 to 0.23 |
| Former JavaScript fallback (several arrays allocated per hash, hex string compared) | 0.22 to 0.25 |
| Lean JavaScript, hex string compared | 2.4 |
| Lean JavaScript, prefix compared as numbers | 3.5 (3.6 in Chromium) |
| Native OpenSSL with the CPU's SHA extensions, for comparison | about 12 |

WebCrypto is made for large inputs: for 15 bytes the asynchronous call per digest costs far more
than the hash itself. The challenge is served over HTTPS only, so every modern browser had
WebCrypto and took the slowest path. The former fallback delivered each hash through
`setTimeout(…, 0)`, which browsers clamp to at least 4 ms when nested, so a browser without
WebCrypto needed about 16 s for the former 4,096 attempts. A single implementation also leaves
one code path to test. The answer's `method` telemetry is `js` for all browsers.

Not done: a Web Worker per core (the search takes about 20 ms on a laptop and well below 0.5 s on
a phone, so the extra code and browser variation do not pay yet), WebAssembly (not measured), and
hashing the constant challenge prefix once (the input fits one 64-byte block, there is nothing to
save). Safari and phones are not measured. For long inputs WebCrypto overtakes JavaScript, see
"Long input hashed with WebCrypto" under "Alternatives considered".

### Yield with a message, not a timer

The script hashes `YIELD_EVERY` (10,000) attempts at a time and then yields, so the page stays
responsive and the progress dots appear. Browsers delay a nested `setTimeout(…, 0)` by at least
4 ms; at about 3 million hashes per second a chunk takes about 3 ms, so the timer more than
doubled the time (the table above). A `MessageChannel` message is not clamped. Browsers without
`MessageChannel` (before 2013) fall back to `setTimeout`. A time-based chunk size was not chosen:
it would need the clock in the loop and make the tests depend on clock steps, and with the
message the chunk size hardly matters.

### Default prefix 8888: under 0.5 s on 2024 phones

The difficulty is chosen so a 2024 mobile phone solves the challenge in under 0.5 s. The current
script is about 15 times as fast in the browser as the former WebCrypto path; the prefix grows in
steps of 16 (one hex digit), so the proportional step is one more digit, from `888` to `8888`.

The number of attempts is geometrically distributed: the 90th percentile is 2.3 times the mean,
the 95th 3 times. The phone speed is an assumption, not measured: a budget 2024 Android phone
runs JavaScript at about 1/8 of the laptop above, about 0.375 million hashes/s of the 3.0 measured
in Chromium; a 2024 flagship about as fast as the laptop.

| Prefix | Mean attempts | Budget phone mean / p90 / p95 | Laptop mean |
|---|---|---|---|
| `888` | 4,096 | 11 / 25 / 33 ms | 1.4 ms |
| `8888` | 65,536 | 0.17 / 0.40 / 0.52 s | 22 ms |
| `88888` | 1,048,576 | 2.8 / 6.4 / 8.4 s | 0.35 s |

With `8888` the mean and 9 in 10 visitors of a budget phone stay under 0.5 s, the slowest 5 %
need a little more. Five digits would break the budget. The answers' `durationMs` in the request log shows the real
times; if phones are much slower than assumed, the default goes back for discussion. With
`8888` the default budget of 1,000,000 attempts runs out with a probability of about e^-15.
Steps finer than 16 would need a bit target instead of a hex prefix, which changes the answer
check.

### Stored cookie checked before reload

After an accepted answer the script reloads the page only if `document.cookie` shows the
`sensepitch-tk` admission cookie, which is readable because it is not HttpOnly. Without it, the
reload would get the challenge again, an endless loop without explanation for a visitor who
blocks cookies. The script shows a message and reports `no-cookie` instead, and does the same
before calculating if `navigator.cookieEnabled` is false. The cookie name reaches the script
through the `{{TOKEN_COOKIE}}` placeholder from `Deflector.TOKEN_COOKIE_NAME`.

### Stateless challenge verification

[`TimeBasedChallenge`](../../src/main/java/org/sensepitch/edge/TimeBasedChallenge.java) encodes the
server's millisecond timestamp in base 23. Verification accepts a positive timestamp whose
absolute difference from server time is less than five minutes, including future timestamps
within that window. The challenge is not signed and is not tied to a particular browser, IP
address or server. The `sensepitch-ch` cookie is issued with the page but is not checked when
verifying an answer. Verification needs no record of issued challenges or previous answers.

Admission tokens contain a generator prefix, creation time, a mixed identifier and a checksum
derived from SHA-256 over the identifier (including its time) followed by the configured secret.
The mixed identifier includes the issuing server's IPv4 address, thread and sequence information,
and random bytes. The server address contributes to token generation; verification does not
require the receiving server to have that address, and the token is not bound to the client's IP.

### Token generators support rotation

The first entry in `tokenGenerators` issues new tokens. Verification selects an entry by the
token's prefix, allowing old entries to remain while a new first entry issues tokens. Nodes that
need to accept each other's admissions must have matching verification prefixes and secrets.
Removing an entry stops acceptance of its tokens; there is no individual token revocation list.

The admission cookie is Secure, has path `/`, no explicit Domain, and a 30-day `Max-Age`. It is
readable by JavaScript because HttpOnly is not set. The server validates the token checksum and
returns its creation time, then enforces a server-side age limit, see
[Server-enforced admission expiry](#server-enforced-admission-expiry). The 30-day cookie `Max-Age`
is therefore a browser retention policy, while the server-side limit bounds how long a retained
token stays valid.

### Server-enforced admission expiry

A granted admission expires server-side. The token carries its creation time, so verification
compares that time with the current wall clock and rejects a token that is older than
`admissionMaxAgeMillis` (default 5 days) or whose creation time lies more than
`admissionClockSkewMillis` (default 5 seconds) in the future. The future allowance tolerates clock
skew between the issuing and verifying servers of a distributed fleet. Tokens are second-granular
and the comparisons are strict, so a token exactly at a boundary is still accepted. A rejected
token is handled like an absent admission: the browser is re-challenged and receives a fresh token
after solving, and the request is reported with the `challenge_sent` outcome.

### Preserve revisit history in a separate signed cookie

When an authentic expired admission triggers a challenge page, the response clears that admission
and sets the Secure, HttpOnly, host-only `admission0` cookie for 30 days at path `/`. Clearing the
expired token prevents a page reload from counting it again. The answer endpoint preserves history when
it issues a fresh admission. If the answer presents an expired admission, it records that expiry
as well.

The value is `original-token.count.tag`. The original token's generator prefix selects the secret.
HMAC-SHA-256 covers the token and decimal count with a key derived specifically for `admission0`.
A missing, unverified or retired-key history starts again at one with the currently expired token.
The count describes the signed history the client presented. A client can discard or replay an
older cookie, and concurrent requests can carry the same history.

### The request log shows only verified history

The deflector verifies an `admission0` cookie on every request that carries one, and only then,
so requests without history pay no HMAC. A verified history is attached to the request as
`Sp-Admission-Original-Token` and `Sp-Admission-Revisits`, like `Sp-Admission-Token`, and the
request log writes it as `sensepitch.protection.admission_original_token` and
`sensepitch.protection.admission_revisits` (a number). A response that sets the cookie, a
challenge page for an expired admission or an answer presenting one, logs the values it issues
instead. Admitted requests after a re-challenge thus link the fresh token to the original one.
An unverified, forged or retired-key history is not logged: otherwise a client could put any
token into the log and attribute its requests to another visitor. The legacy plain text format
keeps its columns, because its readers parse by position.

### Outcomes identify the protection path

The deflector reports `passed`, `bypassed`, `method_rejected`, `challenge_sent`, `answer_accepted`
and `answer_rejected` outcomes to metrics and the request log. Validated and newly issued tokens
are attached to the request as `Sp-Admission-Token` for logging. `Sp-Traffic-Type` labels admitted
traffic as `human`, detected crawlers as `bot`, and otherwise undetermined traffic as `unknown`.
The answer endpoint sets `human` even for a rejected answer, so use the protection outcome to
distinguish acceptance from rejection. These labels describe the protection path, not verified
human identity.

## Alternatives considered

### Long input hashed with WebCrypto

Open, not adopted yet. WebCrypto pays a fixed cost per call and then hashes in native code, so
with a long input it overtakes JavaScript and comes close to a native solver on the same core.
Measured 2026-11-25 on the development machine (AMD Ryzen AI 7 PRO 360, Linux) with an ad-hoc
test page: one reused `Uint8Array` per length, the nonce written into its first 8 bytes per
attempt, 2 s per cell, three runs, the WebCrypto digest checked against the JavaScript one for
every length. Headless Chromium 153 (`chrome-headless-shell`) and headless Firefox 159; native
with `openssl speed -seconds 1 -evp sha256 -bytes N`. MB/s of input, ranges over the runs:

| Bytes | Chromium JS | Chromium WebCrypto, 1 / 64 in flight | Firefox JS | Firefox WebCrypto, 1 / 64 in flight | Native, one core |
|---|---|---|---|---|---|
| 16 | 65 | 5 / 6.5 | 55 to 57 | 2.3 / 7 to 8 | 182 |
| 256 | 203 to 208 | 75 to 79 / 102 | 184 to 186 | 38 / 114 to 123 | 1,254 |
| 1,024 | 233 to 245 | 282 to 287 / 360 to 365 | 213 to 217 | 126 to 139 / 358 to 453 | 2,001 |
| 4,096 | 250 to 253 | 818 to 827 / 926 to 968 | 206 to 228 | 457 to 501 / 1,129 to 1,928 | 2,304 |
| 16,384 | 242 to 277 | 1,471 to 1,583 / 1,587 to 1,612 | 220 to 227 | 1,032 to 1,178 / 3,116 to 3,220 | 2,386 |
| 65,536 | 252 to 262 | 1,918 to 1,993 / 1,953 to 2,162 | 223 to 234 | 1,684 to 1,841 / 3,874 to 3,926 | 2,407 |

- JavaScript stays at about 250 MB/s whatever the length, WebCrypto overtakes it at about 1 KB.
- At 64 KB a native solver is only about 1.2 times as fast per core as Chromium and 1.3 times as
  Firefox, against 3 to 4 times for the current 15-byte input.
- Chromium ran the digests on one core also with 64 in flight (CPU time of the browser's process
  tree, sampled from `/proc`). Firefox spreads digests in flight over several cores, beyond the
  native rate of one core; an attacker uses all cores as well, so the comparison per core stands.

What it would take:

- **Nonce first.** SHA-256 hashes from left to right. With a constant challenge in front, a native
  solver hashes it once and continues from the saved state, while WebCrypto cannot, which would
  widen the gap instead. The input would be nonce, challenge and a filler the page derives from
  the challenge, so nothing long is sent.
- **A bit target.** At 64 KB a laptop core does about 30,000 attempts per second, so a phone's
  0.5 s allows a few thousand. Steps of 16 attempts per hex digit are too coarse there; the
  answer check would compare leading bits instead of a hex prefix.
- **Server cost per answer:** one verification hashes the whole input, about 8 µs at 16 KB and
  30 µs at 64 KB on the event loop, instead of well under 1 µs. Every bogus answer costs that, so
  the cheap checks (format, age) have to come first.
- **Browsers without WebCrypto** would fall back to JavaScript at about a tenth of the speed.
- **Unmeasured:** phones (their SHA instructions may change the ratio against JavaScript) and
  Safari.

The gain is against custom native solvers only: a bot that runs a headless browser pays the same
as a visitor either way, and GPUs keep their lead because the work still grows with the compute.

## Limitations

- **Replay and precomputation:** a valid challenge/nonce pair can be submitted repeatedly while
  fresh, issuing multiple admissions. Clients can construct timestamp challenges themselves,
  including timestamps slightly in the future. There is no single-use accounting or binding to
  the challenge cookie, client or site.
- **Admission reuse:** an admission can be copied and shared wherever its prefix and secret are
  accepted until it reaches the server-side maximum age (default 5 days). No per-token request
  budget or abnormal-use enforcement is implemented.
- **Checksum strength:** only four bytes of the SHA-256 digest are kept as the token checksum.
  This is a 32-bit check, not a full-length authentication tag; its forgery resistance is limited
  by that size. The current construction is a secret-suffixed hash, not HMAC.
- **Client cost varies:** the same prefix imposes different delays on different devices. Solvers
  can automate or parallelize the search, and a valid cookie avoids further PoW. The search
  budget does not guarantee that every browser will find a solution.
- **Native code stays faster:** native SHA-256 with the CPU's SHA extensions is about 3 to 4 times
  as fast per core as the browser's JavaScript (more with a tuned loop), GPUs by orders of
  magnitude. The work sets a cost, it does not prove a human. Difficulty steps are 16-fold. A
  long input hashed with WebCrypto would narrow the gap per core, see "Alternatives considered".
- **Time and fleet configuration:** challenge acceptance depends on server wall clocks and the
  receiving node's hash prefix. Admission acceptance depends on shared verification keys.
  Configuration changes can therefore affect a browser between challenge issue and submission.

## Planned improvements

Restrictions on admissions per solved challenge are researched in
[admission-replay-draft.md](admission-replay-draft.md); abnormal-use detection remains a separate
task in [TODO.md](../../TODO.md). This overview does not choose their enforcement or cluster
coordination policies.
