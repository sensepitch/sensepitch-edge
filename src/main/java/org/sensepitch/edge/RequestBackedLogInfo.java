package org.sensepitch.edge;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;

/// A [RequestLogInfo] that derives the values of the request from the `HttpRequest` as it was sent
/// to the upstream, with the headers the edge added. The one place that knows which header carries
/// which value; the loggers see [RequestLogInfo] only.
///
/// @author Jens Wilke
interface RequestBackedLogInfo extends RequestLogInfo {

  /// The request received from the client with the headers the edge added, as it was sent to the
  /// upstream. If no request was received but an error response was generated, a mock request with
  /// the method [#NIL_METHOD].
  ///
  /// @return the request or the mock, never `null`
  HttpRequest request();

  @Override
  default HttpMethod method() {
    return request().method();
  }

  @Override
  default String uri() {
    return request().uri();
  }

  @Override
  default HttpVersion protocolVersion() {
    return request().protocolVersion();
  }

  @Override
  default String requestHeaderHost() {
    return request().headers().get(HttpHeaderNames.HOST);
  }

  @Override
  default String ingress() {
    return ProxyMetrics.ingress(request());
  }

  @Override
  default String country() {
    return request().headers().get(IpTraitsHandler.COUNTRY_HEADER);
  }

  @Override
  default String asn() {
    return request().headers().get(IpTraitsHandler.ASN_HEADER);
  }

  @Override
  default String ipTraits() {
    return IpTraitsHandler.extract(request());
  }

  @Override
  default String ja4() {
    return request().headers().get(Ja4HeaderHandler.JA4_HEADER);
  }

  @Override
  default String admissionToken() {
    return request().headers().get(Deflector.ADMISSION_TOKEN_HEADER);
  }

  @Override
  default String admissionOriginalToken() {
    return request().headers().get(Deflector.ADMISSION_ORIGINAL_TOKEN_HEADER);
  }

  @Override
  default long admissionRevisits() {
    String revisits = request().headers().get(Deflector.ADMISSION_REVISITS_HEADER);
    return revisits != null ? Long.parseLong(revisits) : -1;
  }

  @Override
  default String bypassReason() {
    return request().headers().get(BypassCheck.HEADER);
  }

  @Override
  default String trafficType() {
    return request().headers().get(Deflector.TRAFFIC_TYPE_HEADER);
  }

  @Override
  default HttpHeaders clientHeaders() {
    return request().headers();
  }
}
