package org.sensepitch.edge;

import java.util.ArrayList;
import java.util.List;

/// In-memory destination of the request log, which otherwise goes to standard out. Pass it to
/// [Proxy#Proxy(ProxyConfig, RequestLogOutput)] or use [#plainText()] directly in a handler
/// pipeline.
///
/// @author Jens Wilke
public class RequestLogCapture implements RequestLogOutput {

  /// Event loop threads may write concurrently
  private final Object lock = new Object();

  // guarded by lock
  private final List<String> lines = new ArrayList<>();
  private final List<RequestOutcome> outcomes = new ArrayList<>();
  private final List<ResponseSource> sources = new ArrayList<>();

  @Override
  public void writeLine(String line) {
    synchronized (lock) {
      lines.add(line);
    }
  }

  /// Request logger writing the plain text format into this capture. It also records the outcome
  /// and the response source of each request, which the plain text format does not show, see
  /// [#outcomes()] and [#sources()].
  public RequestLogger plainText() {
    PlainTextRequestLogger text = new PlainTextRequestLogger(this);
    return info -> {
      synchronized (lock) {
        outcomes.add(info.outcome());
        sources.add(info.responseSource());
      }
      text.logRequest(info);
    };
  }

  /// Outcomes of the requests logged via [#plainText()] so far
  public List<RequestOutcome> outcomes() {
    synchronized (lock) {
      return List.copyOf(outcomes);
    }
  }

  /// Response sources of the requests logged via [#plainText()] so far, `null` where no response
  /// was written
  public List<ResponseSource> sources() {
    synchronized (lock) {
      return new ArrayList<>(sources);
    }
  }

  /// Request log lines written so far
  public List<String> lines() {
    synchronized (lock) {
      return List.copyOf(lines);
    }
  }
}
