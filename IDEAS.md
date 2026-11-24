# Ideas

New ideas can be added here. An idea should be brief, for which audience its relevant, estimation of importance, a recent and most relevant reference if there is one.

- `ProxyShutdownIT` upstream handler swallows the `IOException` when the proxy has gone before
  the answer; then the exchange never ends and `TestingParameters.stop` waits its whole grace.
  Test speed, low importance, it does not happen in the passing runs. From the `ShadedJarIT`
  stop grace fix, 2026-11-22.
- `ProxyAcceptGraceTest.readinessAndReloadChangeBeforeTheGraceAndConcurrentCallerWaits`: when a
  wait in the `try` runs its full `EVENT_TIMEOUT`, the `finally` calls `first.get`, whose own
  latch wait has timed out too, and that `AssertionError` replaces the original failure. Test
  diagnostics, low importance, it only hides the cause of a failure. From the
  `accept-grace-second-caller-wait` turn, 2026-11-23.
- Close the replaced MaxMind `Reader`s (`IpInfoCountryAndAsnLookup`, GeoIP2) when a reload
  replaces the IP lookup; today the memory mapping and the disk space of a replaced 24 MB
  database stay until the buffer is garbage collected. Operators, low importance, daily updates
  pile up only between collections. From the `ip-database-volume-research` turn, 2026-11-23.
- A `ConfigReloadTest` case for a truncated IP database: maxmind-db 3.2.0 throws
  `IllegalArgumentException`, not `InvalidDatabaseException`, for a cut file, and only the
  `RuntimeException` catch in `Proxy.reload` keeps the active configuration. Developers, medium
  importance, guards the half-written download case. From the `ip-database-volume-research`
  turn, 2026-11-23.
- Answer a decoder failure in the request body too, e.g. a malformed chunk size: Netty passes it
  on as a `LastHttpContent` with a failed `decoderResult()` (inferred, not traced), which
  `DecoderFailureHandler` lets through, so the upstream may see a truncated body as complete.
  Operators, medium importance, a client error would end as an upstream answer. From the
  `answer-http-decoder-failures` turn, 2026-11-24.
