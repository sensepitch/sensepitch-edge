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
- Give `Deflector.deriveServerIpv4Address()` a seam for `PublicIpv4Finder.findFirstPublicIpv4()`
  so its present-address, absent-address, and `SocketException` fallback paths are unit-tested
  deterministically instead of varying by whether the test host has a public IPv4. Developers, low
  importance, removes host-NIC coverage variance. From the
  `test-public-ipv4-finder-deterministically` turn, 2026-11-26.
- Add the default `Strict-Transport-Security` also to responses the edge generates itself
  (protection challenge, `FallbackHandler` pages, `ExceptionHandler` error pages, unserviced host
  answers); today only forwarded and configured static responses (these responses simulate an upstream) carry it, since only those pass
  `DownstreamHandler.write` with an upstream attached. Operators, medium importance, a client
  whose first response is a challenge learns no policy.
- Other tests that close an `HttpBatchSender` and then stop their JDK test server with
  `TestingParameters.stop` (`OtlpLogDestinationTest.stop`, `HttpBatchSenderTest`,
  `OtlpAllocationIT`, `OtlpSlowStartIT`) can hit the same 10 s stop that
  `OtlpLogDestinationIT.concurrentRequestsUpToTheLimit` had; none was slow in one full gate. A
  shared helper that stops without grace once the sender is closed would cover them. Developers,
  low importance. From the `shorten-otlp-destination-it` turn, 2026-11-27.
- Show deprecation warnings in the normal build (`<showDeprecation>true</showDeprecation>` or
  `-Xlint:deprecation` on `maven-compiler-plugin` in `pom.xml`), so a newly deprecated library
  call shows up when a dependency is upgraded instead of in an occasional manual run. Developers,
  low importance. From the `replace-deprecated-library-calls` turn, 2026-11-27.
