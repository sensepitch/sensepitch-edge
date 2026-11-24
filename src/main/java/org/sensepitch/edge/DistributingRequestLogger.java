package org.sensepitch.edge;

/// Passes each request to all loggers. Closing is left to the owners of the loggers, e.g. the
/// [Proxy] closes the request log destinations on shutdown.
///
/// @author Jens Wilke
public class DistributingRequestLogger implements RequestLogger {

  private final RequestLogger[] requestLogger;

  public DistributingRequestLogger(RequestLogger... requestLogger) {
    this.requestLogger = requestLogger;
  }

  @Override
  public void logRequest(RequestLogInfo info) {
    for (RequestLogger requestLogger : requestLogger) {
      requestLogger.logRequest(info);
    }
  }
}
