package org.sensepitch.edge;

import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/// A [RequestLogInfo] for testing request loggers and metrics without a pipeline. Byte counts,
/// durations and the trace context are fixed values. The `with…` methods set a value and return
/// this instance, each test creates its own via [#of(HttpMethod, int)].
///
/// @author Jens Wilke
final class TestRequestLogInfo implements RequestBackedLogInfo {

  static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
  static final String SPAN_ID = "00f067aa0ba902b7";

  private final HttpRequest request;
  private final HttpResponse response;
  private InetAddress clientAddress;
  private String tlsProtocol;
  private Throwable error;
  private String upstreamName;
  private String upstreamTarget;
  private RequestOutcome outcome = RequestOutcome.COMPLETED;
  private int upstreamStatus;
  private String route;
  private String configId;
  private String protectionOutcome;
  private List<CharSequence> clientHeaderNames;
  private List<String> sampleHeaders;
  private ResponseSource responseSource;

  private TestRequestLogInfo(HttpRequest request, HttpResponse response) {
    this.request = request;
    this.response = response;
  }

  /// Request for `example.com/` with the given method and response status, no client address
  static TestRequestLogInfo of(HttpMethod method, int status) {
    HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, method, "/");
    request.headers().set(HttpHeaderNames.HOST, "example.com");
    HttpResponse response =
        new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(status));
    return new TestRequestLogInfo(request, response);
  }

  /// A successful `GET` of the path, the record the request logger tests log by the hundred,
  /// told apart by their paths
  static TestRequestLogInfo request(String path) {
    TestRequestLogInfo info = of(HttpMethod.GET, 200);
    info.request().setUri(path);
    return info;
  }

  TestRequestLogInfo withClientAddress(String ip) {
    try {
      clientAddress = InetAddress.getByName(ip);
    } catch (UnknownHostException e) {
      throw new IllegalArgumentException(e);
    }
    return this;
  }

  /// With a TLS connection, cipher is always `TLS_AES_128_GCM_SHA256`
  TestRequestLogInfo withTls(String protocol) {
    tlsProtocol = protocol;
    return this;
  }

  TestRequestLogInfo withError(Throwable error) {
    this.error = error;
    return this;
  }

  /// With an upstream that served the request
  TestRequestLogInfo withUpstream(String name, String target) {
    upstreamName = name;
    upstreamTarget = target;
    return this;
  }

  TestRequestLogInfo withOutcome(RequestOutcome outcome, int upstreamStatus) {
    this.outcome = outcome;
    this.upstreamStatus = upstreamStatus;
    return this;
  }

  /// With who produced the response, see [ResponseSource]
  TestRequestLogInfo withResponseSource(ResponseSource source) {
    responseSource = source;
    return this;
  }

  @Override
  public ResponseSource responseSource() {
    return responseSource;
  }

  TestRequestLogInfo withRoute(String route) {
    this.route = route;
    return this;
  }

  TestRequestLogInfo withConfigId(String configId) {
    this.configId = configId;
    return this;
  }

  TestRequestLogInfo withProtectionOutcome(String protectionOutcome) {
    this.protectionOutcome = protectionOutcome;
    return this;
  }

  @Override
  public HttpRequest request() {
    return request;
  }

  @Override
  public HttpResponse response() {
    return response;
  }

  @Override
  public InetAddress clientAddress() {
    return clientAddress;
  }

  @Override
  public String tlsProtocol() {
    return tlsProtocol;
  }

  @Override
  public Throwable error() {
    return error;
  }

  @Override
  public String upstreamName() {
    return upstreamName;
  }

  @Override
  public String upstreamTarget() {
    return upstreamTarget;
  }

  @Override
  public RequestOutcome outcome() {
    return outcome;
  }

  @Override
  public int upstreamStatus() {
    return upstreamStatus;
  }

  @Override
  public String route() {
    return route;
  }

  @Override
  public String configId() {
    return configId;
  }

  @Override
  public String protectionOutcome() {
    return protectionOutcome;
  }

  @Override
  public int clientPort() {
    return clientAddress != null ? 4711 : 0;
  }

  @Override
  public int serverPort() {
    return 443;
  }

  @Override
  public String tlsCipher() {
    return tlsProtocol != null ? "TLS_AES_128_GCM_SHA256" : null;
  }

  @Override
  public String connectionId() {
    return "0";
  }

  @Override
  public int connectionRequestCount() {
    return 0;
  }

  @Override
  public String requestId() {
    return "0";
  }

  @Override
  public String traceId() {
    return TRACE_ID;
  }

  @Override
  public String spanId() {
    return SPAN_ID;
  }

  @Override
  public String traceFlags() {
    return "01";
  }

  @Override
  public String clientTraceparent() {
    return request.headers().get(TraceContext.TRACEPARENT);
  }

  @Override
  public long contentBytes() {
    return 3;
  }

  @Override
  public long bytesSent() {
    return 100;
  }

  @Override
  public long bytesReceived() {
    return 50;
  }

  @Override
  public long requestBodyBytes() {
    return 7;
  }

  @Override
  public long requestStartTimeMillis() {
    return 0;
  }

  @Override
  public long receiveDurationNanos() {
    return 1_000;
  }

  @Override
  public long responseTimeNanos() {
    return 2_000;
  }

  @Override
  public long totalDurationNanos() {
    return 3_000;
  }

  /// Captures the current request header names without the edge headers into an immutable list,
  /// like [FingerprintHandler] does before logging, so [#clientHeaderNames()] does not allocate
  TestRequestLogInfo captureClientHeaderNames() {
    clientHeaderNames = List.copyOf(extractClientHeaderNames());
    return this;
  }

  /// With pre-captured client header names, as [FingerprintHandler] takes them
  TestRequestLogInfo withClientHeaderNames(List<CharSequence> names) {
    clientHeaderNames = names;
    return this;
  }

  /// The names of the request headers without the edge headers, like the [FingerprintHandler]
  /// takes them before the edge adds its own. Returns the pre-captured list when set via
  /// [#captureClientHeaderNames()] or [#withClientHeaderNames(List)].
  @Override
  public List<CharSequence> clientHeaderNames() {
    if (clientHeaderNames != null) {
      return clientHeaderNames;
    }
    return extractClientHeaderNames();
  }

  /// Scans the current request headers without the edge headers when no list was pre-captured
  private List<CharSequence> extractClientHeaderNames() {
    List<CharSequence> names = new ArrayList<>();
    request
        .headers()
        .iteratorCharSequence()
        .forEachRemaining(
            entry -> {
              if (!SensepitchHeaders.isEdgeHeader(entry.getKey())) {
                names.add(entry.getKey());
              }
            });
    return names;
  }

  /// With sampled headers, as the [FingerprintHandler] takes them
  TestRequestLogInfo withSampleHeaders(List<String> sample) {
    sampleHeaders = sample;
    return this;
  }

  @Override
  public List<String> sampleHeaders() {
    return sampleHeaders;
  }
}
