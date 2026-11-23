package org.sensepitch.edge;

import com.sun.net.httpserver.HttpServer;
import java.time.Duration;

/// Shared bounds of the tests: for one event, for one test, and for timers that must not fire.
final class TestingParameters {

  /// [#EVENT_TIMEOUT] in seconds, for APIs that take a count and a unit.
  static final int EVENT_TIMEOUT_SECONDS = 10;

  /// Bound of one wait for one event of another thread, process or timer. The scheduler may
  /// delay any step of the code under test or of the test itself by up to this long, and a test
  /// must still pass, so every single wait allows it in full: a shorter bound may flake, a wait
  /// that runs out means the event did not come. A normal run ends far below it.
  ///
  /// An event of several steps has its own named bound, like [#PROCESS_START_TIMEOUT]. A timer of
  /// the code under test that must not fire is set to [#OUT_OF_REACH], never to this value. The
  /// limit of a whole test is [#TEST_TIMEOUT_SECONDS].
  static final Duration EVENT_TIMEOUT = Duration.ofSeconds(EVENT_TIMEOUT_SECONDS);

  /// Outer guard of one test, for the `@Timeout` of a test class, in seconds: an annotation needs
  /// a constant, not a [Duration]. Above the sum of the waits of any test, so a wait that runs out
  /// reports the missing event before this guard fires. Reaching it is a failure, never part of a
  /// passing run. The largest user is a child JVM start of [#PROCESS_START_TIMEOUT] plus waits.
  static final int TEST_TIMEOUT_SECONDS = 6 * EVENT_TIMEOUT_SECONDS;

  /// For a timer the test relies on never firing (flush interval, age limit): far beyond any
  /// wait, so a scheduler delay of up to [#EVENT_TIMEOUT] cannot reach it.
  static final Duration OUT_OF_REACH = EVENT_TIMEOUT.multipliedBy(10);

  /// [#OUT_OF_REACH] for the `int` millisecond fields of the configuration.
  static final int OUT_OF_REACH_MILLIS = (int) OUT_OF_REACH.toMillis();

  /// Bounds the start of a child JVM until it serves: class loading, native library extraction,
  /// configuration and binding are many steps, each of which may be delayed by up to
  /// [#EVENT_TIMEOUT].
  static final Duration PROCESS_START_TIMEOUT = EVENT_TIMEOUT.multipliedBy(3);

  private TestingParameters() {}

  /// Bounds asynchronous completion waits and identifies the missing event on failure.
  static void await(String description, Condition condition) throws Exception {
    await(description, EVENT_TIMEOUT, condition);
  }

  /// [#await(String, Condition)] for an event that takes several steps, like
  /// [#PROCESS_START_TIMEOUT].
  static void await(String description, Duration bound, Condition condition) throws Exception {
    long deadline = System.nanoTime() + bound.toNanos();
    while (!condition.test()) {
      if (System.nanoTime() - deadline >= 0) {
        throw new AssertionError(
            description + " not reached within " + bound.toSeconds() + " seconds");
      }
      Thread.sleep(5);
    }
  }

  /// Lets a test server finish an exchange before closing its connection. An idle server returns
  /// at once; a generous grace period tolerates scheduling pauses on loaded machines. An exchange
  /// counts as finished only when its response is complete: a handler that closes it without
  /// response headers makes this wait the whole grace.
  static void stop(HttpServer server) {
    server.stop(EVENT_TIMEOUT_SECONDS);
  }

  /// Allows completion probes that read files or sockets to report checked failures.
  @FunctionalInterface
  interface Condition {
    boolean test() throws Exception;
  }
}
