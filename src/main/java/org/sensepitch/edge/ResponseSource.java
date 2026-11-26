package org.sensepitch.edge;

import java.util.Locale;

/// Who produced the response to the client, logged as `sensepitch.response.source`. Apart from the
/// [RequestOutcome], which says how the exchange ended for the client: a fallback page or a
/// challenge can be delivered completely or be aborted like any other response.
///
/// Reported to the [RequestLoggingHandler] by the handler that writes the response; a later report
/// wins, since a handler nearer the client replaces the response of one further in, e.g. the
/// [FallbackHandler] replaces the upstream response. [#EDGE] is derived when a response was written
/// and nothing was reported.
public enum ResponseSource {

  /// The upstream answered, the response was forwarded
  UPSTREAM,

  /// A configured static response of the site, see [ResponseConfig]
  STATIC,

  /// A fallback page replaced the error response of the upstream, see [FallbackHandler] and
  /// [RequestLogInfo#upstreamStatus()]
  FALLBACK,

  /// The edge answered itself: a challenge, a redirect or rejection, an error response after an
  /// upstream failure or a timeout, an undecodable request
  EDGE;

  private final String value = name().toLowerCase(Locale.ROOT);

  /// Logged value, the lower case name
  public String value() {
    return value;
  }
}
