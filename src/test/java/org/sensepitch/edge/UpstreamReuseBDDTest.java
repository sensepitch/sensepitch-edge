package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Future;
import java.io.IOException;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.serenitybdd.annotations.Step;
import net.serenitybdd.junit5.SerenityJUnit5Extension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/// When [DownstreamHandler] releases an upstream connection as reusable, and when it sends a
/// request once more after a connection taken from the pool failed. The pool itself is tested by
/// [UpstreamConnectionPoolTest], the failures of a new connection by [UpstreamFailureBDDTest].
@ExtendWith(SerenityJUnit5Extension.class)
class UpstreamReuseBDDTest {

  // releasing the connection

  @Test
  void cleanExchangeReleasesTheConnectionForReuse() {
    try (Steps steps = new Steps()) {
      steps
          .when_the_client_sends_a_get()
          .when_the_upstream_responds()
          .then_the_client_received(200)
          .then_the_connection_is_released(true)
          .then_the_outcome_is(RequestOutcome.COMPLETED);
    }
  }

  @Test
  void responseWithConnectionCloseIsNotReused() {
    try (Steps steps = new Steps()) {
      steps
          .when_the_client_sends_a_get()
          .when_the_upstream_responds_and_closes()
          .then_the_client_received(200)
          .then_the_connection_is_released(false);
    }
  }

  /// The upstream answered before the client sent the whole body: the rest of the body was not
  /// forwarded, the connection is out of step with the upstream
  @Test
  void responseBeforeTheBodyWasForwardedIsNotReused() {
    try (Steps steps = new Steps()) {
      steps
          .when_the_client_starts_a_post_with_a_body()
          .when_the_upstream_responds()
          .then_the_client_received(200)
          .then_the_connection_is_released(false);
    }
  }

  @Test
  void clientAbortIsNotReused() {
    try (Steps steps = new Steps()) {
      steps
          .when_the_client_sends_a_get()
          .when_the_client_closes()
          .then_the_connection_is_released(false);
    }
  }

  @Test
  void hopByHopHeadersOfTheClientAreNotForwarded() {
    try (Steps steps = new Steps()) {
      steps
          .when_the_client_sends_a_get_with_connection_options()
          .then_the_upstream_received_no_hop_by_hop_headers();
    }
  }

  // retry after a pooled connection failed

  @Test
  void closedPooledConnectionIsRetriedOnANewOne() {
    try (Steps steps = new Steps()) {
      steps
          .given_a_pooled_connection()
          .when_the_client_sends_a_get()
          .when_the_upstream_closes()
          .then_the_request_was_sent_again_on_a_new_connection()
          .when_the_upstream_responds()
          .then_the_client_received(200)
          .then_the_outcome_is(RequestOutcome.COMPLETED);
    }
  }

  @Test
  void failedWriteOnAPooledConnectionIsRetried() {
    try (Steps steps = new Steps()) {
      steps
          .given_a_pooled_connection()
          .when_the_client_sends_a_get()
          .when_the_upstream_write_fails()
          .then_the_request_was_sent_again_on_a_new_connection()
          .when_the_upstream_responds()
          .then_the_client_received(200);
    }
  }

  @Test
  void resetPooledConnectionIsRetried() {
    try (Steps steps = new Steps()) {
      steps
          .given_a_pooled_connection()
          .when_the_client_sends_a_get()
          .when_the_upstream_resets_the_connection()
          .then_the_request_was_sent_again_on_a_new_connection()
          .when_the_upstream_responds()
          .then_the_client_received(200);
    }
  }

  /// RFC 9110, section 9.2.2: a non-idempotent request is not sent again
  @Test
  void postIsNotRetried() {
    try (Steps steps = new Steps()) {
      steps
          .given_a_pooled_connection()
          .when_the_client_sends_a_post_with_a_body()
          .when_the_upstream_closes()
          .then_the_client_received(502)
          .then_nothing_was_retried()
          .then_the_outcome_is(RequestOutcome.UPSTREAM_CLOSED);
    }
  }

  @Test
  void startedResponseIsNotRetried() {
    try (Steps steps = new Steps()) {
      steps
          .given_a_pooled_connection()
          .when_the_client_sends_a_get()
          .when_the_upstream_sends_the_response_head()
          .when_the_upstream_closes()
          .then_nothing_was_retried()
          .then_the_outcome_is(RequestOutcome.UPSTREAM_CLOSED);
    }
  }

  @Test
  void newConnectionIsNotRetried() {
    try (Steps steps = new Steps()) {
      steps
          .when_the_client_sends_a_get()
          .when_the_upstream_closes()
          .then_the_client_received(502)
          .then_nothing_was_retried();
    }
  }

  /// The connection of the retry is new, a failure of it ends the exchange
  @Test
  void retryIsNotRetriedAgain() {
    try (Steps steps = new Steps()) {
      steps
          .given_a_pooled_connection()
          .when_the_client_sends_a_get()
          .when_the_upstream_closes()
          .then_the_request_was_sent_again_on_a_new_connection()
          .when_the_upstream_closes()
          .then_the_client_received(502)
          .then_the_outcome_is(RequestOutcome.UPSTREAM_CLOSED);
    }
  }

  /// A request with a body is not sent again, its body would have to be kept
  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"content-length", "chunked", "full"})
  void requestWithABodyIsNotRetried(String kind) {
    try (Steps steps = new Steps()) {
      steps
          .given_a_pooled_connection()
          .when_the_client_sends_a_get_with_a_body(kind)
          .when_the_upstream_closes()
          .then_the_client_received(502)
          .then_nothing_was_retried();
    }
  }

  @Test
  void failedConnectOfTheRetryEndsTheExchange() {
    try (Steps steps = new Steps()) {
      steps
          .given_a_pooled_connection()
          .given_new_connections_fail()
          .when_the_client_sends_a_get()
          .when_the_upstream_closes()
          .then_the_client_received(502)
          .then_the_outcome_is(RequestOutcome.UPSTREAM_CONNECT_FAILED);
    }
  }

  /// A `101` switches the protocol, the connection is not reused even if a final response follows
  @Test
  void switchingProtocolsIsNotReused() {
    try (Steps steps = new Steps()) {
      steps
          .when_the_client_sends_a_get()
          .when_the_upstream_switches_protocols()
          .when_the_upstream_responds()
          .then_the_connection_is_released(false);
    }
  }

  /// The write of the request of an earlier exchange fails after the next exchange started: it
  /// belongs to no active exchange, nothing is retried and the next exchange goes on
  @Test
  void lateWriteFailureOfAnEarlierExchangeIsIgnored() {
    try (Steps steps = new Steps()) {
      steps
          .given_a_pooled_connection()
          .when_the_client_sends_a_get()
          .when_the_upstream_responds()
          .then_the_client_received(200)
          .when_the_client_sends_a_get()
          .when_the_write_of_the_first_connection_fails()
          .then_nothing_was_retried()
          .when_the_upstream_responds()
          .then_the_client_received(200)
          .then_the_outcome_is(RequestOutcome.COMPLETED, RequestOutcome.COMPLETED);
    }
  }

  /// The event loop has its share of `maxConnections` open: no further connection, a `503`
  @Test
  void connectionLimitIsAnswered503() {
    try (Steps steps = new Steps()) {
      steps
          .given_the_connection_limit_is_reached()
          .when_the_client_sends_a_get()
          .then_the_client_received(503)
          .then_the_outcome_is(RequestOutcome.UPSTREAM_CONNECTION_LIMIT);
    }
  }

  /// A retry needs a new connection too, at the limit it ends the exchange with a `503`
  @Test
  void retryAtTheConnectionLimitIsAnswered503() {
    try (Steps steps = new Steps()) {
      steps
          .given_a_pooled_connection()
          .given_the_connection_limit_is_reached()
          .when_the_client_sends_a_get()
          .when_the_upstream_closes()
          .then_the_client_received(503)
          .then_the_outcome_is(RequestOutcome.UPSTREAM_CONNECTION_LIMIT);
    }
  }

  class Steps implements AutoCloseable {
    private final ProxyMetrics metrics = new ProxyMetrics();
    private final RequestLogCapture requestLog = new RequestLogCapture();
    private final PoolingUpstream upstream = new PoolingUpstream();
    private final EmbeddedChannel ingress =
        new EmbeddedChannel(
            TestTls.HANDLER,
            new RequestLoggingHandler(metrics, requestLog.plainText()),
            new IngressTimeoutHandler(ConnectionConfig.DEFAULT, metrics),
            new DownstreamHandler(upstream, metrics),
            new ExceptionHandler(metrics));

    Steps() {
      ingress.runPendingTasks();
    }

    @Step
    Steps given_a_pooled_connection() {
      upstream.idle.add(upstreamChannel(null));
      return this;
    }

    @Step
    Steps given_the_connection_limit_is_reached() {
      upstream.limitReached = true;
      return this;
    }

    @Step
    Steps given_new_connections_fail() {
      upstream.failNewConnects = true;
      return this;
    }

    @Step
    Steps when_the_client_sends_a_get_with_a_body(String kind) {
      if (kind.equals("full")) {
        return fromClient(
            new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/data", body()));
      }
      HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/data");
      if (kind.equals("chunked")) {
        request.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
      } else {
        request.headers().set(HttpHeaderNames.CONTENT_LENGTH, 3);
      }
      return fromClient(request, new DefaultLastHttpContent(body()));
    }

    @Step
    Steps when_the_client_sends_a_get() {
      return fromClient(
          new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/data"),
          LastHttpContent.EMPTY_LAST_CONTENT);
    }

    @Step
    Steps when_the_client_sends_a_get_with_connection_options() {
      HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/data");
      request.headers().set(HttpHeaderNames.CONNECTION, "close, X-Hop");
      request.headers().set("X-Hop", "1");
      request.headers().set("Keep-Alive", "timeout=5");
      request.headers().set("Proxy-Connection", "keep-alive");
      request.headers().set(HttpHeaderNames.ACCEPT, "text/html");
      return fromClient(request, LastHttpContent.EMPTY_LAST_CONTENT);
    }

    @Step
    Steps when_the_client_sends_a_post_with_a_body() {
      return fromClient(post(), new DefaultHttpContent(body()), LastHttpContent.EMPTY_LAST_CONTENT);
    }

    /// The head and a part of the body, the last content is still on its way
    @Step
    Steps when_the_client_starts_a_post_with_a_body() {
      return fromClient(post(), new DefaultHttpContent(body()));
    }

    private HttpRequest post() {
      HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/data");
      request.headers().set(HttpHeaderNames.CONTENT_LENGTH, 6);
      return request;
    }

    private io.netty.buffer.ByteBuf body() {
      return Unpooled.copiedBuffer("abc", StandardCharsets.US_ASCII);
    }

    private Steps fromClient(Object... messages) {
      ingress.writeInbound(messages);
      ingress.runPendingTasks();
      return this;
    }

    @Step
    Steps when_the_client_closes() {
      ingress.close();
      return this;
    }

    @Step
    Steps when_the_upstream_responds() {
      return fromUpstream(response());
    }

    @Step
    Steps when_the_upstream_responds_and_closes() {
      FullHttpResponse response = response();
      response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
      return fromUpstream(response);
    }

    @Step
    Steps when_the_upstream_switches_protocols() {
      fromUpstream(
          new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.SWITCHING_PROTOCOLS));
      return fromUpstream(LastHttpContent.EMPTY_LAST_CONTENT);
    }

    @Step
    Steps when_the_write_of_the_first_connection_fails() {
      upstream
          .first
          .pipeline()
          .get(UpstreamFailureBDDTest.HoldLastContentWrite.class)
          .promise
          .setFailure(new IOException("Broken pipe"));
      ingress.runPendingTasks();
      return this;
    }

    @Step
    Steps when_the_upstream_sends_the_response_head() {
      HttpResponse head = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
      head.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
      return fromUpstream(head);
    }

    private FullHttpResponse response() {
      FullHttpResponse response =
          new DefaultFullHttpResponse(
              HttpVersion.HTTP_1_1,
              HttpResponseStatus.OK,
              Unpooled.copiedBuffer("ok", StandardCharsets.US_ASCII));
      response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 2);
      return response;
    }

    private Steps fromUpstream(Object msg) {
      upstream.current.writeInbound(msg);
      ingress.flush();
      ingress.runPendingTasks();
      return this;
    }

    @Step
    Steps when_the_upstream_closes() {
      upstream.current.unsafe().close(upstream.current.voidPromise());
      ingress.runPendingTasks();
      return this;
    }

    @Step
    Steps when_the_upstream_write_fails() {
      upstream
          .current
          .pipeline()
          .get(UpstreamFailureBDDTest.HoldLastContentWrite.class)
          .promise
          .setFailure(new IOException("Broken pipe"));
      ingress.runPendingTasks();
      return this;
    }

    @Step
    Steps when_the_upstream_resets_the_connection() {
      upstream.current.pipeline().fireExceptionCaught(new IOException("Connection reset"));
      ingress.runPendingTasks();
      return this;
    }

    @Step
    Steps then_the_client_received(int status) {
      HttpResponse response = null;
      Object msg;
      while ((msg = ingress.readOutbound()) != null) {
        if (msg instanceof HttpResponse head && response == null) {
          response = head;
        }
        ReferenceCountUtil.release(msg);
      }
      assertThat(response).as("response to the client").isNotNull();
      assertThat(response.status().code()).isEqualTo(status);
      return this;
    }

    @Step
    Steps then_the_connection_is_released(boolean reusable) {
      assertThat(upstream.released).singleElement().isEqualTo(reusable);
      return this;
    }

    @Step
    Steps then_the_upstream_received_no_hop_by_hop_headers() {
      HttpRequest sent = upstream.current.readOutbound();
      assertThat(sent.headers().contains(HttpHeaderNames.CONNECTION)).isFalse();
      assertThat(sent.headers().contains("X-Hop")).isFalse();
      assertThat(sent.headers().contains(LegacyHeaderNames.KEEP_ALIVE)).isFalse();
      assertThat(sent.headers().contains(LegacyHeaderNames.PROXY_CONNECTION)).isFalse();
      assertThat(sent.headers().get(HttpHeaderNames.ACCEPT)).isEqualTo("text/html");
      return this;
    }

    /// The pooled connection was closed, the request arrived on a new connection
    @Step
    Steps then_the_request_was_sent_again_on_a_new_connection() {
      assertThat(upstream.opened).hasSize(1);
      HttpRequest sent = upstream.current.readOutbound();
      assertThat(sent.method()).isEqualTo(HttpMethod.GET);
      assertThat(sent.uri()).isEqualTo("/data");
      assertThat(retries()).isOne();
      return this;
    }

    @Step
    Steps then_nothing_was_retried() {
      assertThat(retries()).isZero();
      return this;
    }

    private double retries() {
      return metrics.upstreamRetries.labelValues(UpstreamFailureBDDTest.UPSTREAM).get();
    }

    @Step
    Steps then_the_outcome_is(RequestOutcome... outcomes) {
      assertThat(requestLog.outcomes()).containsExactly(outcomes);
      return this;
    }

    private EmbeddedChannel upstreamChannel(Channel ingressChannel) {
      return new EmbeddedChannel(
          new UpstreamFailureBDDTest.HoldLastContentWrite(),
          new ForwardHandler(ingressChannel, metrics, UpstreamFailureBDDTest.UPSTREAM));
    }

    @Override
    public void close() {
      ingress.finishAndReleaseAll();
      upstream.idle.forEach(EmbeddedChannel::finishAndReleaseAll);
      upstream.opened.forEach(EmbeddedChannel::finishAndReleaseAll);
      upstream.reusedChannels.forEach(channel -> ((EmbeddedChannel) channel).finishAndReleaseAll());
    }

    /// Hands out the pooled connections first, marked as reused, then new ones; records each
    /// release
    class PoolingUpstream implements Upstream {
      final Deque<EmbeddedChannel> idle = new ArrayDeque<>();
      final List<EmbeddedChannel> opened = new ArrayList<>();
      final Set<Channel> reusedChannels = new HashSet<>();
      final List<Boolean> released = new ArrayList<>();
      boolean failNewConnects;
      boolean limitReached;

      /// The connection of the first exchange
      EmbeddedChannel first;

      EmbeddedChannel current;

      @Override
      public Future<Channel> connect(ChannelHandlerContext ctx) {
        EmbeddedChannel pooled = idle.poll();
        if (pooled == null) {
          return connectNew(ctx);
        }
        pooled.pipeline().get(ForwardHandler.class).attach(ctx.channel());
        reusedChannels.add(pooled);
        current = pooled;
        if (first == null) {
          first = pooled;
        }
        return ctx.executor().newSucceededFuture(pooled);
      }

      @Override
      public Future<Channel> connectNew(ChannelHandlerContext ctx) {
        if (limitReached) {
          return ctx.executor().newFailedFuture(new ConnectionLimitException("upstream:80"));
        }
        if (failNewConnects) {
          return ctx.executor().newFailedFuture(new ConnectException("Connection refused"));
        }
        current = upstreamChannel(ctx.channel());
        if (first == null) {
          first = current;
        }
        opened.add(current);
        return ctx.executor().newSucceededFuture(current);
      }

      @Override
      public boolean reused(Channel channel) {
        return reusedChannels.contains(channel);
      }

      @Override
      public void release(Channel channel, boolean reusable) {
        released.add(reusable);
        if (!reusable) {
          channel.close();
        }
      }

      @Override
      public String name() {
        return UpstreamFailureBDDTest.UPSTREAM;
      }

      @Override
      public String target() {
        return "upstream:80";
      }
    }
  }
}
