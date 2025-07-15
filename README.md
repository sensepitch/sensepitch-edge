
Sensepitch Edge is a reverse proxy edge server that sits in front of web applications and protects from malicious 
bots requests or hacking attempts. It is intended to replace other ingress controllers or reverse proxies like
NGINX, Openresty, Caddy, Traefik.

## Goals

- Ingress / reverse proxy for public facing web applications
- Protection from malicious robots, DoS attacks or scanners
- Detect and don't block legitimate crawlers
- Make CAPTCHAs or other protection mechanisms within the applications redundant
- Improve site delivery performance, e.g. by SSL offloading, optimized HTTP protocol handling, caching, compression
- "Waiting room": Ensure healthy applications and consistent UX for users in high load situations and provide a sensible message to users that cannot be served at the moment 
- performance level similar to proven high RPS server like NGINX
- Sensible defaults for good OOTB experience

## Non Goals

API gateway capabilities, like rate limiting and accounting are not priority.

## Architecture

Sensepitch Edge is based on Netty, the Java VM and Boring SSL.

Netty provides a solid HTTP implementation for more than a decade. On the other hand, Java was not a 
good choice for building edge servers. The problem is high startup times and unpredictable, stop the world, garbage collection
pauses of one second or more. The GC pauses lead to response time spikes but also failures because of resource limits reached
while unprocessed requests are queued up.

Meanwhile, in 2025 the Java JDK the modern GC (ZGC) solves the pause problem. The startup of Sensepitch Edge is already fast
because of its minimal dependencies. With GraalVM startup times can be reduced more. Another approach to reduce startup times 
is the OpenJDK project (called project Leyden).

In comparison to NGINX and Lua based technologies, that use per worker heaps, a global heap will reduce memory and improve performance.

## Proof of concept - phase 1

- Analyze and validate performance Netty based proxy approach
- Validate simple bypass / legitimate crawler detection capabilities
- Validate challenge is answered by user browsers
- Production experience, monitoring, possible protocol errors, etc.
- Collect legitimate clients that are not real browsers

## Roadmap / TODOs

- [x] minimal POC with transparent passthrough
- [x] expose statistics counters
- [x] simple request log, with request, response status, content length
- [x] SNI ssl configuration
- [x] bypass config for URL prefixes
- [x] redirect option if host does not match
- [x] proxy headers
- [x] pass to NGINX configuration 
- [x] host based upstream routing
- [x] admission: use server IP in token
- [x] admission: make time a parameter
- [x] make admission secret configurable
- [x] can we use Builder in lombok?
- [x] use keep alive / connection pool upstream
- [x] openssl support for HTTPS
- [ ] upstream backpressure
- [ ] block requests that are not GET and POST if not admitted or crawler bypassed
- [ ] expose allocator statistics via prometheus
- [ ] expose upstream connection pool statistics via prometheus
- [ ] POC production testing
- [ ] failure counters
- [ ] cleanup debugging code / use Netty logger
- [ ] make Netty socket options available, check connection timeout and SO_TIMEOUT
- [ ] test errors of in flight requests
- [ ] restrict admissions per solved challenge
- [ ] default configs / layer configuration / overwrite config tree
- [ ] YAML based configuration
- [ ] Enable tracing / debugging switch
- [ ] standard logging target
- [ ] Admission performance: skip validation for keep-alive connections
- [ ] Admission performance: deliver a challenge via cookie and serve static HTML
- [ ] improve default configuration
- [ ] improve bypass matching (maybe ASN, geo)
- [ ] downstream http2 support
- [ ] Expiry of granted admissions
- [ ] abnormal admission usage detection of admission
- [ ] reload configuration without closing the port
- [ ] multiple servers per upstream
- [ ] upstream health
- [ ] warmup

## Put Sensepitch Edge in front of NGINX

Assuming your existing inbound reverse proxy is NGINX, here is an example how to augment
your NGINX configuration.

## Admission configuration

### Bypass for webhook calls

Example Stipe
- post uri: POST /module/stripe_official/webhook
- user agent: Stripe/1.0 (+https://stripe.com/docs/webhooks)
- IP: 54.187.174.169

Example Paypal
- post uri: POST /module/paypal/webhookhandler
- user agent: PayPal/AUHR-1.0-1
- IP: 173.0.81.140


## Local testing

````
iptables -t nat -A OUTPUT -o lo -p tcp --dport 80  -j REDIRECT --to-port 7080
iptables -t nat -A OUTPUT -o lo -p tcp --dport 443 -j REDIRECT --to-port 7443
````

