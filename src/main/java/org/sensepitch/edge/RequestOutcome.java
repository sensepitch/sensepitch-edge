package org.sensepitch.edge;

import java.util.Locale;

/// How the exchange ended for the client, logged as `sensepitch.request.outcome`: completed, or why
/// not. Who produced the response is the [ResponseSource]. The values are few, so they are suited
/// for grouping in dashboards: is it the edge, the upstream or the client? The same values are
/// counted in the metric `sensepitch_ingress_request_outcomes_total{ingress,outcome}`, see
/// [ProxyMetrics#countRequestOutcome].
///
/// [#COMPLETED] is derived when nothing else was reported. The others are reported by the handler
/// that ended the exchange, the first report wins.
///
/// @author Jens Wilke
public enum RequestOutcome {

  /// The response was sent completely, whoever produced it, see [ResponseSource]
  COMPLETED,

  /// The connection to the upstream failed
  UPSTREAM_CONNECT_FAILED,

  /// Forwarding the request to the upstream failed
  UPSTREAM_WRITE_FAILED,

  /// The upstream closed the connection before the response was complete
  UPSTREAM_CLOSED,

  /// The upstream did not respond within the response timeout
  UPSTREAM_TIMEOUT,

  /// The client did not complete the request within the read timeout
  REQUEST_TIMEOUT,

  /// The request line or header was larger than the HTTP decoder accepts, answered with 414 or
  /// 431, see [DecoderFailureHandler]
  REQUEST_TOO_LARGE,

  /// The client did not take the response within the write timeout
  RESPONSE_WRITE_TIMEOUT,

  /// The connection was closed before the response was complete
  CLIENT_ABORTED,

  /// Another error, answered with a server error by the edge
  EDGE_ERROR;

  private final String value = name().toLowerCase(Locale.ROOT);

  /// Logged value, the lower case name
  public String value() {
    return value;
  }
}
