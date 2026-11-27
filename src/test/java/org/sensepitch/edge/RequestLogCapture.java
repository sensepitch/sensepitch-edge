package org.sensepitch.edge;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/// In-memory destination of the request log, which otherwise goes to standard out. Pass it to
/// [Proxy#Proxy(ProxyConfig, RequestLogOutput)] or use [#plainText()] directly in a handler
/// pipeline. A test waits for a line written by an event loop with
/// [#awaitLine(String, Predicate)].
///
/// @author Jens Wilke
public class RequestLogCapture implements RequestLogOutput {

  /// Event loop threads may write concurrently. Also the monitor [#awaitLine] waits on.
  private final Object lock = new Object();

  // guarded by lock
  private final List<String> lines = new ArrayList<>();
  private final List<RequestOutcome> outcomes = new ArrayList<>();
  private final List<ResponseSource> sources = new ArrayList<>();

  @Override
  public void writeLine(String line) {
    synchronized (lock) {
      lines.add(line);
      lock.notifyAll();
    }
  }

  /// Request logger writing the plain text format into this capture. It also records the outcome
  /// and the response source of each request, which the plain text format does not show, see
  /// [#outcomes()] and [#sources()].
  public RequestLogger plainText() {
    PlainTextRequestLogger text = new PlainTextRequestLogger(this);
    return info -> {
      synchronized (lock) {
        outcomes.add(info.outcome());
        sources.add(info.responseSource());
      }
      text.logRequest(info);
    };
  }

  /// Outcomes of the requests logged via [#plainText()] so far
  public List<RequestOutcome> outcomes() {
    synchronized (lock) {
      return List.copyOf(outcomes);
    }
  }

  /// Response sources of the requests logged via [#plainText()] so far, `null` where no response
  /// was written
  public List<ResponseSource> sources() {
    synchronized (lock) {
      return new ArrayList<>(sources);
    }
  }

  /// Request log lines written so far
  public List<String> lines() {
    synchronized (lock) {
      return List.copyOf(lines);
    }
  }

  /// Waits for a request log line, up to [TestingParameters#EVENT_TIMEOUT]; wakes on each line
  /// written, a line written before the call is found at once. Lines logged via [#plainText()]
  /// are written after their outcome is recorded, so [#outcomes()] includes the record then.
  ///
  /// @param description names the awaited event in the failure
  /// @return the first line that matches
  /// @throws AssertionError if no line matches in time
  public String awaitLine(String description, Predicate<String> condition)
      throws InterruptedException {
    return awaitLine(description, condition, TestingParameters.EVENT_TIMEOUT);
  }

  /// [#awaitLine(String, Predicate)] with its own bound, so a test of the failure does not wait
  /// the whole [TestingParameters#EVENT_TIMEOUT]
  String awaitLine(String description, Predicate<String> condition, Duration bound)
      throws InterruptedException {
    long deadline = System.nanoTime() + bound.toNanos();
    synchronized (lock) {
      while (true) {
        for (String line : lines) {
          if (condition.test(line)) {
            return line;
          }
        }
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
          throw new AssertionError(
              description + " not reached within " + bound.toSeconds() + " seconds");
        }
        TimeUnit.NANOSECONDS.timedWait(lock, remaining);
      }
    }
  }
}
