# Ideas

New ideas can be added here. An idea should be brief, for which audience its relevant, estimation of importance, a recent and most relevant reference if there is one.

- `ProxyShutdownIT` upstream handler swallows the `IOException` when the proxy has gone before
  the answer; then the exchange never ends and `TestingParameters.stop` waits its whole grace.
  Test speed, low importance, it does not happen in the passing runs. From the `ShadedJarIT`
  stop grace fix, 2026-11-22.
