package org.sensepitch.edge;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import io.netty.channel.Channel;
import io.netty.channel.DefaultEventLoop;
import io.netty.channel.EventLoop;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.local.LocalIoHandler;
import io.netty.util.concurrent.MockTicker;
import io.netty.util.concurrent.Ticker;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.sensepitch.edge.ProxyMetrics.CloseReason;

/// The partition of one event loop, on embedded channels: the event loop of [#loop] schedules the
/// idle timers on a mock ticker, the connections are further embedded channels
class UpstreamConnectionPoolTest {

  private static final String UPSTREAM = "backend";
  private static final long IDLE_MILLIS = 4_000;

  /// Two event loops share four connections, two each
  private static final int MAX_CONNECTIONS = 4;

  private static final int EVENT_LOOPS = 2;

  private final MockTicker ticker = Ticker.newMockTicker();
  private final EmbeddedChannel loop = EmbeddedChannel.builder().ticker(ticker).build();
  private final ProxyMetrics metrics = new ProxyMetrics();
  private final List<EmbeddedChannel> connections = new ArrayList<>();

  private final UpstreamConnectionPool pool =
      new UpstreamConnectionPool(
          ConnectionPoolConfig.builder()
              .idleTimeoutMillis(IDLE_MILLIS)
              .maxConnections(MAX_CONNECTIONS)
              .build(),
          metrics,
          UPSTREAM,
          EVENT_LOOPS);

  private final UpstreamConnectionPool.Partition partition = pool.partition(eventLoop());

  private EventLoop eventLoop() {
    return loop.eventLoop();
  }

  private EmbeddedChannel connection() {
    EmbeddedChannel channel = new EmbeddedChannel();
    connections.add(channel);
    return channel;
  }

  /// A connection opened within the share of the loop, as [DefaultUpstream] does, so its close is
  /// counted
  private EmbeddedChannel openedConnection() {
    assertThat(pool.reserve(eventLoop())).isTrue();
    EmbeddedChannel channel = connection();
    pool.opened(eventLoop(), channel);
    return channel;
  }

  @AfterEach
  void close() {
    connections.forEach(EmbeddedChannel::finishAndReleaseAll);
    loop.finishAndReleaseAll();
  }

  @Test
  void releasedLastIsTakenFirstAndMarkedReused() {
    Channel first = connection();
    Channel second = connection();
    partition.add(first);
    partition.add(second);
    assertThat(idle()).isEqualTo(2);
    Channel taken = pool.acquire(eventLoop());
    assertThat(taken).isSameAs(second);
    assertThat(taken.attr(UpstreamConnectionPool.REUSED).get()).isTrue();
    assertThat(pool.acquire(eventLoop())).isSameAs(first);
    assertThat(pool.acquire(eventLoop())).isNull();
    assertThat(reused()).isEqualTo(2);
    assertThat(idle()).isZero();
  }

  /// Backpressure of the last exchange may have paused reading; the pool reads again
  @Test
  void releaseResumesReading() {
    EmbeddedChannel channel = connection();
    channel.config().setAutoRead(false);
    pool.release(channel);
    assertThat(channel.config().isAutoRead()).isTrue();
    assertThat(pool.acquire(channel.eventLoop())).isSameAs(channel);
  }

  @Test
  void otherLoopHasNoConnections() {
    partition.add(connection());
    assertThat(pool.acquire(new EmbeddedChannel().eventLoop())).isNull();
  }

  /// After a burst more connections are released than are needed; all are kept, none closed at
  /// release, and those not taken again end by the idle timeout
  @Test
  void burstSurplusEndsByTheIdleTimeout() {
    List<Channel> burst = new ArrayList<>();
    for (int i = 0; i < 100; i++) {
      Channel channel = connection();
      burst.add(channel);
      partition.add(channel);
    }
    assertThat(burst).allMatch(Channel::isOpen);
    assertThat(partition.size()).isEqualTo(100);
    ticker.advance(IDLE_MILLIS, MILLISECONDS);
    loop.runScheduledPendingTasks();
    assertThat(burst).noneMatch(Channel::isOpen);
    assertThat(idle()).isZero();
  }

  /// An event loop opens connections up to its share of `maxConnections`; a closed one makes room
  @Test
  void eventLoopOpensUpToItsShare() {
    assertThat(pool.reserve(eventLoop())).isTrue();
    assertThat(pool.reserve(eventLoop())).isTrue();
    assertThat(pool.reserve(eventLoop())).as("share of the loop used up").isFalse();
    assertThat(open()).isEqualTo(2);
    pool.unreserve(eventLoop());
    assertThat(open()).isOne();
    assertThat(pool.reserve(eventLoop())).isTrue();
  }

  /// The share of one loop does not use up the share of another
  @Test
  void eventLoopsHaveTheirOwnShare() {
    pool.reserve(eventLoop());
    pool.reserve(eventLoop());
    EventLoop other = connection().eventLoop();
    assertThat(pool.reserve(other)).isTrue();
    assertThat(open()).isEqualTo(3);
  }

  @Test
  void idleTimeoutClosesTheConnection() {
    Channel channel = openedConnection();
    partition.add(channel);
    ticker.advance(IDLE_MILLIS - 1, MILLISECONDS);
    loop.runScheduledPendingTasks();
    assertThat(channel.isOpen()).as("open until the timeout").isTrue();
    ticker.advance(1, MILLISECONDS);
    loop.runScheduledPendingTasks();
    assertThat(channel.isOpen()).isFalse();
    assertThat(partition.size()).isZero();
    assertThat(closed(CloseReason.IDLE_TIMEOUT)).isOne();
    assertThat(idle()).isZero();
    assertThat(open()).isZero();
  }

  /// An acquired connection is no longer watched, its idle timer does not close it in use; a close
  /// by the upstream in use is counted too
  @Test
  void acquiredConnectionIsNotClosedByItsIdleTimer() {
    Channel channel = openedConnection();
    partition.add(channel);
    assertThat(pool.acquire(eventLoop())).isSameAs(channel);
    ticker.advance(IDLE_MILLIS, MILLISECONDS);
    loop.runScheduledPendingTasks();
    assertThat(channel.isOpen()).isTrue();
    channel.close();
    assertThat(closed(CloseReason.IDLE_TIMEOUT)).isZero();
    assertThat(closed(CloseReason.CLOSED_BY_UPSTREAM)).isOne();
  }

  @Test
  void connectionClosedWhileIdleIsRemoved() {
    Channel channel = openedConnection();
    partition.add(channel);
    channel.close();
    assertThat(partition.size()).isZero();
    assertThat(closed(CloseReason.CLOSED_BY_UPSTREAM)).isOne();
    assertThat(pool.acquire(eventLoop())).isNull();
  }

  @Test
  void closedConnectionIsNotKept() {
    Channel channel = connection();
    channel.close();
    partition.add(channel);
    assertThat(partition.size()).isZero();
  }

  /// A reload removed the upstream: the idle connections are closed, a connection released
  /// afterwards too
  @Test
  void closeOfThePoolClosesIdleAndLaterConnections() {
    Channel idleConnection = openedConnection();
    partition.add(idleConnection);
    pool.close();
    loop.runPendingTasks();
    assertThat(idleConnection.isOpen()).isFalse();
    assertThat(closed(CloseReason.REMOVED)).isOne();
    Channel inUse = openedConnection();
    pool.release(inUse);
    assertThat(inUse.isOpen()).isFalse();
    assertThat(closed(CloseReason.REMOVED)).isEqualTo(2);
    assertThat(idle()).isZero();
  }

  /// An opened connection is counted until it closes, with the reason of the first close; the
  /// reservation is given back
  @Test
  void openedConnectionIsCountedUntilItCloses() {
    Channel channel = openedConnection();
    assertThat(opened()).isOne();
    assertThat(open()).isOne();
    UpstreamConnectionPool.close(channel, CloseReason.NOT_REUSABLE);
    UpstreamConnectionPool.close(channel, CloseReason.REMOVED);
    assertThat(closed(CloseReason.NOT_REUSABLE)).isOne();
    assertThat(closed(CloseReason.REMOVED)).isZero();
    assertThat(open()).isZero();
  }

  /// A connection the event loop closes when it shuts down is counted as `shutdown`, not as a close
  /// by the upstream
  @Test
  void connectionClosedAtShutdownCountsShutdown() throws Exception {
    var group = new MultiThreadIoEventLoopGroup(1, LocalIoHandler.newFactory());
    EventLoop shuttingDown = group.next();
    LocalChannel channel = new LocalChannel();
    shuttingDown.register(channel).get(TestingParameters.EVENT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    shuttingDown
        .submit(
            () -> {
              pool.reserve(shuttingDown);
              pool.opened(shuttingDown, channel);
            })
        .get(TestingParameters.EVENT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    group
        .shutdownGracefully(0, TestingParameters.EVENT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .get(TestingParameters.EVENT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertThat(channel.isOpen()).isFalse();
    assertThat(closed(CloseReason.SHUTDOWN)).isOne();
    assertThat(closed(CloseReason.CLOSED_BY_UPSTREAM)).isZero();
  }

  /// An event loop that shut down closed its connections itself, the close of the pool skips it
  @Test
  void closeSkipsAnEventLoopThatShutDown() throws Exception {
    DefaultEventLoop stopped = new DefaultEventLoop();
    stopped
        .shutdownGracefully(0, TestingParameters.EVENT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .get(TestingParameters.EVENT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    pool.partition(stopped);
    pool.close();
    assertThat(closed(CloseReason.REMOVED)).isZero();
  }

  private double open() {
    return metrics.upstreamPoolConnections.labelValues(UPSTREAM).get();
  }

  private double opened() {
    return metrics.upstreamPoolConnectionsOpened.labelValues(UPSTREAM).get();
  }

  private double idle() {
    return metrics.upstreamPoolConnectionsIdle.labelValues(UPSTREAM).get();
  }

  private double reused() {
    return metrics.upstreamPoolConnectionsReused.labelValues(UPSTREAM).get();
  }

  private double closed(CloseReason reason) {
    return metrics.upstreamPoolConnectionsClosed.labelValues(UPSTREAM, reason.label).get();
  }
}
