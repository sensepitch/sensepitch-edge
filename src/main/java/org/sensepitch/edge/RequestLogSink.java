package org.sensepitch.edge;

import java.net.InetAddress;

/// Receives the fields of one request log record from [RequestLogFieldsWriter], implemented by each
/// output format: the JSON sink inside [JsonRequestLogger] and [OtlpRequestLogSink]. First come the
/// record level fields via [#record] and [#body], then the attributes in a stable order.
///
/// The interface is designed so that the mapping does not allocate: values are passed as parts,
/// ranges or primitives, and arrays are streamed. A sink that renders text may still allocate.
///
/// @param <E> the checked exception the sink may throw, e.g. `IOException` of the JSON generator,
///   `RuntimeException` for a sink that throws none; so a caller catches it once per record and
///   not each method of the sink
///
/// @author Jens Wilke
public interface RequestLogSink<E extends Exception> {

  /// Record level fields, which OTLP keeps outside the attributes
  ///
  /// @param timeMillis start of the request
  /// @param observedTimeMillis time the record was produced, when the request was completed
  /// @param severity [RequestLogFieldsWriter.Severity#WARN] for server errors and failed requests
  /// @param traceId W3C trace id or `null`
  /// @param spanId W3C span id or `null`, the request id
  /// @param traceFlags W3C trace flags or `null`
  void record(
      long timeMillis,
      long observedTimeMillis,
      RequestLogFieldsWriter.Severity severity,
      String traceId,
      String spanId,
      String traceFlags)
      throws E;

  /// Parts of a short summary for log viewers, `<method> <uri> <status>`
  void body(String method, String uri, int status) throws E;

  void string(String key, CharSequence value) throws E;

  /// String attribute with the characters `start` (inclusive) to `end` (exclusive) of the value
  void string(String key, CharSequence value, int start, int end) throws E;

  /// IP address attribute, rendered like [InetAddress#getHostAddress()]
  void address(String key, InetAddress address) throws E;

  void number(String key, long value) throws E;

  void bool(String key, boolean value) throws E;

  /// Starts a string array, followed by [#arrayValue(CharSequence)] calls and [#endArray()]
  void startArray(String key) throws E;

  void arrayValue(CharSequence value) throws E;

  void endArray() throws E;

  /// String attribute, left out if the value is `null`
  default void optionalString(String key, CharSequence value) throws E {
    if (value != null) {
      string(key, value);
    }
  }

  /// IP address attribute, left out if the address is `null`
  default void optionalAddress(String key, InetAddress address) throws E {
    if (address != null) {
      address(key, address);
    }
  }

  /// Number attribute, left out if the value is not positive, e.g. an unknown port
  default void optionalNumber(String key, long value) throws E {
    if (value > 0) {
      number(key, value);
    }
  }
}
