package org.sensepitch.edge;

import com.sun.net.httpserver.HttpServer;
import java.time.Duration;

/// Shared upper bound for waiting on another thread or process in tests.
final class TestingParameters {

  /// [#TIMEOUT] in seconds, for APIs that take a count and a unit.
  static final int TIMEOUT_SECONDS = 10;

  /// Maximum time per test. Any test reaching this timeout is a failure.
  ///
  /// A passing test stays far below it, so it is also the bound of every single wait for
  /// another thread, process or timer: a wait that reaches it has already failed the test, a
  /// shorter one may flake under scheduler delays, a multiple of it is never needed. The only
  /// exception is an event of several steps with its own named bound, like
  /// [#PROCESS_START_TIMEOUT]. A timer of the code under test that must not fire is set to
  /// [#OUT_OF_REACH], never to this value.
  static final Duration TIMEOUT = Duration.ofSeconds(TIMEOUT_SECONDS);

  /// For a timer the test relies on never firing (flush interval, age limit): far beyond any
  /// wait, so a scheduler delay of up to [#TIMEOUT] cannot reach it.
  static final Duration OUT_OF_REACH = TIMEOUT.multipliedBy(10);

  /// [#OUT_OF_REACH] for the `int` millisecond fields of the configuration.
  static final int OUT_OF_REACH_MILLIS = (int) OUT_OF_REACH.toMillis();

  /// Bounds the start of a child JVM until it serves: class loading, native library extraction,
  /// configuration and binding are many steps, each of which may be delayed by up to [#TIMEOUT].
  static final Duration PROCESS_START_TIMEOUT = TIMEOUT.multipliedBy(3);

  private TestingParameters() {}

  /// Bounds asynchronous completion waits and identifies the missing event on failure.
  static void await(String description, Condition condition) throws Exception {
    await(description, TIMEOUT, condition);
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
    server.stop(TIMEOUT_SECONDS);
  }

  /// Allows completion probes that read files or sockets to report checked failures.
  @FunctionalInterface
  interface Condition {
    boolean test() throws Exception;
  }
}
