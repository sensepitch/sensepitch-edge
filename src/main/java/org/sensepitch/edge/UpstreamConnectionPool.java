package org.sensepitch.edge;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.EventLoop;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.ScheduledFuture;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import org.sensepitch.edge.ProxyMetrics.CloseReason;

/// The idle keep-alive connections to one upstream, and the count of all its connections against
/// `maxConnections`, see [ConnectionPoolConfig] and `doc/design/upstream-connection-pool.md`.
///
/// One [Partition] per event loop, used only on its loop: [DefaultUpstream] opens the connection of
/// an exchange on the event loop of the client channel, and takes an idle one from the partition
/// of that loop, so the exchange stays on one thread and a partition needs no lock. A connection
/// never moves to another loop. A partition hands out the connection released last, the surplus
/// after a burst stays idle at the tail and is closed by the idle timeout; the number of idle
/// connections is not limited otherwise. An idle connection keeps reading: a close by the upstream
/// removes it at once, data closes it, see [ForwardHandler].
///
/// Each partition also counts the open connections of its loop, in use, idle and connecting, and
/// refuses a new one beyond the loop's share of `maxConnections`, see [#reserve(EventLoop)]. So the
/// loops together never exceed the limit, without a counter shared by the threads.
///
/// @author Jens Wilke
final class UpstreamConnectionPool {

  /// Set on a connection taken from the pool, so a failure before the response can be retried on
  /// a new connection, see [DownstreamHandler]. A new connection has no value.
  static final AttributeKey<Boolean> REUSED =
      AttributeKey.valueOf(UpstreamConnectionPool.class, "reused");

  /// Why the edge closed a connection, set before the close, see [#closeReason(Channel,
  /// CloseReason)]; without a value the close came from the upstream or the shutdown
  private static final AttributeKey<CloseReason> CLOSE_REASON =
      AttributeKey.valueOf(UpstreamConnectionPool.class, "closeReason");

  private final ConnectionPoolConfig config;
  private final ProxyMetrics metrics;

  /// The share of `maxConnections` of one event loop
  private final int maxConnectionsPerLoop;

  /// The `upstream` label of the metrics
  private final String upstream;

  private final Map<EventLoop, Partition> partitionsByLoop = new ConcurrentHashMap<>();

  /// Set by [#close()]: a connection released afterwards is closed
  private volatile boolean closed;

  /// @param eventLoops the number of event loops, they share `maxConnections`
  UpstreamConnectionPool(
      ConnectionPoolConfig config, ProxyMetrics metrics, String upstream, int eventLoops) {
    this.config = config;
    this.metrics = metrics;
    this.upstream = upstream;
    this.maxConnectionsPerLoop = config.maxConnectionsPerEventLoop(eventLoops);
  }

  /// Counts a connection `loop` is about to open, if its share of `maxConnections` allows it. The
  /// caller gives the count back with [#unreserve(EventLoop)] when the connection closes or its
  /// connect fails. Call on `loop`.
  ///
  /// @return `false` if the loop has its share open, the connection must not be opened
  boolean reserve(EventLoop loop) {
    return partition(loop).reserve();
  }

  /// A connection counted by [#reserve(EventLoop)] was not opened. Call on `loop`.
  void unreserve(EventLoop loop) {
    partition(loop).unreserve();
  }

  /// Counts a connection opened after [#reserve(EventLoop)], and when it closes, gives the
  /// reservation back and counts the close by its [CloseReason]. Call on `loop`, the event loop of
  /// the channel.
  void opened(EventLoop loop, Channel channel) {
    metrics.upstreamPoolConnectionsOpened.labelValues(upstream).inc();
    channel
        .closeFuture()
        .addListener(
            closed -> {
              unreserve(loop);
              CloseReason reason = channel.attr(CLOSE_REASON).get();
              if (reason == null) {
                reason =
                    channel.eventLoop().isShuttingDown()
                        ? CloseReason.SHUTDOWN
                        : CloseReason.CLOSED_BY_UPSTREAM;
              }
              countClosed(reason);
            });
  }

  /// Closes a connection and records why, see [#closeReason(Channel, CloseReason)]
  static void close(Channel channel, CloseReason reason) {
    closeReason(channel, reason);
    channel.close();
  }

  /// Records why the connection is about to be closed, so its close is counted with the reason,
  /// see [#opened(EventLoop, Channel)]. The first reason wins: e.g. a reset reported to the
  /// exchange stays `closed_by_upstream` when the exchange then releases the connection as not
  /// reusable.
  static void closeReason(Channel channel, CloseReason reason) {
    channel.attr(CLOSE_REASON).setIfAbsent(reason);
  }

  /// An idle, still open connection of the partition of `loop`, marked [#REUSED], or `null`.
  /// Call on `loop`.
  Channel acquire(EventLoop loop) {
    Partition partition = partitionsByLoop.get(loop);
    return partition != null ? partition.acquire() : null;
  }

  /// Keeps the connection for the next exchange on its event loop; it must be ready for the next
  /// request, see [DownstreamHandler]. A connection that is no longer open is dropped, one
  /// released after [#close()] is closed. Call on the event loop of the channel: [DefaultUpstream]
  /// opens it on the loop of the client channel, where the exchange releases it.
  void release(Channel channel) {
    if (closed) {
      close(channel, CloseReason.REMOVED);
      return;
    }
    // backpressure of the last exchange may have paused reading, see DownstreamHandler; an idle
    // connection reads, to notice a close, and the next exchange needs its response
    channel.config().setAutoRead(true);
    partition(channel.eventLoop()).add(channel);
  }

  /// The partition of `loop`, created on first use. Package private for tests, which pass the
  /// event loop of an embedded channel.
  Partition partition(EventLoop loop) {
    return partitionsByLoop.computeIfAbsent(loop, Partition::new);
  }

  /// Closes the idle connections, on the loop of each partition, when a reload removed or changed
  /// the upstream. Connections in use are closed when their exchange releases them. A loop that
  /// shut down already closed its connections itself.
  void close() {
    closed = true;
    partitionsByLoop.forEach(
        (loop, partition) -> {
          try {
            loop.execute(partition::closeAll);
          } catch (RejectedExecutionException shutDown) {
            // the loop closed its channels when it shut down
          }
        });
  }

  private void countClosed(CloseReason reason) {
    metrics.upstreamPoolConnectionsClosed.labelValues(upstream, reason.label).inc();
  }

  /// The idle connections of one event loop, used only on that loop. Most recently released
  /// first.
  final class Partition {

    private final EventLoop loop;
    private final ArrayDeque<Idle> idle = new ArrayDeque<>();

    /// Open connections of this loop, in use, idle and connecting
    private int open;

    private Partition(EventLoop loop) {
      this.loop = loop;
    }

    /// The connection released last, or `null`, see [UpstreamConnectionPool#acquire]
    Channel acquire() {
      assert loop.inEventLoop();
      // never an inactive one: a connection that closes leaves at once, see Idle
      Idle entry = idle.pollFirst();
      if (entry == null) {
        return null;
      }
      entry.forget();
      entry.channel.attr(REUSED).set(true);
      metrics.upstreamPoolConnectionsReused.labelValues(upstream).inc();
      return entry.channel;
    }

    private boolean reserve() {
      assert loop.inEventLoop();
      if (open >= maxConnectionsPerLoop) {
        return false;
      }
      open++;
      metrics.upstreamPoolConnections.labelValues(upstream).inc();
      return true;
    }

    private void unreserve() {
      assert loop.inEventLoop();
      open--;
      metrics.upstreamPoolConnections.labelValues(upstream).dec();
    }

    /// Keeps an open connection, until the idle timeout or until it is taken
    void add(Channel channel) {
      assert loop.inEventLoop();
      if (!channel.isActive()) {
        return;
      }
      idle.addFirst(new Idle(channel));
      metrics.upstreamPoolConnectionsIdle.labelValues(upstream).inc();
    }

    /// Number of idle connections, for tests
    int size() {
      return idle.size();
    }

    private void closeAll() {
      Idle entry;
      while ((entry = idle.pollFirst()) != null) {
        entry.forget();
        close(entry.channel, CloseReason.REMOVED);
      }
    }

    /// An idle connection with its idle timer, and listening for its close
    private final class Idle implements ChannelFutureListener {

      final Channel channel;
      private final ScheduledFuture<?> timeout;

      Idle(Channel channel) {
        this.channel = channel;
        channel.closeFuture().addListener(this);
        timeout = loop.schedule(this::expire, config.idleTimeoutMillis(), MILLISECONDS);
      }

      /// The idle timeout passed; never after [#forget()], which cancels the timer on this loop
      private void expire() {
        idle.remove(this);
        forget();
        close(channel, CloseReason.IDLE_TIMEOUT);
      }

      /// The connection closed while idle, by the upstream, or by the [ForwardHandler] after
      /// unexpected data; never after [#forget()], which removes this listener. The close is
      /// counted by the listener of [UpstreamConnectionPool#opened(EventLoop, Channel)].
      @Override
      public void operationComplete(ChannelFuture future) {
        idle.remove(this);
        forget();
      }

      /// Ends the watching after the connection left the partition, called once per entry
      void forget() {
        channel.closeFuture().removeListener(this);
        timeout.cancel(false);
        metrics.upstreamPoolConnectionsIdle.labelValues(upstream).dec();
      }
    }
  }
}
