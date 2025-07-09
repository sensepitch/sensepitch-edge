
Sensepitch Edge is a reverse proxy edge server that sits in front of web
applications and protects those from malicious bots requests or hacking
attempts. It is intended to replace other ingress controllers or reverse proxies like
nginx, openresty, caddy, traefik.

## Goals

- Ingress / reverse proxy for public web applications or static web sites
- Protects up stream resources from malicious robots, DoS attacks or hacking scanners
- Don't block out legitimate crawlers
- Make CAPTCHAs within the applications unneccessary
- Improve site performance, e.g. with caching or better compression
- Minimize needed configuration and have sensible defaults.

## Non Goals

No API gateway: Sensepitch Edge is not intended as an API gateway that has rate limiting and accounting
capabilities. There are many good products that do this.

Security enforcement: The main goal is to protect server resources. As part of this, automated and 
non-regular requests might be detected and blocked. However, it should act as transparently as possible
as soon as certain checks are passed. Capabilities like web application firewall or a security enforcement point
(meaning logins) are low priority.

## Proof of concept - phase 1

- Analyze and validate performanc of doing a reverse proxy with Netty
- Validate simple bypass capailities
- Validate challenge is answered by user browsers
- Production expierience, monitoring, possible protocol errors, etc.

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
- [ ] admission: use server IP in token
- [ ] admission: make time a parameter
- [ ] make admission secret configurable
- [ ] POC production testing
- [ ] can we use Builder in lombok?
- [ ] restrict admissions per solved challenge
- [ ] default configs / layer configuration / overwrite config tree
- [ ] yaml based configuration
- [ ] Enable tracing / debugging switch
- [ ] standard logging target
- [ ] Admission performance: skip validation for keep-alive connections
- [ ] Admission performance: deliver a challenge via cookie and serve static HTML
- [ ] make secret configurable
- [ ] default configuration
- [ ] improve bypass matching (maybe ASN, geo)
- [ ] upstream http keep alive connections
- [ ] openssl support for HTTPS
- [ ] downstream http2 support
- [ ] Expiry of granted admissions
- [ ] abnormal admission usage detection of admission
- [ ] reload configuration without closing the port
- [ ] use keep alive upstream
- [ ] multiple servers per upstream

## Put Sensepitch Edge in front of NGINX

Assuming your existing inbound reverse proxy is NGINX, here is an example how to augment
your NGINX configuration.


## Bypass for webhook calls

Example Stipe (timestamp: 29/Jun/2025:21:12:39 +0200):
- post uri: POST /module/stripe_official/webhook
- user agent: Stripe/1.0 (+https://stripe.com/docs/webhooks)
- IP: 54.187.174.169

Example Paypal (timestamp: )
- post uri: POST /module/paypal/webhookhandler
- user agent: PayPal/AUHR-1.0-1
- IP: 173.0.81.140


## Local testing

````
iptables -t nat -A OUTPUT -o lo -p tcp --dport 80  -j REDIRECT --to-port 7080
iptables -t nat -A OUTPUT -o lo -p tcp --dport 443 -j REDIRECT --to-port 7443
````