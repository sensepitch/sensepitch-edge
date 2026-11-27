package org.sensepitch.edge;

import lombok.Builder;

/// @param readTimeoutSeconds if no input is received for the specified time, the request is aborted
///   with a status 408 response. The time starts when the TLS handshake was completed. Only
///   complete HTTP frames are recognized: the request head counts once it is complete, so sending
///   it byte by byte does not reset the timeout, but every piece of the body does. This timeout
///   applies also for keep alive connections. A slow upload is bounded by `requestTimeoutSeconds`.
/// @param writeTimeoutSeconds if the HTTP response or chunked content is not successfully written
///   within the specified time, the request is aborted. If the upstream server fails to send data
///   within the timeout, the request is aborted as well.
/// @param responseTimeoutSeconds if the HTTP response is not arriving within the defined time, the
///   request is aborted
/// @param requestTimeoutSeconds total time to receive the body of a request, counted from its
///   complete head. When the body is not complete by then and no response has started, the request
///   is aborted with a status 408 response and the connection is closed. Unlike
///   `readTimeoutSeconds`, receiving does not move it, so a client that trickles the body cannot
///   hold the connection and an upstream connection open without end. `0` switches it off.
/// @author Jens Wilke
@Builder(toBuilder = true)
public record ConnectionConfig(
    int readTimeoutSeconds,
    int writeTimeoutSeconds,
    int responseTimeoutSeconds,
    int requestTimeoutSeconds) {

  public static final ConnectionConfig DEFAULT =
      builder().readTimeoutSeconds(30).writeTimeoutSeconds(30).responseTimeoutSeconds(15).build();

  public ConnectionConfig {
    if (requestTimeoutSeconds < 0) {
      throw new IllegalArgumentException(
          "requestTimeoutSeconds must be 0 (off) or positive, was " + requestTimeoutSeconds);
    }
  }
}
