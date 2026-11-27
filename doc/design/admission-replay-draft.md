# Admission replay protection (draft)

**Draft, all policy choices decided (2026-11-27), not yet implemented.** This is the
research for the `TODO.md` entry "restrict admissions per solved challenge". It covers
[`TimeBasedChallenge`](../../src/main/java/org/sensepitch/edge/TimeBasedChallenge.java),
[`ChallengeGenerationAndVerification`](../../src/main/java/org/sensepitch/edge/ChallengeGenerationAndVerification.java),
[`Deflector`](../../src/main/java/org/sensepitch/edge/Deflector.java) and
[`DefaultAdmissionTokenGenerator`](../../src/main/java/org/sensepitch/edge/DefaultAdmissionTokenGenerator.java).
It records the replay threat, compares enforcement and coordination options, and names the policy
choices; the maintainer's decisions are in "Decisions" below.

The flow and code references were checked against `main` at `af435f6` on 2026-11-20 and
rechecked at `c59c848` on 2026-11-24; the commits in between do not change the replay facts. The
project is also described in [Proof-of-work admission](pow-admission.md); that document stays the
description of the current behaviour.

## Purpose and scope

The deflector asks an unadmitted browser to solve a SHA-256 proof-of-work search and gives it an
admission cookie after a correct answer. The admission is a bearer cookie: it raises the cost of
clients that do not keep a cookie, but it does not identify a person or enforce application
security. The project goals say that CAPTCHAs in the applications should become redundant and that
the edge is not an access-control or rate-limiting gateway. The design must therefore stay cheap
on the request path and must not pretend to be an identity system.

The problem in this draft is narrower: one solved challenge currently yields an unlimited number
of admissions, and a copied admission yields unlimited requests until it expires. The questions
are whether an answer may be used once, what "once" means across replicas, how long the evidence
must be retained, and what coordination is acceptable.

As long as an admission is an unlimited bearer cookie, many distinct admissions from one solve
are worth no more to an attacker than one admission copied to many clients. Limiting a solve to
one admission therefore mainly pays off together with per-token accounting, such as the
abnormal-use detection in `TODO.md`: only when one token is one unit of observation does a replay
stop multiplying the units. This bounds what any option below can achieve on its own.

Out of scope:

- abnormal-use detection, retention of per-token request streams and alerting; that is a separate
  `TODO.md` entry;
- CAPTCHA, fingerprinting or reputation services;
- protection against a compromised admission secret, browser or TLS endpoint;
- application authorization after the edge has admitted a request.

## Overview: what the edge does today

### Challenge and answer

`TimeBasedChallenge` returns the current wall-clock millisecond value in base 23, without a
signature or random component. Verification parses the value and accepts it when the absolute
difference from server time is less than five minutes, including a timestamp that lies in the
future within that window. The implementation comment says the time check is meant to avoid a
static pre-recorded result.

`ChallengeGenerationAndVerification` then hashes `challenge + nonce` with SHA-256 and checks the
configured hexadecimal prefix. A correct answer only proves that someone found a solution for a
recent-looking timestamp. The page issues a `sensepitch-ch` cookie, but the answer endpoint does
not compare the submitted challenge with that cookie, and no server-side record of issued
challenges exists. The challenge cookie is HttpOnly, Secure and host-only, with the same 30-day
`Max-Age` as the admission cookie.

The browser submits the answer as a GET to `/.sensepitch.challenge.complete` with `challenge` and
`nonce` query parameters. `DeflectorHandler` handles that path locally before any admission. A
correct answer calls `tokenGenerator.newAdmission()` and sets a new `sensepitch-tk` cookie. The
token generator does not receive the challenge or the client identity, and the answer endpoint
keeps no "already answered" state.

### Admission token

The token is a 30-character string: one generator prefix, six time characters from a fixed epoch,
17 characters of a mixed identifier and six checksum characters. The mixed identifier contains
the issuing server's IPv4 address, thread, sequence and random bytes. The checksum is the first
four bytes of SHA-256 over the identifier and the configured secret. `checkAdmission` verifies the
checksum and returns the creation time. The deflector then rejects a token older than
`admissionMaxAgeMillis` (five days by default) or too far in the future. A valid token is accepted
on every request with no request budget or use count.

### Exposure

The following facts follow directly from the code:

- The challenge is only a timestamp and is not signed. A client can choose a timestamp in the
  accepted window; the server cannot tell a challenge it issued from one the client invented.
- The answer endpoint does not bind the answer to the challenge cookie, host, site or node. The
  same `challenge`/`nonce` pair is accepted wherever the hash prefix and clock window match.
- Every accepted answer calls `newAdmission()` with fresh random and sequence bits. One solved
  challenge can be submitted repeatedly during its validity window and each submission creates
  another distinct admission.
- Nothing records a consumed challenge. A replay is indistinguishable from a first correct
  answer; there is no replay counter or rejection path.
- An admission is checked only for checksum and age. A copied cookie can be used by any number of
  clients and requests until it expires.
- The challenge cookie carries no server-side record. The cookie cannot prove that a particular
  browser answered a particular challenge.

A challenge issued now remains acceptable for about five minutes. Within that window a client can
issue parallel requests and collect many admissions from one proof of work. Each resulting token
then lasts up to five days. A client can also submit a challenge that it constructed itself,
including one for a timestamp up to five minutes in the future, which stretches the usable period
of that value from construction to roughly ten minutes.

This is not a key-forgery problem: the admission checksum still protects against random token
forging as well as its 32-bit size allows. It is a lifecycle problem: the server cannot tell one
answer from the next, and it cannot tell a copied admission from the original user.

## Threat model

The assumed attacker is remote and unauthenticated. It can

- fetch challenge pages and inspect the JavaScript and cookies;
- solve a proof of work once, or solve many challenges in parallel;
- copy a challenge, an answer or an admission cookie between clients and hosts;
- submit requests in parallel and repeat requests;
- choose its own challenge values if the server accepts them.

The attacker cannot read the configured secrets, break HMAC-SHA-256 or SHA-256, or compromise a
server. The attacker may run many source addresses, so binding to one client address would also
break legitimate mobile and NAT users without stopping a distributed attacker.

The security goal for this item is: one solved challenge should not create more than one usable
admission, and a replay should not multiply the admissions or the work saved. A copied admission
is still a bearer credential; limiting its reuse is a separate policy question.

## Design options

### Common prerequisite: a signed, random challenge

A random challenge identifier and a server authentication tag fix the "client invents the
challenge" and "two users receive the same timestamp" problems. A signed challenge also gives the
answer endpoint values that can be covered by a deterministic token derivation and by replay
accounting.

A possible wire format, for discussion only:

```text
v1.<payload-base64url>.<tag-base64url>
payload: issued-at millis | 16 random bytes | generator prefix | difficulty
tag:     HMAC-SHA-256(key, label | version | payload), truncated to 16 bytes
```

The challenge stays a string that the browser hashes with the solution nonce, so the JavaScript
does not need a new proof-of-work algorithm. The proposal has these properties:

- The random 16-byte identifier makes challenges unique even when many are issued in one
  millisecond. This matters for any consumed-challenge set: a timestamp-only identifier would
  make concurrent users collide and only one of them could answer.
- The HMAC covers the version, issue time, issuing generator prefix and difficulty. A challenge
  cannot be reused after a configuration change or given a different difficulty by the client.
- The challenge is deliberately not bound to the host (decided 2026-11-24). A challenge, and the
  admission derived from it, is valid on every site that shares the generator secret, so an
  admission can be accepted across domains without a new proof of work. The generator secret is
  the scope: sites that must stay separate get their own generator, which `ProtectionConfig`
  already allows per site. Because sites sharing a generator may configure different
  difficulties, the verifier rejects a challenge whose signed difficulty is below the difficulty
  configured for the site that receives the answer; otherwise a solve on a cheap site would admit
  to an expensive one.
- A 16-byte tag is far stronger than the four-byte admission checksum. The challenge tag protects
  the server's choice of identifier and bindings; it is not the proof of work.
- The key should be domain-separated from the admission token and history keys, for example by
  deriving it from the generator secret with a distinct label, as `signHistory` already does for
  `admission0`. Verification should use a constant-time comparison.
- A challenge key has to remain available for at least the challenge lifetime plus clock skew,
  not for the five-day admission lifetime. The existing `tokenGenerators` list already provides a
  prefix and rotation mechanism; reusing it avoids a second secret format, but a separate
  `challengeKeys` list is also possible.
- Binding to the client IP or User-Agent is not recommended. Both are copyable or unstable; NAT
  and mobile networks make an IP-bound admission unreliable, and an attacker can copy the
  header values. The issuing generator is the meaningful binding; the client identity is not
  available without a stronger authentication mechanism.
- Comparing the submitted challenge with the `sensepitch-ch` cookie is a useful consistency check
  but not a replay defence: a client that can copy one can copy the other. The signed identifier
  and the single-use rule are what matter.
- The proof-of-work input must be unambiguous. The browser hashes `challenge + nonce` without a
  delimiter, so characters can be moved from the nonce into the challenge or back while the hash
  stays the same. ALTCHA had exactly this flaw (CVE-2025-68113, see "Implementations
  investigated"): digits moved from the solution number into the salt extended the signed expiry
  without new work. In the format above the tag ends the challenge, so a moved character breaks
  the tag; this holds only if the verifier checks the complete submitted challenge string against
  the tag and does not accept a challenge that merely starts with a valid signed prefix. Fields
  inside the signed payload need fixed lengths or delimiters for the same reason.

The challenge cookie's `Max-Age` should probably follow the challenge lifetime rather than the
current 30 days. That is a cookie-hygiene choice, not a substitute for verification.

### Option A: idempotent admission, no state

Derive the admission deterministically from the challenge instead of generating a new random
admission on every answer:

```text
mixed = HMAC-SHA-256(admission-secret, label | generator-prefix | challenge-id)[0..12]
token time = challenge issued-at (second granularity)
token prefix = generator prefix selected at challenge issue
```

The token checksum and expiry checks stay as they are, or the token format can be versioned to use
a full HMAC tag. A replay of the same signed challenge then computes the same mixed identifier,
the same time and therefore the same token. The answer endpoint can return 200 with the same
cookie, which is idempotent, or reject the second answer if a local consumed set (Option B) is
also installed.

Advantages:

- one solved challenge yields one token by construction, on every node, without shared state;
- replay and a lost first response are harmless: the browser receives the same admission again;
- no memory per challenge, no cleanup, no coordination and no new failure mode on the answer path;
- it fits the current multi-replica statement that replicas share secrets and no request state.

Costs and limits:

- it does **not** stop a client from copying that one admission and using it in many places;
- it does not reject replay, so it cannot report a replay attempt unless additional state is
  added; `answer_accepted` alone would hide it;
- the token format and the answer path need a versioned change; the time in the token should be
  the challenge issue time, not the replay time, otherwise a replay produces a different token;
- a token generated under an old generator prefix remains valid only while that generator remains
  in `tokenGenerators`. Removing a generator during the challenge window invalidates outstanding
  answers; that is an acceptable explicit configuration change, but must be documented;
- it departs from common practice. Of the challenge products read for this draft, all except
  go-away reject a second use of a solution with server-side state; go-away is stateless and
  deterministic like Option A. The departure is deliberate, because the edge has no shared store
  and puts availability first, but it should be stated as such.

The token is not site-bound, like today's token, which has no host field. Without the host in
the derivation, the same challenge yields the same token on every site that shares the generator,
so answering it on a second site does not create a second admission. Use across those sites is
intended; separation between sites comes from separate generator secrets.

### Option B: node-local consumed-challenge set

Store each accepted challenge identifier until it can no longer be submitted. The check is one
`putIfAbsent` on the answer path; a second answer sees the entry and is rejected. The record can
be a small map from challenge identifier to expiry, or a time-ordered ring with a sweep. The
retention time is the challenge acceptance window plus the allowed clock skew, not the admission
lifetime.

Advantages:

- strict rejection on one node, with a low-cardinality replay outcome that can be counted and
  logged;
- no network dependency and no new configuration service;
- can be combined with Option A, so a replica that did not see the first answer still produces
  the same token rather than a second distinct one.

Costs and risks:

- memory grows with the rate of accepted answers times the retention time. With a signed
  challenge an entry is written only for an answer with a valid tag and a valid proof of work, so
  fetching challenge pages costs no memory; only solving does. Anubis, by contrast, stores every
  challenge when it is issued, so its store grows with page fetches. Using an illustrative 48
  bytes per entry, 300 seconds and an assumed 1,000 accepted answers per second gives about
  14.4 MB; 10,000 per second gives about 144 MB. These numbers are assumptions, not measurements,
  and the map's real overhead depends on the implementation.
- an attacker who solves many challenges can still grow the set, at the cost of the work; the
  size must be bounded. A hard cap, eviction and a metric for rejected inserts turn the cap into a
  new denial path. How cheap a solve is depends on the configured difficulty.
- with `N` replicas each node accepts the first answer it sees, so a replay can still produce up
  to `N` admissions unless Option A makes those admissions identical. A process restart forgets
  the set. These are not violations of a node-local rule, but they are not cluster-wide
  single-use.
- a strict second-answer response breaks browsers that retry after a lost response. The idempotent
  response of Option A is friendlier; the strict rejection is only worth its cost if replay
  visibility or a single-use policy is required.

A probabilistic structure such as a Bloom filter would reduce memory, but false positives would
reject valid first answers. For an admission mechanism whose purpose is to let legitimate browsers
through, that is a poor trade. Exact identifiers or no set are cleaner.

### Option C: cluster-wide consumed-challenge store

A shared store such as Redis could do one `SET key value NX PX retention` per answer and return the
same token or a rejection on the second answer. This is the only option that enforces strict
single-use across replicas and restarts.

Advantages:

- one admission per challenge cluster-wide, independent of which node answers or how many
  replicas exist;
- the retention and replay decision have one authoritative place.

Costs and risks:

- the project has no shared store today: configuration is local YAML or environment state and the
  edge is deliberately stateless across replicas. Adding Redis adds a deployment dependency,
  credentials, network policy, monitoring and a new way for admission to fail.
- only answers need the store, not every admitted request. The normal hot path stays local, which
  limits the latency impact, but a store outage still blocks new admissions.
- failure behaviour is a policy choice. Fail-open preserves availability but loses single-use
  during the outage; fail-closed locks out every unadmitted browser when the store is down.
  Option A can be the degraded mode in either case, but then a cluster-wide strict policy is not
  actually guaranteed during an outage.
- store TTLs must cover the challenge window plus any verification skew. Clock differences
  between the edge and the store become another correctness input.
- a compromise of the store can suppress answers or leak challenge identifiers; it must not hold
  the admission secrets.
- the consume step must be one atomic store operation such as `SET NX`. A read, a check and a
  separate write are not enough: Anubis guards its double-spend check with a process-local lock
  and then writes with a plain `SET`, so two replicas sharing its valkey store can both accept the
  same answer concurrently.

Option C is the right shape if the maintainer requires strict cluster-wide rejection. It is not
needed merely to prevent one challenge from producing many distinct tokens; Option A does that
without coordination.

### Option D: node-local set with asynchronous propagation

Cloudflare Turnstile shows a middle ground between B and C: its tokens are single-use
network-wide, but the redemption state spreads asynchronously. Its stated objective is to reject
at least 99% of repeats made five or more seconds after the first success, and an
`idempotency_key` lets a client retry the same validation safely. Transferred to the edge, each
node would keep the Option B set and broadcast consumed identifiers to its peers, without a store
on the answer path.

Advantages:

- replay is rejected cluster-wide after a short propagation delay, with no store that can block
  new admissions;
- combined with Option A, a replay inside the propagation window still yields the same token, so
  the window costs nothing in distinct admissions.

Costs and risks:

- the edge has no peer discovery or messaging between replicas today; this adds a protocol,
  membership handling and its failure modes, which is close to the cost of a store;
- the guarantee is probabilistic and time-bounded, not strict; a partitioned node accepts replays;
- the memory cost of Option B on every node, since every node holds every consumed identifier.

Option D is listed for completeness. It is only worth its cost if replay rejection across replicas
is required and a store is not acceptable.

### Admission reuse limits

Single-use of a challenge is independent of how often the resulting admission may be used. Three
policies are possible:

- **Unlimited requests until expiry.** This is today's behaviour. Option A removes the
  multiplication from replay, but a copied admission remains usable by anyone until the
  five-day maximum age.
- **A request budget per admission.** The server would need mutable state keyed by the token
  (or a token shard) for the admission lifetime. A node-local map has the memory and eviction
  costs of Option B, but over a five-day window and with a lookup on every request. A cluster-wide
  counter would put a store on the hot path and fail the availability goal. A signed cookie
  cannot carry a decrementing counter that the client cannot reset or replay.
- **Bind to a client property.** IP, IP prefix or ASN binding needs no per-token state but breaks
  mobile, CGNAT and shared-network users. It limits sharing across networks, not repeated use from
  the same network, and the property can change during the token lifetime. It also conflicts with
  the project's goal of not being an access-control gateway.

A middle ground is to limit the admission token's lifetime or re-challenge periodically; that
does not limit concurrent sharing but reduces the value of a leaked token. The existing
`admissionMaxAgeMillis` already gives this control. It is a policy choice, not a replay fix.

The `admission0` history cookie is not a quota: it is signed but client-controlled, and a client
can discard it, replay an older one, or reset the count by never presenting it. It can support
telemetry, not enforcement.

### Per-node versus cluster-wide

| Property | A, idempotent | B, node-local set | C, shared store | D, propagated set |
| --- | --- | --- | --- | --- |
| Distinct tokens from one challenge | one everywhere | up to one per node | one | up to one per node in the window |
| State on the answer path | none | per node, bounded by answer rate | one remote operation | per node, all answers |
| State on normal admitted requests | none | none | none | none |
| Replica restart | no effect | forgets consumed answers | retained by the store | refilled by peers only if designed |
| New infrastructure | none | none | Redis-like service and policy | peer messaging |
| Replay rejection/visibility | none | local | cluster-wide | cluster-wide after a delay |
| Failure of coordination | none | none | affects new answers | replays pass during a partition |

The "distinct tokens" row assumes B, C and D without Option A; combined with A, every column
yields one token.

## Recommendation for approval, not a decision

The draft recommends Option A as the first increment, with the signed, random challenge and a
versioned deterministic token. It meets "one admission per solved challenge"
across replicas without adding state or a failure mode to an availability-focused proxy, and it
keeps the existing stateless architecture. The price is that replay is idempotent rather than
rejected, and a copied admission still has unlimited reuse until expiry. This deliberately
differs from the products in "Implementations investigated", which mostly reject replays with
server-side state.

Option B should be added only if replay rejection or replay telemetry is a product requirement.
Option C should be deferred until a strict cluster-wide policy and a store are explicitly wanted;
Option D only if cluster-wide rejection is wanted and a store is not.
A per-token request budget should not be added speculatively: it puts state on the hottest path
and its memory cost is driven by five-day token retention.

The maintainer has since decided all choices, see "Decisions".

## Decisions

### Stateless idempotent token (Option A)

Decided 2026-11-24. One solve yields one token on every replica without shared state or a new
failure mode on the answer path, see "Option A".

### No site binding

Decided 2026-11-24. A challenge and its admission are valid on every site sharing the generator
secret, so an admission can be accepted across domains without a new proof of work; separation
comes from separate generator secrets. How the admission reaches another domain is a separate
`TODO.md` entry, see "Limitations".

### A replay succeeds idempotently

Decided 2026-11-27. A replayed answer gets 200 and the same token. No consumed-challenge set and
no replay metric: a browser retrying after a lost response keeps working, and Option B would only
give node-local visibility at the cost of state and broken retries. Consequence: replays are not
visible in the metrics, see "Limitations".

### Unlimited use until expiry

Decided 2026-11-27. An admission is a bearer credential valid until `admissionMaxAgeMillis`, as
today. A per-token budget would put five-day state on the hottest path, see "Admission reuse
limits"; none of the investigated products limits cookie use. Abnormal use is the separate
`TODO.md` entry.

### Five minutes lifetime, 30 seconds skew, rotation through `tokenGenerators`

Decided 2026-11-27. A challenge is accepted for five minutes after its signed issue time, the
current window, and at most 30 seconds in the future, instead of today's five minutes, so a
client can no longer stretch a value to ten minutes. The challenge key is derived from the
generator secret with its own label; rotation reuses the `tokenGenerators` list, so no second
secret format is needed. A generator must stay configured for at least lifetime plus skew after
it stopped issuing, otherwise outstanding answers fail. The `sensepitch-ch` cookie's `Max-Age`
follows the challenge lifetime instead of 30 days.

### Hard cut for unsigned challenges, old tokens expire naturally

Decided 2026-11-27. Unsigned challenges are rejected from the upgrade on: the cost is one
re-solve for a page loaded before the upgrade, and accepting them longer would keep the replay
hole open. Admission tokens of the old format stay valid until they expire, told apart by a
version prefix, so no visitor loses an admission at the rollout.

## Observability and verification plan

If Option A is approved, the implementation should keep the existing `answer_accepted` and
`answer_rejected` outcomes and add counters or bounded outcomes for the new rejection reasons:
bad signature, too low difficulty, unsupported version, expired challenge, unknown generator and, if
Option B exists, replay. Response statuses should distinguish an invalid answer (400) from a
temporary configuration problem in the same way the current endpoint does; the exact status is a
design choice. A replay that returns the same token is still an accepted answer and must not be
logged at request level above `DEBUG` without a count.

The tests would need to cover, with a fake clock and no real timers:

- the same signed challenge and nonce submitted twice produces the same token under Option A;
- a challenge with a tampered tag, a changed difficulty, a difficulty below the site's, or
  signed by a generator that is not configured is rejected;
- a challenge solved on one site is accepted on another site with the same generator and
  difficulty, and yields the same token;
- characters moved between `challenge` and `nonce`, in both directions, are rejected even though
  the proof-of-work hash is unchanged;
- an expired challenge is rejected and no token is issued;
- two different challenges produce different tokens, including two challenges issued in the same
  millisecond;
- old-format challenges and old admission tokens follow the chosen migration policy;
- with Option B, the second answer is rejected, the entry expires at the retention boundary, and
  a concurrent pair has exactly one winner;
- with Option C, a store error follows the chosen fail-open or fail-closed policy and is counted;
- key rotation: an answer issued under the previous generator still works while that generator is
  configured, and stops working when it is removed.

The existing `ChallengeTest`, `DeflectorHandlerTest`, `AdmissionRevisitTest` and
`DeflectorAdmissionExpiryTest` are the starting points. No implementation or test is part of this
research turn.

## Implementations investigated

Proof-of-work and challenge products were read on 2026-11-24; the local replay-prevention
libraries on 2026-11-20. "Read" below means the source code; "docs" means the vendor's
documentation only, so the statement is the vendor's claim, not verified in code.

| Project | Challenge | Replay of a solution | Admission after the solve |
| --- | --- | --- | --- |
| Anubis | random, stored at issue | rejected, `Spent` flag in a store | signed JWT, bearer, optional IP binding |
| ALTCHA | HMAC-signed salt with expiry | rejected by a registry the integrator keeps | up to the integrator |
| Friendly Captcha | per visitor, vendor service | rejected centrally, `response_duplicate` | up to the integrator |
| Cloudflare Turnstile | vendor service | rejected network-wide, eventually consistent | up to the integrator |
| mCaptcha | short-lived config, cached | rejected, nonce cache (Redis backend) | up to the integrator |
| go-away | derived from client data, no state | same result again, idempotent | bound to the client data |
| haproxy-protection | optional IP binding | not investigated | cookie with a server-side time bucket |

All products except go-away reject a second use with server-side state; go-away is the only
stateless and deterministic design, the shape of Option A. None limits how often an admission
cookie may be used: where the product issues one, it is a bearer credential with an expiry and
optional client binding. This supports the draft's advice against a per-token request budget.

### Anubis

[TecharoHQ/anubis](https://github.com/TecharoHQ/anubis), `lib/anubis.go` at `main` `7903b22`
(2026-11-23), read. `issueChallenge` creates a UUIDv7 identifier and 64 random bytes and stores
the challenge with a 30-minute TTL when the page is served. `PassChallenge` takes one of 256
process-local mutexes selected by the identifier hash, loads the challenge, rejects it as
`double_spend` when `Spent` is set, validates the proof of work and the policy rule, the method,
the difficulty and the extensions recorded at issue, then stores it with `Spent = true` and signs
a JWT with the challenge identifier, method and policy rule hash. The JWT is verified by
signature and expiry only; `--jwt-restriction-header` optionally binds it to the hash of one
request header, e.g. the client IP.

The store is pluggable (`lib/store`: memory, bbolt, valkey, s3api). The valkey store's `Set` is a
plain `SET` with expiry, without `NX`, and the lock is per process, so with several replicas
sharing valkey the check and the write are not atomic across replicas. This is inferred from the
code, not tested. Conclusions used above: strict rejection needs state; storing at issue makes
the store grow with page fetches; a shared store needs an atomic consume operation (Option C).

### ALTCHA

[Security recommendations](https://altcha.org/docs/integration/security-recommendations/), docs.
The challenge is a random salt with an optional `expires` parameter, signed with HMAC by the
server. The docs require the server to make each challenge single-use by keeping a registry of
solved challenges for at least the expiry time (20 minutes to one hour recommended); the hosted
Sentinel product has this built in.

[CVE-2025-68113](https://osv.dev/vulnerability/CVE-2025-68113): the HMAC did not separate the salt
from the solution number, so an attacker could move digits between them and change the signed
expiry while the proof-of-work hash stayed valid. Fixed by a delimiter after the salt (JS library
1.4.1, Sentinel 1.16.0). This is the reason for the unambiguous-input requirement in "Common
prerequisite", since this project also hashes `challenge + nonce` without a delimiter.

### Friendly Captcha

[Verify the response](https://developer.friendlycaptcha.com/docs/v2/getting-started/verify),
docs. Replay is rejected by the vendor's central verification service, which reports
`response_duplicate` (v2) or `timeout-or-duplicate` (v1). The docs advise treating a failed
verification leniently, since it can have benign causes. Not comparable in topology, because the
state lives in one vendor service.

### Cloudflare Turnstile

[Server-side validation](https://developers.cloudflare.com/turnstile/get-started/server-side-validation/),
docs. A token is valid for 300 seconds and for one validation; a repeat fails with
`timeout-or-duplicate`. The redemption state spreads through Cloudflare's network with a delay:
the stated objective is to reject at least 99% of repeats made five or more seconds after the
first success. An `idempotency_key` sent with the first validation lets the integrator retry the
same validation safely. This is the model for Option D and shows that a large operator accepts a
short, bounded window instead of strict global single use.

### mCaptcha

[mCaptcha README](https://github.com/mCaptcha/mcaptcha) and
[libmcaptcha 0.2.4 `cache`](https://docs.rs/crate/libmcaptcha/latest/source/src/cache/mod.rs),
docs and module documentation. A proof-of-work configuration lives 30 seconds and can be used
once; the cache keeps proof-of-work details and nonces to prevent replay and has a Redis backend.
The Redis key scheme was not read.

### go-away

[go-away](https://git.gammaspectra.live/git/go-away), `lib/challenge/key.go` on `master`, read.
The challenge key is SHA-256 over a label, the challenge name, the client's network prefix,
selected request headers, an expiry time bucket and the private key fingerprint, separated by
NUL bytes. No state is kept; the same client in the same bucket gets the same key, so a replay
from the same network yields the same result, and one from another network prefix fails. It
shows two things: a stateless, deterministic design is used in practice (Option A), and binding
to a network prefix instead of an exact address is a middle ground, see "Alternatives
considered". How the cookie is verified against the key was not traced.

### haproxy-protection

[fatchan/haproxy-protection](https://github.com/fatchan/haproxy-protection) and the
[original README](https://git.cavemanon.xyz/Cavemanon/haproxy-protection/src/commit/c03a16214bed12af1d3c343a5a771c032d0a7ed8/README.MD),
README only. `CHALLENGE_INCLUDES_IP` locks a solved challenge to the IP address or Tor circuit;
cookie expiry is a server-side time bucket inside the cookie hash rather than a client-side
expiry. Replay handling of a solution was not investigated.

### Replay prevention libraries

Read locally on 2026-11-20, not proof of work, but the same two mechanisms. Passlib 1.9.3
(`python3-passlib` 1.9.3-1ubuntu1): the TOTP tutorial section "Preventing Token Reuse" and
`TOTP.match` (`passlib/totp.py`, lines 1126-1218) reject a counter equal to the last one used and
keep it for `period + window` seconds, i.e. for the verification window, not the credential
lifetime. OAuthlib 3.3.1 (`python3-oauthlib` 3.3.1-1build1):
`validate_timestamp_and_nonce` (`oauthlib/oauth1/rfc5849/request_validator.py`, lines 571-620)
requires a stored nonce/timestamp record to reject a repeat; `generate_nonce`
(`oauthlib/common.py`) combines a timestamp with a random number.

### This project

The code links in the introduction, the tests named above and
[Proof-of-work admission](pow-admission.md) were read. The existing
`doc/kubernetes-support/3-gap-analysis.md` states that the challenge is not signed and that
signing is a separate hardening item; that note is consistent with this draft.

### Not read

Hashcash keeps a database of spent stamps until they expire, and Privacy Pass (RFC 9576-9578)
leaves double-spend prevention of redeemed tokens to the origin. Both are recalled, not read for
this draft. RFC 6238 (TOTP), RFC 5849 (OAuth 1) and RFC 8555 (ACME nonces) were not read either.

## Alternatives considered

- **Keep the timestamp challenge and shorten the window.** A shorter window narrows replay and
  precomputation but does not stop repeated submissions inside it, and a client can still mint a
  challenge. Rejected as a complete fix; it is only a risk-reduction knob.
- **Use a random challenge without signing it.** The random value prevents same-millisecond
  collisions, but the client can still choose identifiers, precompute solutions and choose a
  future timestamp. It is not enough for a deterministic token or a consumed set. Rejected.
- **Bind the answer to the challenge cookie.** Helpful for consistency, but the cookie is not a
  secret and can be copied with the answer. It does not replace signing or single-use. Retained
  only as an optional check.
- **Bind to client IP or User-Agent.** Breaks mobile, CGNAT and shared clients, is copyable, and
  turns a protection feature into an access-control rule. Rejected.
- **Bind to a network prefix.** go-away keys its challenge on the client's network prefix plus
  selected headers, which tolerates address changes inside one network and limits copying across
  networks. It still breaks a client that moves between networks, e.g. from Wi-Fi to mobile, during
  the admission lifetime, and a distributed attacker solves once per prefix. It also needs a
  trusted client address behind other proxies. Not proposed; it is the option to revisit if
  cross-network sharing of admissions becomes a problem, and it is a separate decision from
  replay.
- **Bloom filter or approximate set.** Saves memory, but false positives deny legitimate first
  answers. Rejected for this purpose.
- **Bind the challenge and token to the host.** Prevents moving an answer or admission between
  sites, but also prevents accepting an admission across domains without a new proof of work,
  which is wanted. Separate generator secrets give the same separation where it is needed.
  Rejected.
- **Strict shared store now.** Correct for cluster-wide single-use but adds a dependency and a new
  outage mode to a proxy whose architecture has no shared request state. Deferred as Option C.
- **Store the challenge when it is issued, as Anubis does.** Needed when the challenge is not
  signed, but every challenge page then writes state, so the store grows with page fetches rather
  than with solved work. With a signed challenge, state is only needed after a correct answer.
  Rejected.
- **Make the answer a POST or require a CSRF token.** A malicious client can send a POST and copy
  a token just as easily; this changes the browser flow without solving replay. Not a fix.
- **Count admission uses in the signed cookie.** The client can replay an earlier cookie or drop
  it, so a self-contained counter cannot enforce a limit. Rejected.

## Limitations

- Option A does not prevent sharing a valid admission and does not reject or report a replay. It
  only prevents one solve from creating more than one token.
- The memory estimates for Option B are arithmetic assumptions, not measurements; a real map's
  overhead, concurrency and eviction policy must be measured before choosing a cap.
- With the hard cut for unsigned challenges, a mixed fleet during a rolling upgrade rejects
  answers to challenges issued by an old replica on a new one, and vice versa; the browser
  re-solves.
- Replays succeed silently: no metric tells a replay from a first answer.
- The HMAC tag protects challenge fields, but a compromised generator secret still allows an
  attacker to mint challenges and tokens. Key compromise is out of scope.
- The admission token keeps its existing 32-bit checksum unless the format is versioned. The
  draft mentions a stronger tag but does not make that change.
- No implementation, load test or end-to-end browser test was done. The conclusions about
  coordination and state are design reasoning checked against the code.
- Cookies stay host-only. Accepting an admission across domains needs a way to carry it to the
  other domain, e.g. a redirect that sets the cookie there or a cookie on a common parent domain.
  That mechanism is not designed here; without it, a browser still solves once per domain.
- Of the other products, only Anubis and go-away were read in code; the others are described from
  their documentation, and Hashcash and Privacy Pass from memory.

## Planned improvements

The implementation is the `TODO.md` entry "restrict admissions per solved challenge"; carrying
an admission to another domain is a separate entry. With the implementation, this draft becomes
the decision document.
