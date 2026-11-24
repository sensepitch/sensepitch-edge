package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderResult;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.TooLongHttpHeaderException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/// Requests the HTTP codec cannot decode, sent as bytes through the codec and the handlers of
/// [Proxy#addHttpHandlers], see [DecoderFailureHandler]. The codec has Netty's default limits,
/// 4096 bytes for the request line and 8192 for the header.
///
/// @author Jens Wilke
class DecoderFailureHandlerTest {

  /// Above the default header limit of the codec, 8192 bytes
  static final int TOO_LARGE_HEADER_BYTES = 9000;

  /// Just below the default header limit of the codec, with the host header
  static final int WITHIN_LIMIT_HEADER_BYTES = 8000;

  /// Above the default request line limit of the codec, 4096 bytes
  static final int TOO_LONG_URI_BYTES = 5000;

  final RequestLogCapture requestLog = new RequestLogCapture();
  final Proxy proxy = new Proxy(CompleteTest.COMMON_CONFIG, requestLog);
  final EmbeddedChannel channel =
      new EmbeddedChannel(
          new ChannelInitializer<EmbeddedChannel>() {
            @Override
            protected void initChannel(EmbeddedChannel ch) {
              ch.pipeline().addLast(new HttpServerCodec());
              proxy.addHttpHandlers(ch.pipeline());
            }
          });

  @AfterEach
  void tearDown() {
    channel.finishAndReleaseAll();
  }

  @Test
  void rejectsFailedRequestBeforeAccessIsLost() {
    EmbeddedChannel handlerChannel = new EmbeddedChannel(new DecoderFailureHandler());
    DefaultFullHttpRequest request =
        new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/") {
          @Override
          public DecoderResult decoderResult() {
            assertThat(refCnt()).as("request references while reading decoder result").isPositive();
            return super.decoderResult();
          }
        };
    request.setDecoderResult(DecoderResult.failure(new TooLongHttpHeaderException()));
    try {
      assertThat(handlerChannel.writeInbound(request)).isFalse();
      assertThat(request.refCnt()).isZero();
      FullHttpResponse response = handlerChannel.readOutbound();
      try {
        assertThat(response.status()).isEqualTo(HttpResponseStatus.REQUEST_HEADER_FIELDS_TOO_LARGE);
      } finally {
        response.release();
      }
    } finally {
      handlerChannel.finishAndReleaseAll();
    }
  }

  @Test
  void tooLargeHeaderIs431() {
    String response = send(request("/", "X-Large: " + "a".repeat(TOO_LARGE_HEADER_BYTES) + "\r\n"));
    assertThat(response).startsWith("HTTP/1.1 431 Request Header Fields Too Large\r\n");
    assertRejected(response, 431, RequestOutcome.REQUEST_TOO_LARGE);
  }

  @Test
  void tooLongRequestLineIs414() {
    String response = send(request("/" + "a".repeat(TOO_LONG_URI_BYTES), ""));
    assertThat(response).startsWith("HTTP/1.1 414 Request-URI Too Long\r\n");
    assertRejected(response, 414, RequestOutcome.REQUEST_TOO_LARGE);
  }

  /// Another decoder failure, here an unknown protocol version, keeps the outcome of an edge
  /// rejection
  @Test
  void malformedRequestIs400() {
    String response = send("GET / HTTX/1.1\r\nHost: example.com\r\n\r\n");
    assertThat(response).startsWith("HTTP/1.1 400 Bad Request\r\n");
    assertRejected(response, 400, RequestOutcome.EDGE_RESPONSE);
  }

  /// A header just below the limit passes, is answered by the site and keeps the connection open
  @Test
  void requestWithinTheLimitsIsAnswered() {
    String response =
        send(request("/", "X-Large: " + "a".repeat(WITHIN_LIMIT_HEADER_BYTES) + "\r\n"));
    assertThat(response).startsWith("HTTP/1.1 200 OK\r\n").endsWith("a test response");
    assertThat(channel.isOpen()).isTrue();
    assertLogged(200, RequestOutcome.COMPLETED);
    assertThat(outcomeCounts("example.com")).isEqualTo(Map.of("completed", 1L));
  }

  /// The decoder discards the head, so the request has no host: counted as a missing host
  private void assertRejected(String response, int status, RequestOutcome outcome) {
    assertThat(response.toLowerCase(Locale.ROOT)).contains("\r\nconnection: close\r\n");
    assertThat(channel.isOpen()).isFalse();
    assertLogged(status, outcome);
    assertThat(outcomeCounts(SanitizeHostHandler.MISSING_HOST))
        .isEqualTo(Map.of(outcome.value(), 1L));
  }

  /// One request log record with the status and outcome
  private void assertLogged(int status, RequestOutcome outcome) {
    assertThat(requestLog.lines())
        .singleElement()
        .asString()
        .contains("\"" + OtelLogFields.HTTP_RESPONSE_STATUS_CODE + "\":" + status)
        .contains("\"" + SensepitchLogFields.REQUEST_OUTCOME + "\":\"" + outcome.value() + "\"");
  }

  static String request(String uri, String extraHeaders) {
    return "GET " + uri + " HTTP/1.1\r\nHost: example.com\r\n" + extraHeaders + "\r\n";
  }

  /// Sends the bytes and returns everything written back
  private String send(String request) {
    channel.writeInbound(Unpooled.copiedBuffer(request, StandardCharsets.US_ASCII));
    StringBuilder sb = new StringBuilder();
    ByteBuf buf;
    while ((buf = channel.readOutbound()) != null) {
      sb.append(buf.toString(StandardCharsets.US_ASCII));
      buf.release();
    }
    return sb.toString();
  }

  /// Count per outcome label value of one ingress
  private Map<String, Long> outcomeCounts(String ingress) {
    Map<String, Long> counts = new TreeMap<>();
    proxy.metrics().requestOutcomeCounter.collect().getDataPoints().stream()
        .filter(p -> ingress.equals(p.getLabels().get("ingress")))
        .forEach(p -> counts.put(p.getLabels().get("outcome"), (long) p.getValue()));
    return counts;
  }
}
