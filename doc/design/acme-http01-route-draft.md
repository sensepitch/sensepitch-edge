# ACME HTTP-01 on the plain HTTP listener (draft)

**Draft, nothing decided.** This is the research for `TODO.md` item 12 of "Kubernetes, direct
node ingress", "certificates on Kubernetes". The item decided cert-manager with HTTP-01 and
named as the next step "a fixed route for `/.well-known/acme-challenge/` to the solver on the
plain HTTP listener". This draft checks that premise against how cert-manager publishes its
solver, and compares the ways a standalone edge, without the Gateway API controller parked in
[NOT-YET.md](../../NOT-YET.md), can answer the challenge. The open choices are unchecked boxes
for the maintainer.

The code statements were checked against `main` at `afabfad` on 2026-11-28. The web sources
were read on 2026-11-28; cert-manager source is quoted from tag `v1.21.3`, the latest release
(GitHub releases page: "23 October", the year is not shown).

## Summary

- A fixed route to "the solver" does not exist in cert-manager's model: every challenge gets
  its own solver pod and Service with a generated name, and a pod answers only its own token
  and domain, every other request with 404. One certificate with two names already has two
  challenges, so two pods, at the same time.
- A Service with a fixed name that selects all solver pods by their common label works only
  while exactly one challenge of the namespace is pending. Everything else needs either a list
  of the solver endpoints (a Kubernetes API watch or a headless Service tried pod by pod) or no
  solver at all.
- No solver at all is possible: the HTTP-01 answer is `token.thumbprint`, where the thumbprint
  is a hash of the ACME account's public key (RFC 8555, section 8.1). An edge that knows the
  thumbprint answers every challenge itself, on every node, without forwarding plaintext
  anywhere. acme.sh calls this "stateless mode", HAProxy's built-in ACME client answers the
  same way. This fits the port-80 pipeline best, see
  [encrypted-transport.md](encrypted-transport.md), "Port 80 is a separate pipeline".

## Facts

### What the CA requires

- RFC 8555, section 8.1: "keyAuthorization = token || '.' || base64url(Thumbprint(accountKey))";
  section 8.3: "This request MUST be sent to TCP port 80 on the HTTP server.", "Verify that the
  body of the response is a well-formed key authorization.", "The server SHOULD follow
  redirects when dereferencing the URL."
- Let's Encrypt, [challenge types](https://letsencrypt.org/docs/challenge-types/) (last updated
  2026-02-12): "Our implementation of the HTTP-01 challenge follows redirects, up to 10
  redirects deep.", "It only accepts redirects to “http:” or “https:”, and only to ports 80 or
  443.", "When redirected to an HTTPS URL, it does not validate certificates", "This challenge
  cannot be used to issue wildcard certificates.", "If you have multiple web servers, you have to
  make sure the file is available on all of them."
- Let's Encrypt validates from several network paths:
  [multi-perspective validation](https://letsencrypt.org/2020/02/19/multi-perspective-validation/)
  (2020-02-19) names "the primary path from our data center, and at least two of the three remote
  paths". With DNS spreading (item 9) the paths may resolve to different nodes, so every edge
  node must answer every pending challenge.

### How cert-manager publishes the solver

From the [HTTP01 documentation](https://cert-manager.io/docs/configuration/acme/http01/) (version
"latest", release 1.21) and the source at `v1.21.3`:

- Per challenge, cert-manager creates a solver pod (`pkg/issuer/acme/http/pod.go`,
  `GenerateName: "cm-acme-http-solver-"`) started with `--listen-port=8089`, `--domain`,
  `--token` and `--key` of that challenge, and a Service with the same `GenerateName`
  (`service.go`), port `8089`, `Selector: podLabels`, type `NodePort` unless `serviceType` is
  set: "By default, type `NodePort` will be used when you don't set HTTP01 or when you set
  `serviceType` to an empty string."
- The pod labels (`podLabels`, constants in `pkg/apis/acme/v1/types.go`):
  `acme.cert-manager.io/http-domain` and `acme.cert-manager.io/http-token`, each an Adler-32
  checksum of the domain and the token, and `acme.cert-manager.io/http01-solver: "true"`.
  `ensurePod` finds the pod of a challenge by these labels plus the owner reference, at most
  one pod per challenge. The pods live in the namespace of the challenge, i.e. of the
  Certificate.
- `podTemplate`: "The added labels and annotations will merge on top of the cert-manager
  defaults, overriding entries with the same key." "No other spec fields can be edited."
- The solver (`pkg/issuer/acme/http/solver/solver.go`) answers `/` and `/healthz` with 200,
  and otherwise 404 unless the directory of the path is the challenge path, the `Host` without
  port equals `--domain` and the last path segment equals `--token`; then 200, `Cache-Control:
  no-cache, no-store, must-revalidate` and the body `--key`. So a pod answers 404 for the token
  of another challenge.
- Publishing: with `ingressClassName` (or the legacy `class`) "cert-manager will create new
  `Ingress` resources" per challenge, `GenerateName: "cm-acme-http-solver-"`, a rule for the
  domain with the path `/.well-known/acme-challenge/<token>` (`PathTypeExact` behind the feature
  gate `ACMEHTTP01IngressPathTypeExact`, else `ImplementationSpecific`) to the solver Service on
  port 8089 (`ingress.go`). With `name`, "cert-manager will edit the named ingress resource in
  order to solve HTTP01 challenges", prepending that path to the rule of the domain. With
  `gatewayHTTPRoute`, "The Gateway API HTTPRoute HTTP-01 solver creates a temporary HTTPRoute
  using the given labels."
- Self check before it asks the CA to validate (`http.go`): `Check` requests
  `http://<domain>/.well-known/acme-challenge/<token>` from the cert-manager pod,
  `requiredPasses: 5`, each must answer 200 with a body equal to the challenge key; it sets no
  `CheckRedirect`, so redirects are followed, with the comment "The ACME spec says that a verifier
  should try on http port 80 first, but to follow any redirects which may be returned." The
  request goes through the public DNS name, so the cluster must reach its own node addresses on
  port 80 (inferred, not tested on kind).
- The challenge key: the doc comment of `ChallengeSpec.Key` (`types_challenge.go`) says "For
  HTTP01 challenges, this is the value that must be responded with", "in the format:
  `<private key JWK thumbprint>.<key from acme server for challenge>`". That order is the
  reverse of RFC 8555; that the value is the RFC key authorization, token first, is inferred
  from the RFC and the CA accepting it, not read in the code that sets it.
- The account key: "Secret resource that will be used to store the account's private key"
  (`privateKeySecretRef`, [ACME issuer](https://cert-manager.io/docs/configuration/acme/));
  "If the `disableAccountKeyGeneration` field is set, cert-manager will not create a new ACME
  account and use the existing key specified in `privateKeySecretRef`"; "You may want to reuse
  a single ACME account across multiple clusters."

### Comparable products

- acme.sh, [Stateless Mode](https://github.com/acmesh-official/acme.sh/wiki/Stateless-Mode):
  "Configure your webserver to respond statelessly to challenges for a given account key." "This
  requires nothing more than a one-time web server configuration change and no "moving parts"."
  The NGINX example answers `return 200 "$1.<thumbprint>";` for the captured token.
- HAProxy 3.2's built-in, experimental ACME client
  ([wiki](https://github.com/haproxy/wiki/wiki/ACME:--native-haproxy), last edited 2025-11-20)
  keeps the pending tokens in a virtual map (`map virt@acme`) and answers in the frontend with
  `http-request return status 200 content-type text/plain lf-string ...` for paths beginning
  with `/.well-known/acme-challenge/`, the token and the thumbprint from the map, before the
  HTTPS redirect. No forwarding to a responder.
- Ingress controllers serve the cert-manager solver by watching the Ingress objects of their
  class (general knowledge, no controller documentation read for this draft), which is the
  parked Gateway API controller's job here, see
  [2-gateway-api-scope.md](../kubernetes-support/2-gateway-api-scope.md).

### The edge today

- [`Proxy#bindPlainHttpListener`](../../src/main/java/org/sensepitch/edge/Proxy.java) builds the
  port-80 pipeline: `TrackIngressConnectionsHandler`, `HttpServerCodec`,
  [`HttpToHttpsRedirectHandler`](../../src/main/java/org/sensepitch/edge/HttpToHttpsRedirectHandler.java).
  The handler answers a known host with `308` to HTTPS, an unknown host or a request without a
  usable host with `400`, closes every connection after the answer and counts the outcome in
  `sensepitch_plain_http_requests_total`.
- So a challenge request today is redirected to `https://<host>/.well-known/acme-challenge/...`
  and enters routing like any path. There is no code for `/.well-known/acme-challenge/` in
  `src/main`.
- [encrypted-transport.md](encrypted-transport.md): "An ACME HTTP-01 responder ... belongs into
  this pipeline as well, next to the redirect, not into routing." The port-80 pipeline has no
  upstream today.

## Options

### A. Stateless answer in the port-80 pipeline

A handler in front of `HttpToHttpsRedirectHandler` answers `GET` (and `HEAD`) of
`/.well-known/acme-challenge/<token>` for a known host with 200, `text/plain` and the body
`<token>.<thumbprint>`; a token that is not base64url gets 404 (RFC 8555, section 8.3: "It
MUST NOT contain any characters outside the base64url alphabet and MUST NOT include base64
padding characters"). Everything else goes on to the redirect. cert-manager is
configured so that its solver objects serve nothing, e.g. `ingressClassName` of a class nobody
serves and `serviceType: ClusterIP`; its pods and Services still start and are removed.

- For: no forwarding of plaintext, no upstream and no Kubernetes API from the public process;
  every node answers every challenge, also concurrent ones and all CA network paths; works the
  same for a host ACME client that supports it (acme.sh); small, testable on an
  `EmbeddedChannel`; the cert-manager self check passes through the same handler.
- Against: the edge needs the thumbprint of each ACME account in use; it answers for any token,
  so any order of that account validates for the hosts the edge serves, which only the holder of
  the account's private key, cert-manager, can place (the thumbprint itself is public, every
  answer carries it). A change of account key needs a configuration change. cert-manager does
  not document this mode; it relies on its self check and the CA seeing the right body.

### B. Forward to the solver pods through one Service

A Service with a fixed name in the Certificate's namespace selects
`acme.cert-manager.io/http01-solver: "true"`; the edge forwards the challenge path to it.

- B1, a normal Service: each connection lands on a random solver pod, which answers 404 for
  the other challenges. Works only while one challenge of the namespace is pending; a
  certificate with two names fails some of the five self checks and CA paths.
- B2, a headless Service (`clusterIP: None`): the edge resolves all ready pod addresses and
  tries them until one answers 200. Works for concurrent challenges, but the port-80 pipeline
  gets an upstream, DNS resolution and a fan-out with timeouts; one Service per namespace that
  issues certificates. That a Service selects pods of its own namespace only is inferred, not
  read.

### C. Watch the solver Ingresses through the Kubernetes API

cert-manager creates an Ingress per challenge with an `ingressClassName` reserved for the edge;
the edge watches Ingresses of that class and forwards the exact host and path to the named
Service on port 8089. `name` mode (D) is the same with one Ingress edited in place.

- For: what cert-manager documents; no account knowledge in the edge.
- Against: the public process needs RBAC to list and watch Ingresses and a Kubernetes client,
  either a new dependency or a hand-written watch over the JDK `HttpClient`; it is a slice of
  the parked controller; the port-80 pipeline forwards plaintext to an upstream.

### E. Files from a directory (webroot)

The edge serves `/.well-known/acme-challenge/<token>` from a directory an ACME client writes,
like certbot's webroot. Does not fit cert-manager. On several nodes the file must be on all of
them, by shared storage or a client per node. For the standalone host setup it is an
alternative to A.

### F. DNS-01

No port-80 code at all, wildcards possible. Needs a DNS provider with an API and its
credentials in the cluster; not chosen in item 12 for that reason.

## Choices

- Mechanism:
  - [ ] A, stateless answer in the port-80 pipeline
  - [ ] B2, forward to the solver pods through a headless Service
  - [ ] C, watch the solver Ingresses of a reserved class
  - [ ] F, back to DNS-01
- Source of the thumbprint, if A:
  - [ ] configured as strings, a list for several accounts and key rotation, e.g.
    `listen.acme.accountThumbprints`
  - [ ] computed by the edge from the mounted account key Secret (RFC 7638 JWK thumbprint),
    which puts the account private key into the public process
- Unknown host on the challenge path, if A:
  - [ ] `400`, as the redirect does today
  - [ ] answer anyway (the key authorization is public)
- Counting, if A:
  - [ ] a new outcome `acme_challenge` of `sensepitch_plain_http_requests_total`
  - [ ] no separate count

## Verification plan

- Unit tests on an `EmbeddedChannel`: answer, `HEAD`, a token outside base64url, an unknown host,
  other paths still redirected, the connection closed after the answer.
- On kind: cert-manager with an in-cluster ACME test server (Pebble) and an Issuer configured as
  chosen; verify issuance with two names in one certificate, which is the concurrent case. This
  also confirms the format of the challenge key and the self check through the node address
  (inferred above, not tested).

## Notes

- The plain HTTP redirect builds its port from `listen.httpsPort`; with item 9's `hostPort` 443
  to container port 17443 the `Location` would carry `:17443`. Recorded in `IDEAS.md`, not part
  of this item.
