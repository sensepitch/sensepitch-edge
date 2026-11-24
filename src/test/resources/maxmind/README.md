# MaxMind test databases

Test databases of MaxMind, for the GeoIP2 lookups (`GeoIp2CountryLookup`, `GeoIp2AsnLookup`).
They contain only a few made-up networks, e.g. `81.2.69.142` in `GB` and `1.128.0.0/11` with
ASN `1221`; the source of each entry is the JSON of the same name in `source-data/`.

- Source: https://github.com/maxmind/MaxMind-DB, directory `test-data/`, commit
  `c454a21f31fd17f2843f16094e9afb3a14a42078` (2026-11-16), downloaded 2026-11-24.
- License: Apache License 2.0 or MIT, at your option, Copyright (c) 2013 - 2026 MaxMind, Inc.
- Files: `GeoIP2-Country-Test.mmdb`, `GeoLite2-ASN-Test.mmdb`, unchanged.
