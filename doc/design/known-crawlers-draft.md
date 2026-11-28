# Crawler IP lists from the Sensepitch Known Crawlers release (draft)

**Draft, partly implemented.** The edge recognises crawlers by their User-Agent
([`DetectCrawler`](../../src/main/java/org/sensepitch/edge/DetectCrawler.java), `crawlers.tsv`) and
by their address: today from IP lists embedded in the JAR
(`src/main/resources/crawler-ips/`, read by
[`CombinedIpTraitsLookup.readCrawlerIpLists`](../../src/main/java/org/sensepitch/edge/CombinedIpTraitsLookup.java)).
This document records how the embedded lists are replaced by the release of
[sensepitch-known-crawlers](https://github.com/sensepitch/sensepitch-known-crawlers), read from a
directory. [`KnownCrawlerLists`](../../src/main/java/org/sensepitch/edge/KnownCrawlerLists.java)
reads that release; configured with `ipLookup.knownCrawlers`
([`KnownCrawlersConfig`](../../src/main/java/org/sensepitch/edge/KnownCrawlersConfig.java)) it
names the crawler of an address, the first admitted match, in today's single `crawler` trait,
and reports the first match that is not admitted in the `crawler-not-admitted` trait.
[`DetectCrawler`](../../src/main/java/org/sensepitch/edge/DetectCrawler.java) checks the address
before the User-Agent.
The work is tracked in [`TODO.md`](../../TODO.md) under "Protection, admission".

## Purpose and scope

The embedded lists go stale between edge releases, and only a few vendors are in them. The
release has many vendors, a tier per feed (how trustworthy the list is, 1 the most) and tags
(e.g. `search-engine-crawler`, `ai-training-crawler`), updated independently of the edge.

- Only the IP lists are replaced. The User-Agent list (`crawlers.tsv`, `DetectCrawlerConfig`)
  stays as it is.
- A breaking change is allowed, see "Operator-visible changes".
- The format is described in the release's README; the fixture in
  [`src/test/resources/sensepitch-known-crawlers/`](../../src/test/resources/sensepitch-known-crawlers/README.md)
  holds tier zips in that format, and its `expectations.json` is the test oracle for the
  matching.

## Design decisions

Decided on 2026-11-27, completed on 2026-11-28.

### Configuration: a directory, a tier level and excluded tags

- The directory of the zip files in the local file system. Not set: no IP crawler detection,
  one `WARNING` at startup; the User-Agent list still applies. Until the embedded lists are
  removed, not set uses them, with the `WARNING`.
- The keys are in `ipLookup.knownCrawlers`, because the IP traits, the crawler trait among them,
  are only looked up with `ipLookup` configured.
- `admittedTierLevel`, default `1`: feeds of tier 1 up to this level are admitted as crawlers.
- `excludeTypes`: tags; a feed with any excluded tag is not admitted.

### A configured but unusable directory refuses the start

A configured directory that is missing, empty or holds a broken zip, or a zip with an unknown
`meta.json` `version`, is a configuration error, like a missing certificate: the edge does not
start (decided 2026-11-28). An operator who configures the lists relies on them; starting without
them would silently send crawlers to the challenge. A reload with such a directory keeps the
previous lists and counts the rejection, as a reload does for other files.

### Not admitted is not invisible

A match of a higher tier or of an excluded feed is still recognised and reported (traits, request
log, counter); it only does not bypass the challenge.

Implemented as a step: the best match that is not admitted is the IP trait
`crawler-not-admitted=<vendor>:<list name>` in `Sp-Ip-Traits` and `sensepitch.ip_traits`, beside
the `crawler` trait of the best admitted match. Only `crawler` grants a bypass. The list of
matches below replaces both traits; the counter is a later step.

### Several matches: the best admitted match decides, all are reported

Feeds overlap. The best admitted match decides, ordered by tier, then vendor and list name, as in
`expectations.json`. The request log and the headers list every match in that order, each with
vendor, list name, tier and tags; `IpTraits` carries a list of matches instead of one
`crawlerName`.

### An IP match wins over a User-Agent match

When the User-Agent and the address both match, the bypass reason is `crawler-ip-match` (decided
2026-11-28): the address is the stronger evidence, a User-Agent can be set by anyone. Today
`DetectCrawler.allowBypass` checked the User-Agent first and recorded `crawler-agent-match`; it
now checks the address first (implemented).

### No User-Agent, no bypass

`DetectCrawler.allowBypass` returns before the address check when the request has no
`User-Agent`, so a listed crawler address without one is not admitted. Kept as intended
(decided 2026-11-28): real crawlers send a User-Agent, a request without one is suspicious. The
class doc of `DetectCrawler` says so.

### Updates by the file watcher

The directory is watched by
[`ConfigFileWatcher`](../../src/main/java/org/sensepitch/edge/ConfigFileWatcher.java): a zip
renamed into place reloads the lists. The tier is taken from `meta.json`.

### Delivery as for the IP database

The zips reach the edge the way the IP database does, see "Decision" in
[ip-database-volume-draft.md](ip-database-volume-draft.md): on Kubernetes an init container and a
sidecar download the release into a shared volume, which the edge mounts read-only; standalone a
host timer. Both write a temporary file in the same directory and rename it into place.

### A crawler counter with the deciding match

A new counter `sensepitch_crawler_requests_total{ingress,vendor,tier,tag,admitted}` (decided
2026-11-28), one increment per tag of the deciding match of a request. No existing metric fits:
`sensepitch_ingress_request_flavor_count_total` is about traffic in general, and
`sensepitch_deflector_requests_total{outcome="bypassed"}` misses the matches that are not
admitted. `vendor`, `tier` and `tag` come from the loaded lists, not from the request, so the
cardinality is bounded by the release; it is to be recorded with the numbers of the current
release before the counter is added.

## Operator-visible changes

- The embedded lists are gone: without a configured directory there is no IP crawler detection.
- The new `crawler-not-admitted=` trait in `Sp-Ip-Traits` and `sensepitch.ip_traits` names the
  best match that is not admitted, until the list of matches replaces it.
- The `crawler=` trait in `Sp-Ip-Traits` and `sensepitch.ip_traits` changes from one name, e.g.
  `google:googlebot`, to the list of matches with vendor and list name.
- The request log and the headers get the matches with tier and tags; the README sections on the
  traits, `Sp-Traffic-Type` and the bypass reasons change, with a migration note.
- The bypass reason of a request that matches by User-Agent and address changes from
  `crawler-agent-match` to `crawler-ip-match`.
- The new counter `sensepitch_crawler_requests_total`.

## Limitations

- IPv4-mapped IPv6 addresses are not covered by the fixture. The edge sees IPv4 clients as IPv4
  on a dual stack listener (checked with `DualStackListenerIT`), so they do not occur.
- A crawler address without a `User-Agent` is not admitted, see above.
