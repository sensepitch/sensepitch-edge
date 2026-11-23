# Ideas

New ideas can be added here. An idea should be brief, for which audience its relevant, estimation of importance, a recent and most relevant reference if there is one.

- `ProxyShutdownIT` upstream handler swallows the `IOException` when the proxy has gone before
  the answer; then the exchange never ends and `TestingParameters.stop` waits its whole grace.
  Test speed, low importance, it does not happen in the passing runs. From the `ShadedJarIT`
  stop grace fix, 2026-11-22.
- Developers, medium: `ProxyShutdownIT.acceptGraceKeepsServingThenClosesWithinTheBudget`
  reported 3 OTLP records instead of 4 in one full gate run on `4842865` (2026-11-22) while
  other Maven runs loaded the machine. The next run passed. The last export may still be in
  flight when the test reads the count after `shutdown.get`. Await a count of at least 4.
