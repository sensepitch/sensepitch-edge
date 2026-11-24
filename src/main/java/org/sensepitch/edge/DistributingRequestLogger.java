package org.sensepitch.edge;

/// Passes each request to all loggers, and closes all of them.
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

  // FIXME: never called, Proxy closes the request log destinations directly; remove or close the
  // DistributingRequestLogger in Proxy#shutdown() instead
  @Override
  public void close() {
    for (RequestLogger requestLogger : requestLogger) {
      requestLogger.close();
    }
  }
}
