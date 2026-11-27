package org.sensepitch.edge;

import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.parallel.Isolated;

/// The messages the [ProxyLogger]s logged during a test, so tests can assert on log output.
///
/// Register as a field:
///
/// ```java
/// @RegisterExtension LogCapture logs = new LogCapture();
/// ```
///
/// In tests the loggers write to the [TestingLogSink], which keeps the records per logging thread. A
/// capture shows the records of the test's own thread and forgets the earlier ones before each
/// test. Tests running in other threads at the same time do not show up.
///
/// Code under test that logs from its own threads, e.g. event loops of a started [Proxy] or the
/// sender of a request log destination, needs [#ofAllThreads()]. Such a capture also sees what
/// other tests log, so its test class must be `@Isolated`: it then runs while no other class
/// does. To wait for a record such a thread logs, use [#await(String, Predicate)]: it wakes on
/// each new record instead of polling, see "React on the event" in `AGENTS.md`.
///
/// The first occurrence of each kind of request level event, see `ProxyLogger.incrementAndWarnOnce`, is logged
/// at `WARNING` once per JVM in production. In tests every occurrence is at `DEBUG`, so the level
/// does not depend on which tests ran before; [#warnProblems()] switches the production behaviour
/// on for one test.
///
/// Only `DEBUG` and above is captured, see [TestingLogSink], trace is no acceptance criterion. Messages
/// of Netty, JUL and other libraries do not pass the proxy's loggers, [Log4jCapture] sees them.
///
/// @author Jens Wilke
public class LogCapture implements BeforeEachCallback {

  private final boolean allThreads;

  /// Captures what the thread of the test logs
  public LogCapture() {
    this(false);
  }

  private LogCapture(boolean allThreads) {
    this.allThreads = allThreads;
  }

  /// Captures what any thread logs, see the class doc for the consequence. The test class must
  /// be `@Isolated`, which is checked.
  public static LogCapture ofAllThreads() {
    return new LogCapture(true);
  }

  /// A captured record
  public record Entry(String source, LogInfo info) {

    /// The message as it is logged, without the throwable
    public String message() {
      return DiagnosticLogSink.format(info);
    }

    public Throwable error() {
      return info.error();
    }
  }

  @Override
  public void beforeEach(ExtensionContext context) {
    if (!TestingLogSink.installed()) {
      throw new IllegalStateException(
          "TestingLogSink is not installed, see META-INF/services in the test resources");
    }
    if (allThreads) {
      requireIsolated(context, "LogCapture.ofAllThreads()");
    }
    clear();
  }

  /// Fails a test class that reads JVM wide state without `@Isolated`: run concurrently with
  /// other classes, it would see their log lines.
  static void requireIsolated(ExtensionContext context, String what) {
    if (!context.getRequiredTestClass().isAnnotationPresent(Isolated.class)) {
      throw new IllegalStateException(
          what
              + " sees the log of other tests, annotate "
              + context.getRequiredTestClass().getSimpleName()
              + " with @Isolated");
    }
  }

  /// From now on until the end of the test, the first occurrence of each kind of request level
  /// event in the test's thread is logged at `WARNING`, as the first one in a JVM in production,
  /// see `ProxyLogger.incrementAndWarnOnce`. Only for captures of the test's own thread.
  public void warnProblems() {
    if (allThreads) {
      throw new IllegalStateException("only for the thread of the test");
    }
    TestingLogSink.warnProblemsOfCurrentThread();
  }

  /// Discards the records collected so far and switches [#warnProblems()] off
  public void clear() {
    if (allThreads) {
      TestingLogSink.clearAllThreads();
    } else {
      TestingLogSink.clearCurrentThread();
    }
  }

  /// Snapshot of the collected records
  public List<Entry> entries() {
    List<TestingLogSink.Entry> entries =
        allThreads ? TestingLogSink.entriesOfAllThreads() : TestingLogSink.entriesOfCurrentThread();
    return entries.stream().map(e -> new Entry(e.source(), e.info())).toList();
  }

  /// Messages of all collected records
  public List<String> messages() {
    return entries().stream().map(Entry::message).toList();
  }

  /// Messages from loggers whose name starts with the prefix
  public List<String> messages(String loggerPrefix) {
    return entries().stream()
        .filter(e -> e.source().startsWith(loggerPrefix))
        .map(Entry::message)
        .toList();
  }

  /// Messages from the logger used by [ProxyLogger#get(Class)]
  public List<String> messages(Class<?> source) {
    return entries().stream()
        .filter(e -> e.source().equals(source.getName()))
        .map(Entry::message)
        .toList();
  }

  /// Records at level `ERROR`
  public List<Entry> errors() {
    return entries().stream().filter(e -> e.info().level() == Level.ERROR).toList();
  }

  /// Waits for a record logged by another thread, up to [TestingParameters#EVENT_TIMEOUT]. A
  /// record logged before the call is found at once.
  ///
  /// @param description names the awaited event in the failure
  /// @return the first record that matches
  /// @throws AssertionError if no record matches in time
  public Entry await(String description, Predicate<Entry> condition) throws InterruptedException {
    return await(description, condition, TestingParameters.EVENT_TIMEOUT);
  }

  /// [#await(String, Predicate)] with its own bound, so a test of the failure does not wait the
  /// whole [TestingParameters#EVENT_TIMEOUT]
  Entry await(String description, Predicate<Entry> condition, Duration bound)
      throws InterruptedException {
    long deadline = System.nanoTime() + bound.toNanos();
    if (!TestingLogSink.awaitAdded(() -> firstMatch(condition).isPresent(), deadline)) {
      throw new AssertionError(
          description + " not reached within " + bound.toSeconds() + " seconds");
    }
    return firstMatch(condition).orElseThrow();
  }

  private Optional<Entry> firstMatch(Predicate<Entry> condition) {
    return entries().stream().filter(condition).findFirst();
  }

  /// Waits for a message of the logger of `source` that contains `messagePart`, see
  /// [#await(String, Predicate)]
  ///
  /// @return the message
  public String awaitMessage(Class<?> source, String messagePart) throws InterruptedException {
    return await(
            source.getSimpleName() + " logs \"" + messagePart + "\"",
            e -> e.source().equals(source.getName()) && e.message().contains(messagePart))
        .message();
  }
}
