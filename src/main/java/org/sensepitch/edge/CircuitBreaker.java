package org.sensepitch.edge;

import io.netty.util.concurrent.Ticker;
import java.time.InstantSource;
import java.util.concurrent.TimeUnit;

/// Stops requests to a remote that failed, and lets a single probe through after a backoff.
///
/// - `CLOSED`: requests pass, see [#tryAcquire()]. Failures are counted, a success or an ignored
///   failure resets the count.
/// - `OPEN`: after [Policy#failureThreshold()] failures in a row, see [#onFailure(long, String)],
///   requests are refused until the backoff is over. A failure of a request that was still in
///   flight when the breaker opened does not count again.
/// - `HALF_OPEN`: once the backoff is over, exactly one probe is let through. Its success closes
///   the breaker, as does an ignored failure, see [#onIgnored(String)], since the server
///   answered. Its failure opens it again with a doubled backoff. A probe that reports nothing
///   within the probe timeout lets the next one through, so the breaker cannot get stuck.
/// - `FORCED_OPEN`: refuses every request until [#forceClose()] closes it again, for taking a
///   remote out for maintenance through the admin endpoint, see [AdminServer].
///
/// Who probes is set by the [Policy]: by default the next request after the backoff is the probe,
/// granted by [#tryAcquire()]. With [Policy#callerProbes()] requests are only granted while
/// closed, and the caller sends a probe of its own after [#tryProbe()] granted it, e.g. to a
/// health endpoint, so no request waits for a probe. Such a breaker may start open, see
/// [Policy#startOpen()], so the remote is probed before it gets requests.
///
/// The breaker is passive: it has no thread or timer, a caller that wants to retry or probe by
/// itself schedules after [#retryDelayNanos()]. It is used for posting request logs, see
/// [HttpBatchSender], with [Policy#OPEN_ON_FIRST_FAILURE], which suits a sender that retries one
/// batch after a failure. An upstream fails one request at a time under load, so its breaker
/// needs a higher threshold, see `doc/design/circuit-breaker.md`.
///
/// Called for each request on an event loop, [#tryAcquire()] and [#onSuccess()] in the closed
/// state read volatile fields only, they neither lock nor allocate; so does a refusal while open.
/// State changes take an internal lock. The failures in a row are counted across threads, so a
/// success that runs at the same time as a failure may not reset it.
///
/// Every outcome, refusal and state change is reported to the [Events], e.g. the metrics, see
/// [CircuitBreakerMetrics]. The breaker keeps the wall clock times of its start, its last trip and
/// its last restart for them.
///
/// The durations, the backoff and the probe timeout, come from a [Ticker]; the wall clock times
/// for the metrics come from the system clock, so they match the timestamps of the logs. A test
/// controls both, see [#CircuitBreaker(Backoff, Policy, long, Events, Ticker, InstantSource)].
///
/// @author Jens Wilke
public final class CircuitBreaker {

  public enum State {
    CLOSED,
    OPEN,
    HALF_OPEN,
    FORCED_OPEN
  }

  /// What happened to a call, the `kind` of the metrics, named as in resilience4j
  public enum Kind {
    /// succeeded, see [#onSuccess()]
    SUCCESSFUL,
    /// failed and counted by the breaker, see [#onFailure(long, String)]
    FAILED,
    /// failed in a way the breaker does not count, see [#onIgnored(String)]
    IGNORED,
    /// refused by the breaker, see [#tryAcquire()]
    NOT_PERMITTED
  }

  /// When the breaker opens and who probes, see the class doc
  ///
  /// @param failureThreshold failures in a row that open the breaker, at least 1
  /// @param callerProbes requests are granted only while closed, the caller probes after
  ///   [#tryProbe()] granted it
  /// @param startOpen the breaker starts open with the backoff over, so the first [#tryProbe()]
  ///   is granted at once; requires `callerProbes`, otherwise the first request would be the probe
  public record Policy(int failureThreshold, boolean callerProbes, boolean startOpen) {

    /// Opens on the first failure, the next request after the backoff is the probe
    public static final Policy OPEN_ON_FIRST_FAILURE = new Policy(1, false, false);

    /// @throws IllegalArgumentException if `failureThreshold` is below 1, or `startOpen` is set
    ///   without `callerProbes`
    public Policy {
      if (failureThreshold < 1) {
        throw new IllegalArgumentException("failureThreshold must be at least 1");
      }
      if (startOpen && !callerProbes) {
        throw new IllegalArgumentException("startOpen requires callerProbes");
      }
    }
  }

  /// Receives what the breaker does, mostly with the internal lock of the breaker held, so an
  /// implementation must not call back into the breaker. A refusal and a success while closed are
  /// reported without the lock.
  public interface Events {

    /// A call ended or was refused
    void call(Kind kind);

    /// A call failed, as [Kind#FAILED] or [Kind#IGNORED]
    ///
    /// @param error a short code, e.g. `http_503` or `timeout`, never a message
    void error(String error);

    /// The state changed
    ///
    /// @param reason the error code for a change to `OPEN`, otherwise `backoff_over` or
    ///   `recovered`
    void transition(State from, State to, String reason);
  }

  static final String BACKOFF_OVER = "backoff_over";
  static final String RECOVERED = "recovered";
  static final String FORCED_OPEN_REASON = "forced_open";
  static final String FORCED_CLOSE_REASON = "forced_close";

  private final Backoff backoff;
  private final Policy policy;
  private final long probeTimeoutNanos;
  private final Ticker ticker;

  /// Wall clock, for the start, trip and restart times
  private final InstantSource wallClock;

  private final long startMillis;

  private final Events events;

  /// Guards all state changes and the fields below, private so no outside code can hold it
  private final Object lock = new Object();

  /// Written under the lock, volatile for the lock free read in [#tryAcquire()] and [#state()]
  private volatile State state = State.CLOSED;

  /// Failures in a row since the last success, compared with the threshold. Written under the
  /// lock, volatile for the lock free check in [#onSuccess()].
  private volatile int failures;

  /// Written under the lock, volatile for the lock free refusal in [#tryAcquire()]
  private volatile long retryAtNanos;

  /// Written under the lock, volatile for the lock free refusal in [#tryAcquire()]
  private volatile long probeSinceNanos;

  // guarded by lock

  /// Failures since the breaker opened, the trip and the failed probes; the backoff grows with
  /// them
  private int openFailures;

  /// Wall clock time of the last change from closed to open, `0` if none
  private long lastTripMillis;

  /// Wall clock time of the last close after a trip, `0` if none
  private long lastRestartMillis;

  /// @param probeTimeoutMillis time after which a probe that reported nothing is replaced, at
  ///   least the request timeout
  /// @param events receives what the breaker does, e.g. [CircuitBreakerMetrics#events(String, String)]
  public CircuitBreaker(Backoff backoff, Policy policy, long probeTimeoutMillis, Events events) {
    this(
        backoff, policy, probeTimeoutMillis, events, Ticker.systemTicker(), InstantSource.system());
  }

  /// With a ticker and a wall clock, for tests
  ///
  /// @param wallClock read at the start, a trip and a restart
  CircuitBreaker(
      Backoff backoff,
      Policy policy,
      long probeTimeoutMillis,
      Events events,
      Ticker ticker,
      InstantSource wallClock) {
    this.backoff = backoff;
    this.policy = policy;
    this.events = events;
    this.probeTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(probeTimeoutMillis);
    this.ticker = ticker;
    this.wallClock = wallClock;
    this.startMillis = wallClock.millis();
    if (policy.startOpen()) {
      // never up, so no trip: no transition and no trip time
      state = State.OPEN;
      retryAtNanos = ticker.nanoTime();
    }
  }

  /// Asks for permission to send a request.
  ///
  /// @return `true` if the request may be sent; in the half open state it is the probe and must
  ///   be followed by [#onSuccess()], [#onIgnored(String)] or [#onFailure(long, String)]. With
  ///   [Policy#callerProbes()] only granted while closed. A refusal is reported as
  ///   [Kind#NOT_PERMITTED].
  public boolean tryAcquire() {
    State current = state;
    if (current == State.CLOSED) {
      return true;
    }
    if (refusedWithoutLock(current)) {
      events.call(Kind.NOT_PERMITTED);
      return false;
    }
    return tryAcquireSlow();
  }

  /// Whether a request is refused without taking the lock, because no probe is due or the caller
  /// probes: during an outage every request of every event loop is refused, they must not
  /// contend on the lock. A due probe is granted under the lock, see [#tryAcquireSlow()].
  private boolean refusedWithoutLock(State current) {
    return switch (current) {
      case OPEN -> policy.callerProbes() || ticker.nanoTime() - retryAtNanos < 0;
      case HALF_OPEN ->
          policy.callerProbes() || ticker.nanoTime() - probeSinceNanos < probeTimeoutNanos;
      default -> true;
    };
  }

  /// Grants the probe for [#tryAcquire()], re-checked under the lock
  private boolean tryAcquireSlow() {
    synchronized (lock) {
      State current = state;
      if (current == State.CLOSED) {
        // closed by another thread between the volatile read and taking the lock
        return true;
      }
      if (grantProbe(current)) {
        return true;
      }
      // the probe was granted to another thread, or the breaker was forced open meanwhile
      events.call(Kind.NOT_PERMITTED);
      return false;
    }
  }

  /// Asks for permission to send a probe of the caller's own, with [Policy#callerProbes()]. Not
  /// counted as a call; the caller reports the result of the probe as for a request. Schedule it
  /// after [#retryDelayNanos()].
  ///
  /// @return `true` if the probe may be sent: the backoff is over, or the last probe reported
  ///   nothing within the probe timeout
  /// @throws IllegalStateException without [Policy#callerProbes()], the requests are the probes
  public boolean tryProbe() {
    if (!policy.callerProbes()) {
      throw new IllegalStateException("the requests are the probes, see Policy.callerProbes");
    }
    synchronized (lock) {
      return grantProbe(state);
    }
  }

  /// Grants the probe if one is due, for both kinds of probe. Call with the lock held.
  private boolean grantProbe(State current) {
    long now = ticker.nanoTime();
    switch (current) {
      case OPEN -> {
        if (now - retryAtNanos < 0) {
          return false;
        }
        state = State.HALF_OPEN;
        events.transition(State.OPEN, State.HALF_OPEN, BACKOFF_OVER);
        probeSinceNanos = now;
        return true;
      }
      case HALF_OPEN -> {
        if (now - probeSinceNanos < probeTimeoutNanos) {
          return false;
        }
        probeSinceNanos = now;
        return true;
      }
      default -> {
        return false;
      }
    }
  }

  /// A request succeeded, the breaker closes.
  ///
  /// @return the state before, not `CLOSED` means the remote recovered
  public State onSuccess() {
    if (state == State.CLOSED && failures == 0) {
      // per request on an event loop, nothing to change
      events.call(Kind.SUCCESSFUL);
      return State.CLOSED;
    }
    synchronized (lock) {
      events.call(Kind.SUCCESSFUL);
      return answered();
    }
  }

  /// A request failed in a way a retry cannot fix, e.g. a status 400, reported as [Kind#IGNORED].
  /// It does not open the breaker. If the breaker is not closed, it closes as on [#onSuccess()]:
  /// the server answered, so it is up. Only releasing the probe would let the next probe through
  /// at once, which then most likely succeeds; closing saves that round and the slow start of the
  /// caller begins right away.
  ///
  /// @param error short code of the error, see [Events#error(String)]
  /// @return the state before, not `CLOSED` means the remote recovered
  public State onIgnored(String error) {
    synchronized (lock) {
      events.call(Kind.IGNORED);
      events.error(error);
      return answered();
    }
  }

  /// The remote answered, for [#onSuccess()] and [#onIgnored(String)]: resets the failures in a
  /// row, and closes the breaker unless it is forced open. Call with the lock held.
  ///
  /// @return the state before
  private State answered() {
    State before = state;
    switch (before) {
      case CLOSED -> failures = 0;
      case FORCED_OPEN -> {
        // a request in flight when the breaker was forced open must not close it
      }
      default -> {
        state = State.CLOSED;
        failures = 0;
        openFailures = 0;
        lastRestartMillis = wallClock.millis();
        events.transition(before, State.CLOSED, RECOVERED);
      }
    }
    return before;
  }

  /// A request failed in a way that a later attempt may fix, e.g. a connection error or a status
  /// 503. At the [Policy#failureThreshold()] of failures in a row, and on a failed probe, the
  /// breaker opens for the backoff, or longer if the server requested it.
  ///
  /// @param retryAfterMillis delay the server requested via `Retry-After`, `0` if none
  /// @param error short code of the error, see [Events#error(String)], also the reason of the
  ///   transition to `OPEN`
  /// @return the state before: `CLOSED` means an outage starts, unless the breaker stays closed
  ///   below the threshold, see [#state()]; `OPEN` means the failure was counted already, the
  ///   caller does not need to schedule another retry; `FORCED_OPEN` means the failure was a
  ///   request in flight when the breaker was forced open and changed nothing
  public State onFailure(long retryAfterMillis, String error) {
    synchronized (lock) {
      events.call(Kind.FAILED);
      events.error(error);
      State before = state;
      if (before == State.FORCED_OPEN) {
        // a request in flight when the breaker was forced open must not close or reopen it
        return before;
      }
      long now = ticker.nanoTime();
      if (before == State.OPEN) {
        // a request in flight when the breaker opened, only a longer requested delay matters
        retryAtNanos =
            Math.max(retryAtNanos, now + TimeUnit.MILLISECONDS.toNanos(retryAfterMillis));
        return before;
      }
      failures++;
      if (before == State.CLOSED && failures < policy.failureThreshold()) {
        return before;
      }
      long delayMillis = backoff.delayMillis(openFailures++, retryAfterMillis);
      retryAtNanos = now + TimeUnit.MILLISECONDS.toNanos(delayMillis);
      state = State.OPEN;
      if (before == State.CLOSED) {
        lastTripMillis = wallClock.millis();
      }
      events.transition(before, State.OPEN, error);
      return before;
    }
  }

  /// Ends the backoff now, the next [#tryAcquire()] lets a probe through. Used for a last attempt
  /// when shutting down.
  public void skipBackoff() {
    synchronized (lock) {
      if (state == State.OPEN) {
        retryAtNanos = ticker.nanoTime();
      }
    }
  }

  /// Forces the breaker open until [#forceClose()], whatever its state, for taking a remote out
  /// for maintenance through the admin endpoint. Refuses every request, see [#tryAcquire()], and
  /// does not end by a backoff or a probe. Calling it again is a no-op.
  public void forceOpen() {
    synchronized (lock) {
      if (state == State.FORCED_OPEN) {
        return;
      }
      State before = state;
      state = State.FORCED_OPEN;
      events.transition(before, State.FORCED_OPEN, FORCED_OPEN_REASON);
    }
  }

  /// Closes a breaker forced open by [#forceOpen()], so requests pass again. The failures are
  /// reset, the next failure starts a fresh backoff.
  ///
  /// @return `true` if the breaker was forced open and is now closed, `false` if it was not
  ///   forced open, then its state is unchanged
  public boolean forceClose() {
    synchronized (lock) {
      if (state != State.FORCED_OPEN) {
        return false;
      }
      state = State.CLOSED;
      failures = 0;
      openFailures = 0;
      events.transition(State.FORCED_OPEN, State.CLOSED, FORCED_CLOSE_REASON);
      return true;
    }
  }

  /// Time until the backoff is over and the breaker lets a probe through, `0` if it is over.
  /// Meaningful while open.
  public long retryDelayNanos() {
    synchronized (lock) {
      return Math.max(0, retryAtNanos - ticker.nanoTime());
    }
  }

  public State state() {
    return state;
  }

  /// Failed calls in a row since the last success; below the threshold while closed
  public int failures() {
    return failures;
  }

  /// Wall clock time the breaker was created, in milliseconds
  public long startMillis() {
    return startMillis;
  }

  /// Wall clock time of the last change from closed to open, `0` if none
  public long lastTripMillis() {
    synchronized (lock) {
      return lastTripMillis;
    }
  }

  /// Wall clock time of the last close after a trip, `0` if none
  public long lastRestartMillis() {
    synchronized (lock) {
      return lastRestartMillis;
    }
  }
}
