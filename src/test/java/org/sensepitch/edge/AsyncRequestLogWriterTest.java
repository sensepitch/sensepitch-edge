package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.sensepitch.edge.TestingParameters.EVENT_TIMEOUT_SECONDS;
import static org.sensepitch.edge.TestingParameters.TEST_TIMEOUT_SECONDS;

import io.prometheus.metrics.model.registry.PrometheusRegistry;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.parallel.Isolated;

/// @author Jens Wilke
@Timeout(TEST_TIMEOUT_SECONDS)
// alone: captures the log of all threads, the writer logs from its own
@Isolated
public class AsyncRequestLogWriterTest {

  @RegisterExtension LogCapture logs = LogCapture.ofAllThreads();

  @Test
  public void linesAreWrittenInOrderAndCloseWritesOutEverything() {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    AsyncRequestLogWriter writer = new AsyncRequestLogWriter(out, 1024);
    for (int i = 0; i < 1000; i++) {
      writer.writeLine("line " + i + " äöü");
    }
    writer.close();
    var lines = out.toString(StandardCharsets.UTF_8).lines().toList();
    assertThat(lines).hasSize(1000);
    assertThat(lines.getFirst()).isEqualTo("line 0 äöü");
    assertThat(lines.getLast()).isEqualTo("line 999 äöü");
    assertThat(writer.droppedCount()).isZero();
    assertThat(logs.errors()).isEmpty();
  }

  @Test
  public void recordIsVisibleWithoutCloseWhenIdle() throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    CountDownLatch flushed = new CountDownLatch(1);
    OutputStream latchOnFlush =
        new OutputStream() {
          @Override
          public void write(int b) {
            out.write(b);
          }

          @Override
          public void write(byte[] b, int off, int len) {
            out.write(b, off, len);
          }

          @Override
          public void flush() {
            flushed.countDown();
          }
        };
    AsyncRequestLogWriter writer = new AsyncRequestLogWriter(latchOnFlush, 16);
    try {
      writer.writeLine("single");
      assertThat(flushed.await(EVENT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
          .as("single record flushed without close")
          .isTrue();
      assertThat(out.toString(StandardCharsets.UTF_8)).isEqualTo("single\n");
    } finally {
      writer.close();
    }
  }

  /// The point of the writer: a stuck standard out must not block the calling event loop.
  @Test
  public void blockedOutputDropsInsteadOfBlockingTheCaller() throws Exception {
    CountDownLatch writerStarted = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ByteArrayOutputStream sink = new ByteArrayOutputStream();
    OutputStream blocking =
        new OutputStream() {
          @Override
          public void write(int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
          }

          @Override
          public void write(byte[] b, int off, int len) throws IOException {
            writerStarted.countDown();
            try {
              release.await();
            } catch (InterruptedException e) {
              throw new IOException(e);
            }
            sink.write(b, off, len);
          }
        };
    int capacity = 4;
    int records = 100;
    AsyncRequestLogWriter writer = new AsyncRequestLogWriter(blocking, capacity);
    try (var caller = Executors.newVirtualThreadPerTaskExecutor()) {
      try {
        var write =
            caller.submit(
                () -> {
                  for (int i = 0; i < records; i++) {
                    writer.writeLine("line " + i);
                  }
                });
        assertThat(writerStarted.await(EVENT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            .as("writer reached the blocked output")
            .isTrue();
        write.get(EVENT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        // The writer holds at most one drained batch (capacity + 1) and one queue of records.
        assertThat(writer.droppedCount()).isGreaterThanOrEqualTo(records - 2 * capacity - 1);
        assertThat(logs.messages(AsyncRequestLogWriter.class))
            .describedAs("first drop is reported once")
            .singleElement()
            .asString()
            .contains("dropping records");
      } finally {
        release.countDown();
      }
    } finally {
      writer.close();
    }
    long written = sink.toString(StandardCharsets.UTF_8).lines().count();
    assertThat(written + writer.droppedCount()).isEqualTo(records);
  }

  /// No room for the end marker: close gives up the queued record at once and reports it. The
  /// writer thread ends after the output unblocks: its second flush comes from the interrupt.
  @Test
  public void closeWithFullQueueAndBlockedOutputReturnsAndGivesUpQueuedRecords() throws Exception {
    FlushBlockingOutput output = new FlushBlockingOutput();
    AsyncRequestLogWriter writer = new AsyncRequestLogWriter(output, 1, Duration.ZERO);
    writer.writeLine("taken");
    output.awaitFlushes(1);
    writer.writeLine("queued");
    writer.close();
    assertThat(logs.messages(AsyncRequestLogWriter.class))
        .singleElement()
        .asString()
        .contains("closing without writing out pending records");
    output.release.release(2);
    output.awaitFlushes(2);
    assertThat(output.written()).isEqualTo("taken\n");
  }

  /// Room for the end marker but the writer does not finish in time: close returns and reports
  /// it. Once the output unblocks, the writer writes the records queued before close and ends.
  @Test
  public void closeWithBlockedOutputReturnsAndPendingRecordsFollowLater() throws Exception {
    FlushBlockingOutput output = new FlushBlockingOutput();
    AsyncRequestLogWriter writer = new AsyncRequestLogWriter(output, 4, Duration.ZERO);
    writer.writeLine("taken");
    output.awaitFlushes(1);
    writer.writeLine("queued");
    writer.close();
    assertThat(logs.messages(AsyncRequestLogWriter.class))
        .singleElement()
        .asString()
        .contains("closing before pending records are written");
    output.release.release(2);
    output.awaitFlushes(2);
    assertThat(output.written()).isEqualTo("taken\nqueued\n");
  }

  /// Close from an interrupted thread, e.g. a shutdown hook that was interrupted: close returns
  /// at once and keeps the interrupt flag. The end marker is still queued, so the writer writes the
  /// queued record and ends instead of waiting for records forever.
  @Test
  public void interruptedCloseWritesOutQueuedRecordsAndEndsWriter() throws Exception {
    FlushBlockingOutput output = new FlushBlockingOutput();
    AsyncRequestLogWriter writer = new AsyncRequestLogWriter(output, 4);
    writer.writeLine("taken");
    output.awaitFlushes(1);
    writer.writeLine("queued");
    Thread.currentThread().interrupt();
    writer.close();
    assertThat(Thread.interrupted()).as("interrupt flag kept").isTrue();
    output.release.release(2);
    assertThat(writer.writerThread().join(Duration.ofSeconds(EVENT_TIMEOUT_SECONDS)))
        .as("writer thread ended")
        .isTrue();
    assertThat(output.written()).isEqualTo("taken\nqueued\n");
    assertThat(logs.errors()).isEmpty();
  }

  /// Interrupted close and no room for the end marker: the queued record is given up and the
  /// writer thread is interrupted, so it ends once the output unblocks.
  @Test
  public void interruptedCloseWithFullQueueGivesUpQueuedRecordsAndEndsWriter() throws Exception {
    FlushBlockingOutput output = new FlushBlockingOutput();
    AsyncRequestLogWriter writer = new AsyncRequestLogWriter(output, 1);
    writer.writeLine("taken");
    output.awaitFlushes(1);
    writer.writeLine("queued");
    Thread.currentThread().interrupt();
    writer.close();
    assertThat(Thread.interrupted()).as("interrupt flag kept").isTrue();
    assertThat(logs.messages(AsyncRequestLogWriter.class))
        .singleElement()
        .asString()
        .contains("closing without writing out pending records");
    output.release.release(2);
    assertThat(writer.writerThread().join(Duration.ofSeconds(EVENT_TIMEOUT_SECONDS)))
        .as("writer thread ended")
        .isTrue();
    assertThat(output.written()).isEqualTo("taken\n");
  }

  /// Interrupted while waiting for the writer, after the end marker is queued: close returns and
  /// keeps the interrupt flag, the writer still writes the queued record and ends.
  @Test
  public void closeInterruptedWhileWaitingForWriterLetsItFinish() throws Exception {
    FlushBlockingOutput output = new FlushBlockingOutput();
    AsyncRequestLogWriter writer = new AsyncRequestLogWriter(output, 4);
    writer.writeLine("taken");
    output.awaitFlushes(1);
    writer.writeLine("queued");
    AtomicBoolean interruptKept = new AtomicBoolean();
    Thread closer =
        Thread.ofVirtual()
            .start(
                () -> {
                  writer.close();
                  interruptKept.set(Thread.currentThread().isInterrupted());
                });
    // the record and the end marker, close is now waiting for the writer thread
    while (writer.queueSize() < 2) {
      Thread.onSpinWait();
    }
    closer.interrupt();
    assertThat(closer.join(Duration.ofSeconds(EVENT_TIMEOUT_SECONDS)))
        .as("close returned")
        .isTrue();
    assertThat(interruptKept).isTrue();
    output.release.release(2);
    assertThat(writer.writerThread().join(Duration.ofSeconds(EVENT_TIMEOUT_SECONDS)))
        .as("writer thread ended")
        .isTrue();
    assertThat(output.written()).isEqualTo("taken\nqueued\n");
    assertThat(logs.errors()).isEmpty();
  }

  /// Output whose flush blocks until the test releases it, ignoring interrupts like a blocked
  /// write to standard out. Each flush is signalled before it blocks.
  static class FlushBlockingOutput extends OutputStream {

    final Semaphore release = new Semaphore(0);
    private final Semaphore flushes = new Semaphore(0);
    private final ByteArrayOutputStream sink = new ByteArrayOutputStream();

    @Override
    public synchronized void write(int b) {
      sink.write(b);
    }

    @Override
    public synchronized void write(byte[] b, int off, int len) {
      sink.write(b, off, len);
    }

    @Override
    public void flush() {
      flushes.release();
      release.acquireUninterruptibly();
    }

    void awaitFlushes(int count) throws InterruptedException {
      assertThat(flushes.tryAcquire(count, EVENT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
          .as("writer flushed %d times", count)
          .isTrue();
      flushes.release(count);
    }

    synchronized String written() {
      return sink.toString(StandardCharsets.UTF_8);
    }
  }

  @Test
  public void writeAfterCloseIsDroppedSilently() {
    AsyncRequestLogWriter writer = new AsyncRequestLogWriter(new ByteArrayOutputStream(), 4);
    writer.close();
    writer.close();
    writer.writeLine("late");
    assertThat(writer.droppedCount()).isOne();
    assertThat(logs.errors()).isEmpty();
  }

  @Test
  public void outputErrorIsReportedOnceAndDoesNotReachTheCaller() {
    OutputStream failing =
        new OutputStream() {
          @Override
          public void write(int b) throws IOException {
            throw new IOException("broken pipe");
          }

          @Override
          public void flush() throws IOException {
            throw new IOException("broken pipe");
          }
        };
    AsyncRequestLogWriter writer = new AsyncRequestLogWriter(failing, 16);
    for (int i = 0; i < 10; i++) {
      writer.writeLine("line " + i);
    }
    writer.close();
    assertThat(logs.messages(AsyncRequestLogWriter.class))
        .singleElement()
        .asString()
        .contains("Error writing request log");
  }

  /// A record larger than the output buffer is written directly, so the error comes from the
  /// write, not from the flush.
  @Test
  public void errorWritingLargeRecordIsReported() {
    OutputStream failing =
        new OutputStream() {
          @Override
          public void write(int b) throws IOException {
            throw new IOException("broken pipe");
          }

          @Override
          public void write(byte[] b, int off, int len) throws IOException {
            throw new IOException("broken pipe");
          }
        };
    AsyncRequestLogWriter writer = new AsyncRequestLogWriter(failing, 16);
    writer.writeLine("x".repeat(AsyncRequestLogWriter.BUFFER_SIZE + 1));
    writer.close();
    assertThat(logs.messages(AsyncRequestLogWriter.class))
        .singleElement()
        .asString()
        .contains("Error writing request log");
  }

  /// The writer exposes dropped records and the queue length. In production the writer is the
  /// request log output, so the proxy registers these, see [Proxy].
  @Test
  public void metrics() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    OutputStream blocking =
        new OutputStream() {
          @Override
          public void write(int b) throws IOException {
            try {
              release.await();
            } catch (InterruptedException e) {
              throw new IOException(e);
            }
          }
        };
    AsyncRequestLogWriter writer = new AsyncRequestLogWriter(blocking, 2);
    PrometheusRegistry registry = new PrometheusRegistry();
    writer.registerCollectors(registry::register);
    for (int i = 0; i < 10; i++) {
      writer.writeLine("x".repeat(AsyncRequestLogWriter.BUFFER_SIZE + 1));
    }
    var snapshots = new ScrapedMetrics(registry.scrape());
    // labelled with the destination, since the proxy can have several
    assertThat(
            snapshots.counter("sensepitch_request_log_dropped", "destination", "stdout").getValue())
        .isEqualTo(writer.droppedCount())
        .isPositive();
    assertThat(snapshots.gauge("sensepitch_request_log_queued", "destination", "stdout").getValue())
        .isBetween(0.0, 2.0);
    release.countDown();
    writer.close();
  }
}
