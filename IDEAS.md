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
