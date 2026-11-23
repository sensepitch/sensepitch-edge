package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.sensepitch.edge.TestingParameters.EVENT_TIMEOUT;
import static org.sensepitch.edge.TestingParameters.EVENT_TIMEOUT_SECONDS;
import static org.sensepitch.edge.TestingParameters.TEST_TIMEOUT_SECONDS;
import static org.sensepitch.edge.TestingParameters.await;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/// The shutdown order of [Main]: services first, Log4j2 last. [ShadedJarIT] checks the same with
/// a real `SIGTERM` against the deployed JAR.
@Timeout(TEST_TIMEOUT_SECONDS)
class MainShutdownTest {

  /// Log4j2's own hook would run concurrently with ours and stop logging while the proxy drains.
  @ParameterizedTest
  @ValueSource(strings = {"log4j2.xml", "log4j2-trace.xml"})
  void bundledConfigurationDisablesLog4jShutdownHook(String resource) throws Exception {
    try (var in = getClass().getClassLoader().getResourceAsStream(resource)) {
      var root =
          DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(in).getDocumentElement();
      assertThat(root.getAttribute("shutdownHook")).isEqualTo("disable");
    }
  }

  @Test
  void servicesStopBeforeLoggingAndOnlyOnce() {
    List<String> events = new ArrayList<>();
    var shutdown = new Main.Shutdown(() -> events.add("services"), () -> events.add("logging"));
    shutdown.run();
    shutdown.run();
    assertThat(events).containsExactly("services", "logging");
  }

  @Test
  void serviceFailureStillStopsLoggingOnce() {
    List<String> events = new ArrayList<>();
    var shutdown =
        new Main.Shutdown(
            () -> {
              events.add("services");
              throw new IllegalStateException("cleanup failed");
            },
            () -> events.add("logging"));
    assertThatThrownBy(shutdown::run).hasMessage("cleanup failed");
    shutdown.run();
    assertThat(events).containsExactly("services", "logging");
  }

  /// The main thread and the JVM shutdown hook both call it; the second must not return before
  /// logging is stopped, or the JVM could exit with the first still running. The logging stop is
  /// held on a latch until the second caller waits for the lock; the order of the two events then
  /// shows that it waited.
  @Test
  void concurrentCallerWaitsForLoggingShutdown() throws Exception {
    CountDownLatch loggingStarted = new CountDownLatch(1);
    CountDownLatch releaseLogging = new CountDownLatch(1);
    Queue<String> events = new ConcurrentLinkedQueue<>();
    var shutdown =
        new Main.Shutdown(
            () -> {},
            () -> {
              loggingStarted.countDown();
              try {
                assertThat(releaseLogging.await(EVENT_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
              } catch (InterruptedException e) {
                throw new AssertionError(e);
              }
              events.add(LOGGING_STOPPED);
            });
    Thread first = Thread.ofPlatform().start(shutdown);
    try {
      assertThat(loggingStarted.await(EVENT_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
      Thread second =
          Thread.ofPlatform()
              .start(
                  () -> {
                    shutdown.run();
                    events.add(SECOND_RETURNED);
                  });
      // the only monitor the second thread enters is the lock of the shutdown
      await("second caller waits for the lock", () -> second.getState() == Thread.State.BLOCKED);
      releaseLogging.countDown();
      assertThat(first.join(EVENT_TIMEOUT)).as("first caller finished").isTrue();
      assertThat(second.join(EVENT_TIMEOUT)).as("second caller finished").isTrue();
      assertThat(events).containsExactly(LOGGING_STOPPED, SECOND_RETURNED);
    } finally {
      releaseLogging.countDown();
    }
  }

  static final String LOGGING_STOPPED = "logging stopped";
  static final String SECOND_RETURNED = "second caller returned";
}
