package org.sensepitch.edge;

import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;

/// Request log data with an anonymized client address, everything else is passed through.
///
/// The address is truncated: an IPv4 address to its `/24` network, e.g. `192.0.2.7` becomes
/// `192.0.2.0`, an IPv6 address to its `/48` network. That is common practice for GDPR compliant
/// analytics, the network of the client can still be analysed, but the client cannot be identified.
/// The client port is removed as well, since it helps to identify the client behind a shared
/// address.
///
/// Country and ASN in the IP traits are derived from the full address, they are coarse and
/// are kept.
///
/// An instance can be reused for many requests via [#wrap(RequestLogInfo)], see
/// [AnonymizingRequestLogger]. It keeps the last address and its anonymized form, compared by
/// identity: the requests of a connection share the address object, so the anonymized address is
/// computed once per connection.
///
/// @see AnonymizingRequestLogger
/// @author Jens Wilke
public class AnonymizedClientRequestLogInfo implements RequestLogInfo {

  /// Bytes kept of an IPv4 address
  static final int IPV4_KEEP_BYTES = 3;

  /// Bytes kept of an IPv6 address
  static final int IPV6_KEEP_BYTES = 6;

  private static final int IPV4_MAPPED_PREFIX_LENGTH = 12;

  private RequestLogInfo info;
  private InetAddress lastAddress;
  private InetAddress lastAnonymized;

  public AnonymizedClientRequestLogInfo(RequestLogInfo info) {
    this.info = info;
  }

  /// Wraps another request, for reuse by one thread
  ///
  /// @return this instance
  AnonymizedClientRequestLogInfo wrap(RequestLogInfo info) {
    this.info = info;
    return this;
  }

  /// Truncates the address to its `/24` (IPv4) or `/48` (IPv6) network. An IPv4 address mapped
  /// into IPv6 is handled as IPv4.
  ///
  /// @return truncated address, or `null` if the address is `null`
  public static InetAddress anonymize(InetAddress address) {
    if (address == null) {
      return null;
    }
    byte[] bytes = address.getAddress();
    if (address instanceof Inet6Address && isIpv4Mapped(bytes)) {
      bytes = Arrays.copyOfRange(bytes, IPV4_MAPPED_PREFIX_LENGTH, bytes.length);
    }
    int keep = bytes.length == 4 ? IPV4_KEEP_BYTES : IPV6_KEEP_BYTES;
    Arrays.fill(bytes, keep, bytes.length, (byte) 0);
    return byAddress(bytes);
  }

  /// The address of the truncated bytes, for [#anonymize]. Excluded from the coverage report:
  /// `InetAddress.getByAddress` throws only for a length other than 4 or 16, and the bytes come
  /// from an `InetAddress`, so the `catch` cannot be reached.
  @ExcludeFromJacocoGeneratedReport
  private static InetAddress byAddress(byte[] bytes) {
    try {
      return InetAddress.getByAddress(bytes);
    } catch (UnknownHostException e) {
      throw new IllegalStateException(e);
    }
  }

  /// `::ffff:a.b.c.d`
  private static boolean isIpv4Mapped(byte[] bytes) {
    for (int i = 0; i < 10; i++) {
      if (bytes[i] != 0) {
        return false;
      }
    }
    return bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff;
  }

  @Override
  public InetAddress clientAddress() {
    InetAddress address = info.clientAddress();
    if (address != lastAddress) {
      lastAnonymized = anonymize(address);
      lastAddress = address;
    }
    return lastAnonymized;
  }

  @Override
  public int clientPort() {
    return 0;
  }

  @Override
  public int serverPort() {
    return info.serverPort();
  }

  @Override
  public RequestOutcome outcome() {
    return info.outcome();
  }

  @Override
  public int upstreamStatus() {
    return info.upstreamStatus();
  }

  @Override
  public String route() {
    return info.route();
  }

  @Override
  public String configId() {
    return info.configId();
  }

  @Override
  public String protectionOutcome() {
    return info.protectionOutcome();
  }

  @Override
  public String upstreamName() {
    return info.upstreamName();
  }

  @Override
  public String upstreamTarget() {
    return info.upstreamTarget();
  }

  @Override
  public String tlsProtocol() {
    return info.tlsProtocol();
  }

  @Override
  public String tlsCipher() {
    return info.tlsCipher();
  }

  @Override
  public String connectionId() {
    return info.connectionId();
  }

  @Override
  public int connectionRequestCount() {
    return info.connectionRequestCount();
  }

  @Override
  public String requestId() {
    return info.requestId();
  }

  @Override
  public String traceId() {
    return info.traceId();
  }

  @Override
  public String spanId() {
    return info.spanId();
  }

  @Override
  public String traceFlags() {
    return info.traceFlags();
  }

  @Override
  public String clientTraceparent() {
    return info.clientTraceparent();
  }

  @Override
  public HttpMethod method() {
    return info.method();
  }

  @Override
  public String uri() {
    return info.uri();
  }

  @Override
  public HttpVersion protocolVersion() {
    return info.protocolVersion();
  }

  @Override
  public String ingress() {
    return info.ingress();
  }

  @Override
  public String country() {
    return info.country();
  }

  @Override
  public String asn() {
    return info.asn();
  }

  @Override
  public String ipTraits() {
    return info.ipTraits();
  }

  @Override
  public String ja4() {
    return info.ja4();
  }

  @Override
  public String admissionToken() {
    return info.admissionToken();
  }

  @Override
  public String admissionOriginalToken() {
    return info.admissionOriginalToken();
  }

  @Override
  public long admissionRevisits() {
    return info.admissionRevisits();
  }

  @Override
  public String bypassReason() {
    return info.bypassReason();
  }

  @Override
  public String trafficType() {
    return info.trafficType();
  }

  @Override
  public HttpHeaders clientHeaders() {
    return info.clientHeaders();
  }

  @Override
  public String requestHeaderHost() {
    return info.requestHeaderHost();
  }

  @Override
  public HttpResponseStatus responseStatus() {
    return info.responseStatus();
  }

  @Override
  public String responseContentType() {
    return info.responseContentType();
  }

  @Override
  public long contentBytes() {
    return info.contentBytes();
  }

  @Override
  public long bytesSent() {
    return info.bytesSent();
  }

  @Override
  public long bytesReceived() {
    return info.bytesReceived();
  }

  @Override
  public long requestBodyBytes() {
    return info.requestBodyBytes();
  }

  @Override
  public long requestStartTimeMillis() {
    return info.requestStartTimeMillis();
  }

  @Override
  public Throwable error() {
    return info.error();
  }

  @Override
  public long receiveDurationNanos() {
    return info.receiveDurationNanos();
  }

  @Override
  public long responseTimeNanos() {
    return info.responseTimeNanos();
  }

  @Override
  public long totalDurationNanos() {
    return info.totalDurationNanos();
  }
}
