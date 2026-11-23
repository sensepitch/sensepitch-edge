package org.sensepitch.edge;

import static org.sensepitch.edge.TestingParameters.EVENT_TIMEOUT_SECONDS;

import com.google.protobuf.InvalidProtocolBufferException;
import com.sun.net.httpserver.HttpExchange;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

/// Reads what an OTLP request log destination sends, with the official OTLP message classes: on
/// the server side from an [HttpExchange] of a test log server, on the client side from the
/// [HttpRequest] a [FakeLogServer] recorded.
final class OtlpTestSupport {

  /// The attribute of a record that tells the requests of a test apart
  static final String PATH_ATTRIBUTE = "url.path";

  private OtlpTestSupport() {}

  /// The request of a post, decompressed if the sender compressed it
  static ExportLogsServiceRequest decode(HttpExchange exchange) throws IOException {
    return decode(exchange, exchange.getRequestBody().readAllBytes());
  }

  /// [#decode(HttpExchange)] for a body the server already read, e.g. to count its bytes
  static ExportLogsServiceRequest decode(HttpExchange exchange, byte[] body) throws IOException {
    boolean gzip =
        OtlpConfig.GZIP.equals(exchange.getRequestHeaders().getFirst("Content-Encoding"));
    return ExportLogsServiceRequest.parseFrom(gzip ? gunzip(body) : body);
  }

  /// The body of a post with `Content-Encoding: gzip`, as a log server decodes it
  static byte[] gunzip(byte[] body) throws IOException {
    try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(body))) {
      return in.readAllBytes();
    }
  }

  /// The attributes of a record by key, for assertions on single values
  static Map<String, AnyValue> attributes(List<KeyValue> keyValues) {
    return keyValues.stream().collect(Collectors.toMap(KeyValue::getKey, KeyValue::getValue));
  }

  /// One scrape of the metrics of the destination
  static ScrapedMetrics scrape(OtlpLogDestination destination) {
    PrometheusRegistry registry = new PrometheusRegistry();
    destination.metrics().registerCollectors(registry::register);
    return new ScrapedMetrics(registry.scrape());
  }

  /// The bytes the destination holds, sent or waiting, as the gauge shows them
  static long bufferedBytesGauge(OtlpLogDestination destination) {
    return (long)
        scrape(destination)
            .gauge(
                RequestLogMetrics.BUFFERED_BYTES,
                RequestLogMetrics.DESTINATION_LABEL,
                destination.destination())
            .getValue();
  }

  /// The paths of the records in an uncompressed request a [FakeLogServer] recorded
  static List<String> paths(HttpRequest request) {
    ExportLogsServiceRequest body;
    try {
      body = ExportLogsServiceRequest.parseFrom(body(request));
    } catch (InvalidProtocolBufferException e) {
      throw new AssertionError("OTLP body", e);
    }
    return body.getResourceLogs(0).getScopeLogs(0).getLogRecordsList().stream()
        .map(record -> attributes(record.getAttributesList()).get(PATH_ATTRIBUTE).getStringValue())
        .toList();
  }

  /// The bytes of the request body, as the HTTP client would send them
  static byte[] body(HttpRequest request) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    CompletableFuture<byte[]> done = new CompletableFuture<>();
    request
        .bodyPublisher()
        .orElseThrow()
        .subscribe(
            new Flow.Subscriber<>() {
              @Override
              public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
              }

              @Override
              public void onNext(ByteBuffer buffer) {
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
              }

              @Override
              public void onError(Throwable failure) {
                done.completeExceptionally(failure);
              }

              @Override
              public void onComplete() {
                done.complete(bytes.toByteArray());
              }
            });
    try {
      return done.get(EVENT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (Exception e) {
      throw new AssertionError("request body", e);
    }
  }
}
