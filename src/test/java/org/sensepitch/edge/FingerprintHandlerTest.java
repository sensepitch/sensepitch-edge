package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/// The headers as the client sent them: the names and the sample in the request log, and the
/// sp1 fingerprint for the upstream, see [FingerprintHandler] and [FingerprintConfig].
class FingerprintHandlerTest {

  static final String TRACEPARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

  /// A client's request with a forged edge header, a `traceparent` the edge replaces, and a
  /// cookie that a sample leaves out
  static final String REQUEST =
      "GET / HTTP/1.1\r\n"
          + "Host: example.com\r\n"
          + "User-Agent: curl/8\r\n"
          + Deflector.TRAFFIC_TYPE_HEADER
          + ": human\r\n"
          + "traceparent: "
          + TRACEPARENT
          + "\r\n"
          + "Cookie: sid=secret\r\n"
          + "Accept: */*\r\n"
          + "\r\n";

  static final FingerprintConfig SP1 =
      FingerprintConfig.builder().sp1(Sp1FingerprintConfig.builder().enable(true).build()).build();

  final List<EmbeddedChannel> channels = new ArrayList<>();

  @AfterEach
  void tearDown() {
    channels.forEach(EmbeddedChannel::finishAndReleaseAll);
  }

  @Test
  void configDefaultsToOff() {
    FingerprintConfig config = FingerprintConfig.DEFAULT;
    assertThat(config.ja4()).isEqualTo(Ja4Config.DEFAULT);
    assertThat(config.sp1()).isEqualTo(Sp1FingerprintConfig.DEFAULT);
    assertThat(config.sp1().enable()).isFalse();
    assertThat(config.sp1().raw()).isFalse();
    assertThat(config.sampleHeadersInterval()).isZero();
    assertThat(ProxyConfig.builder().build().fingerprint()).isEqualTo(config);
  }

  @Test
  void negativeSampleHeadersIntervalIsRejected() {
    assertThatThrownBy(() -> FingerprintConfig.builder().sampleHeadersInterval(-1).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("fingerprint.sampleHeadersInterval must not be negative");
  }

  /// The edge moves `Host` to the end when it sanitizes it and replaces `traceparent`, the log
  /// shows what the client sent, without the forged edge header
  @Test
  void loggedHeaderNamesAreTheClients() throws Exception {
    JsonNode record = logRecordOf(CompleteTest.COMMON_CONFIG);
    assertThat(record.get(SensepitchLogFields.REQUEST_HEADER_NAMES).toString())
        .isEqualTo("[\"Host\",\"User-Agent\",\"traceparent\",\"Cookie\",\"Accept\"]");
    assertThat(record.has(SensepitchLogFields.REQUEST_FINGERPRINT_SP1)).isFalse();
    assertThat(record.has(SensepitchLogFields.REQUEST_SAMPLE_HEADERS)).isFalse();
  }

  @Test
  void loggedFingerprintWhenEnabled() throws Exception {
    JsonNode record = logRecordOf(CompleteTest.COMMON_CONFIG.toBuilder().fingerprint(SP1).build());
    assertThat(record.get("sensepitch.request.fingerprint.sp1").asText())
        .isEqualTo(Ja4Fingerprint.truncatedHash("Host,User-Agent,traceparent,Accept|accept=*/*"));
  }

  /// `1` samples every request: the headers in client order and spelling, without the cookie and
  /// the trace context
  @Test
  void loggedSampleAsSent() throws Exception {
    FingerprintConfig sampling = FingerprintConfig.builder().sampleHeadersInterval(1).build();
    JsonNode record =
        logRecordOf(CompleteTest.COMMON_CONFIG.toBuilder().fingerprint(sampling).build());
    assertThat(record.get(SensepitchLogFields.REQUEST_SAMPLE_HEADERS).toString())
        .isEqualTo("[\"Host: example.com\",\"User-Agent: curl/8\",\"Accept: */*\"]");
  }

  /// With a positive interval most requests are not sampled; this one is with a chance of 1 in
  /// 2^31, too small to make the test flaky, so no seam for the random numbers is needed
  @Test
  void notEveryRequestIsSampled() {
    FingerprintConfig rare =
        FingerprintConfig.builder().sampleHeadersInterval(Integer.MAX_VALUE).build();
    EmbeddedChannel channel = track(new EmbeddedChannel(new FingerprintHandler(() -> rare)));
    channel.writeInbound(request());
    channel.<HttpRequest>readInbound();
    assertThat(FingerprintHandler.takeClientHeaders(channel).sample()).isNull();
  }

  @Test
  void rawFingerprintForTheUpstream() {
    FingerprintConfig raw =
        FingerprintConfig.builder()
            .sp1(Sp1FingerprintConfig.builder().enable(true).raw(true).build())
            .build();
    HttpRequest request = passThrough(new FingerprintHandler(() -> raw));
    assertThat(request.headers().get(FingerprintHandler.SP1_RAW_HEADER))
        .isEqualTo("Host,Accept|accept=*/*");
    assertThat(request.headers().get(FingerprintHandler.SP1_HEADER))
        .isEqualTo(Ja4Fingerprint.truncatedHash("Host,Accept|accept=*/*"));
  }

  /// The configuration is read per request, so a reload switches the fingerprint on
  @Test
  void reloadAppliesToTheNextRequest() {
    AtomicReference<FingerprintConfig> active = new AtomicReference<>(FingerprintConfig.DEFAULT);
    FingerprintHandler handler = new FingerprintHandler(active::get);
    assertThat(passThrough(handler).headers().contains(FingerprintHandler.SP1_HEADER)).isFalse();
    active.set(SP1);
    assertThat(passThrough(handler).headers().contains(FingerprintHandler.SP1_HEADER)).isTrue();
  }

  @Test
  void onlyTheNamesWhenDisabled() {
    EmbeddedChannel channel =
        track(new EmbeddedChannel(new FingerprintHandler(() -> FingerprintConfig.DEFAULT)));
    channel.writeInbound(request());
    HttpRequest request = channel.readInbound();
    assertThat(request.headers().names())
        .noneMatch(name -> name.startsWith(FingerprintHandler.SP1_HEADER));
    FingerprintHandler.ClientHeaders taken = FingerprintHandler.takeClientHeaders(channel);
    assertThat(taken.names()).containsExactly("Host", "Accept");
    assertThat(taken.sample()).isNull();
    assertThat(FingerprintHandler.takeClientHeaders(channel))
        .as("taken once")
        .isSameAs(FingerprintHandler.ClientHeaders.NONE);
  }

  private HttpRequest passThrough(FingerprintHandler handler) {
    EmbeddedChannel channel = track(new EmbeddedChannel(handler));
    channel.writeInbound(request());
    return channel.readInbound();
  }

  private static HttpRequest request() {
    HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
    request.headers().add("Host", "example.com").add("Accept", "*/*");
    return request;
  }

  /// Sends [#REQUEST] through the pipeline of a proxy and returns its request log record
  private JsonNode logRecordOf(ProxyConfig config) throws Exception {
    RequestLogCapture requestLog = new RequestLogCapture();
    Proxy proxy = new Proxy(config, requestLog);
    EmbeddedChannel channel =
        track(
            new EmbeddedChannel(
                new ChannelInitializer<EmbeddedChannel>() {
                  @Override
                  protected void initChannel(EmbeddedChannel ch) {
                    ch.pipeline().addLast(new HttpServerCodec());
                    proxy.addHttpHandlers(ch.pipeline());
                  }
                }));
    channel.writeInbound(Unpooled.copiedBuffer(REQUEST, StandardCharsets.US_ASCII));
    ByteBuf response;
    while ((response = channel.readOutbound()) != null) {
      response.release();
    }
    assertThat(requestLog.lines()).hasSize(1);
    return new ObjectMapper().readTree(requestLog.lines().getFirst());
  }

  private EmbeddedChannel track(EmbeddedChannel channel) {
    channels.add(channel);
    return channel;
  }
}
