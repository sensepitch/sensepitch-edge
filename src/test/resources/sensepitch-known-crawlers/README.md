# Sensepitch Known Crawlers - Edge test fixture

The Sensepitch Known Crawlers project is a regular updated list of IP
ranges that are used by crawlers and bots. More information in the readme:

https://github.com/sensepitch/sensepitch-known-crawlers

This is a small, static set of feeds in the release format, for testing edge proxies
such as sensepitch edge without depending on live data. The contents only
change when the release format changes.

All prefixes come from the documentation ranges 192.0.2.0/24,
198.51.100.0/24, 203.0.113.0/24 and 2001:db8::/32, and all vendors are
prefixed `example-`, so the fixture never matches real traffic.

| Tier | Feed | Tags | Prefixes |
| --- | --- | --- | --- |
| 1 | `example-search/crawler` | search-engine-crawler, mixed-pool, seo-tool | 192.0.2.0/25, 2001:db8:1::/48 |
| 1 | `example-ai/user-fetcher` | user-triggered-fetcher, ai-search-crawler | 192.0.2.64/26, 192.0.2.200/32, 2001:db8:1:5::1/128 |
| 2 | `example-seo/audit` | seo-tool | 198.51.100.0/24 |
| 3 | `example-cdn/edge` | cdn | 203.0.113.0/24, 2001:db8:3::/48 |
| 3 | `example-hooks/webhooks` | webhook | 198.51.100.128/25 |
| 3 | `example-train/bot` | ai-training-crawler | 2001:db8:4::/48 |

The feeds cover multiple tags, IPv4-only and IPv6-only lists, host routes
(/32 and /128), an overlap within tier 1 and an overlap between tiers 2 and 3.

## Files

- `sensepitch-known-crawlers-tier{1,2,3}.zip`: the fixture in the release
  format (see the main README). `meta.json` has a fixed `git-sha` and
  `last-modified` of `2026-01-01T00:00:00Z`.
- `expectations.json`: test addresses with every feed they match, sorted by
  tier, vendor and list name. An empty `matches` means no feed matches.
  Addresses are matched as given; IPv4-mapped IPv6 addresses are not covered.
- `registry/` and `lists/`: the source of the ZIPs, in the same layout as the
  repository root.

## Rebuild

```bash
bash examples/edge-fixture/build.sh    # rewrite the ZIPs from registry/ and lists/
python3 examples/edge-fixture/check.py # check expectations.json against the ZIPs
```

`build.sh` runs `lib/release.py` unchanged in a temporary Git repository with
one fixed commit, so a rebuild of unchanged input reproduces the ZIPs byte for
byte.
