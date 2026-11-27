package org.sensepitch.edge;

import io.prometheus.metrics.core.datapoints.CounterDataPoint;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

/// The [DiagnosticLogSink] of all [ProxyLogger]s in tests: keeps the records in memory, per logging
/// thread, for [LogCapture]. Nothing is formatted and Log4j2 is not involved, a record is one
/// thread local lookup and a list add.
///
/// Storing per thread is what makes tests independent of each other: a test sees what its own
/// thread logged, whatever other tests log at the same time. The same design is used by
/// slf4j-test (`TestLogger.getLoggingEvents()` and `getAllLoggingEvents()`). A capture attached
/// to the logging framework instead, e.g. the `ListAppender` of Log4j2, is shared by the whole
/// JVM.
///
/// Trace is enabled, so tests run the trace code, but trace records are dropped: trace output is
/// a debugging aid and no acceptance criterion. Debug records are kept, debug output is meant for
/// operators to find root causes, so tests may assert on it, see [ProxyLogger].
///
/// For debugging, add an appender to the root logger in `log4j2-test.xml`: then every record is
/// passed on to Log4j2 as well.
///
/// @author Jens Wilke
final class TestingLogSink implements DiagnosticLogSink {

  /// A captured record with the name of its logger
  record Entry(String source, LogInfo info) {}

  /// Records of one thread. Synchronized, a capture of all threads reads it from another thread.
  private static final class Buffer {
    private final Object lock = new Object();

    // guarded by lock
    private final List<Entry> entries = new ArrayList<>();

    /// Problem keys seen since the warnings were switched on, `null` while they are off
    private Set<CounterDataPoint> problemKeys;

    void add(Entry entry) {
      synchronized (lock) {
        entries.add(entry);
      }
    }

    void clear() {
      synchronized (lock) {
        entries.clear();
        problemKeys = null;
      }
    }

    void warnProblems() {
      synchronized (lock) {
        problemKeys = new HashSet<>();
      }
    }

    boolean firstOccurrence(CounterDataPoint key) {
      synchronized (lock) {
        return problemKeys != null && problemKeys.add(key);
      }
    }

    void copyTo(List<Entry> target) {
      synchronized (lock) {
        target.addAll(entries);
      }
    }
  }

  /// Buffers of all threads that logged, for captures of all threads. A buffer outlives its
  /// thread until the next [#clearAllThreads()]: short lived threads log as well, and what they
  /// logged must not disappear with them.
  private static final Map<Thread, Buffer> BUFFERS = new ConcurrentHashMap<>();

  /// Fast path to the buffer of the logging thread. Pool threads may erase their thread locals
  /// between tasks, e.g. the workers of the common pool, so the buffer is looked up again and
  /// not replaced, otherwise the records before the erasure were lost.
  private static final ThreadLocal<Buffer> BUFFER =
      ThreadLocal.withInitial(
          () -> BUFFERS.computeIfAbsent(Thread.currentThread(), thread -> new Buffer()));

  /// Set when a developer added an appender to the test configuration, see the class doc
  private static final boolean FORWARD =
      !((LoggerContext) LogManager.getContext(false))
          .getConfiguration()
          .getRootLogger()
          .getAppenders()
          .isEmpty();

  private static volatile boolean installed;

  /// Monitor of the waiters in [#awaitAdded], one for the sink: a capture of all threads reads
  /// the buffers of many threads, so a waiter cannot wait on one buffer
  private static final Object ADDED = new Object();

  /// Threads in [#awaitAdded]. A record notifies only while there is one, so logging takes no
  /// shared lock in the common case.
  private static final AtomicInteger WAITERS = new AtomicInteger();

  private final String source;
  private final DiagnosticLogSink forward;

  private TestingLogSink(String source) {
    this.source = source;
    this.forward = FORWARD ? Factory.log4j(source) : null;
  }

  @Override
  public boolean isEnabled(Level level) {
    return true;
  }

  /// Per thread and test, not per JVM: off, so the request problems are logged at `DEBUG`,
  /// unless a test switched the warnings on via [LogCapture#warnProblems()]. Then the first
  /// occurrence of each problem in the test is the one at `WARNING`. A JVM wide state, as in
  /// production, would make the level depend on the tests that ran before.
  @Override
  public boolean firstOccurrence(CounterDataPoint key) {
    return BUFFER.get().firstOccurrence(key);
  }

  @Override
  public void log(LogInfo info) {
    if (forward != null) {
      forward.log(info);
    }
    if (info.level().getSeverity() >= Level.DEBUG.getSeverity()) {
      BUFFER.get().add(new Entry(source, info));
      if (WAITERS.get() > 0) {
        synchronized (ADDED) {
          ADDED.notifyAll();
        }
      }
    }
  }

  // for LogCapture

  /// Whether the loggers write to this sink. If not, a capture would see nothing and assertions
  /// that expect no message would pass for the wrong reason.
  static boolean installed() {
    return installed;
  }

  static void clearCurrentThread() {
    BUFFER.get().clear();
  }

  static void warnProblemsOfCurrentThread() {
    BUFFER.get().warnProblems();
  }

  static void clearAllThreads() {
    BUFFERS.forEach(
        (thread, buffer) -> {
          buffer.clear();
          if (!thread.isAlive()) {
            BUFFERS.remove(thread);
          }
        });
  }

  static List<Entry> entriesOfCurrentThread() {
    List<Entry> entries = new ArrayList<>();
    BUFFER.get().copyTo(entries);
    return entries;
  }

  /// Records of all threads, in the order of each thread, threads one after the other
  static List<Entry> entriesOfAllThreads() {
    List<Entry> entries = new ArrayList<>();
    for (Buffer buffer : BUFFERS.values()) {
      buffer.copyTo(entries);
    }
    return entries;
  }

  /// Waits until `condition` holds, checking it again after each record of any thread, for
  /// [LogCapture#await]. A wake-up is not lost: a record is added to its buffer before the
  /// logger reads [#WAITERS], and a waiter counts itself before it checks the condition, so
  /// either the check sees the record or the logger sees the waiter and notifies, which it can
  /// only do once the waiter waits, since the waiter holds the monitor until then.
  ///
  /// @param deadlineNanos the [System#nanoTime()] at which to give up
  /// @return whether the condition holds, `false` after the deadline
  static boolean awaitAdded(BooleanSupplier condition, long deadlineNanos)
      throws InterruptedException {
    synchronized (ADDED) {
      WAITERS.incrementAndGet();
      try {
        while (!condition.getAsBoolean()) {
          long remaining = deadlineNanos - System.nanoTime();
          if (remaining <= 0) {
            return false;
          }
          TimeUnit.NANOSECONDS.timedWait(ADDED, remaining);
        }
        return true;
      } finally {
        WAITERS.decrementAndGet();
      }
    }
  }

  /// Makes this sink the sink of all loggers before the first test class is loaded. Registered
  /// in `META-INF/services`, so it also applies to tests started from an IDE.
  public static final class Installer implements LauncherSessionListener {

    @Override
    public void launcherSessionOpened(LauncherSession session) {
      Factory.replace(TestingLogSink::new);
      installed = true;
    }
  }
}
