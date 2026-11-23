package org.sensepitch.edge;

import io.netty.util.concurrent.Ticker;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.morimekta.testing.concurrent.FakeClock;
import net.morimekta.testing.concurrent.ImmediateScheduledExecutor;

/// Simulated time for tests: drives the Netty [Ticker] seams and the scheduled tasks from one
/// morimekta `FakeClock`, so a timer and an age or backoff check see the same time. The test
/// moves the clock with [#advanceMillis(long)], which runs the tasks that are due on the calling
/// thread; nothing runs on other threads, and no test waits real time. See "Concurrent and
/// asynchronous tests" in `AGENTS.md`.
final class FakeTime implements Ticker {

  private final FakeClock clock = new FakeClock();
  private final Instant start = clock.instant();
  private final ScheduledExecutorService scheduler = new ImmediateScheduledExecutor(clock);

  @Override
  public long initialNanoTime() {
    return 0;
  }

  @Override
  public long nanoTime() {
    // FakeClock.nanoTime() advances on reads, so use the scheduler's instant for exact deadlines.
    return Duration.between(start, clock.instant()).toNanos();
  }

  @Override
  public void sleep(long delay, TimeUnit unit) {
    clock.tick(delay, unit);
  }

  /// Moves the clock and runs any tasks that are due.
  void advanceMillis(long millis) {
    clock.tick(millis, TimeUnit.MILLISECONDS);
  }

  /// The scheduler on this clock, for the timer seam of the code under test
  ScheduledExecutorService scheduler() {
    return scheduler;
  }
}
