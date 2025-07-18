package org.sensepitch.edge;

import lombok.Builder;

/**
 *
 * @param readTimeoutSeconds if an HTTP request of following chunked content is not received
 *                           for the specified time, the request is aborted. If possible,
 *                           the server will respond with status 408 and close the connection.
 * @param writeTimeoutSeconds if the HTTP response or chunked content is not successfully written
 *                            within the specified time, the request is aborted.
 *                            If the upstream server fails to send data within the timeout, the
 *                            request will be aborted as well.
 * @param responseTimeoutSeconds if the HTTP response is not arriving within the defined tme,
 *                               the request is aborted
 * @param keepAliveSeconds Time the connection is kept open with no activity
 *                         FIXME: Should 0 turn off keep alive support?
 *
 * @author Jens Wilke
 */
@Builder(toBuilder = true)
public record ConnectionConfig (
  int readTimeoutSeconds,
  int writeTimeoutSeconds,
  int keepAliveSeconds,
  int responseTimeoutSeconds
) {

  public static final ConnectionConfig DEFAULT = builder()
    .readTimeoutSeconds(15)
    .writeTimeoutSeconds(15)
    .keepAliveSeconds(30)
    .responseTimeoutSeconds(30)
    .build();

}
