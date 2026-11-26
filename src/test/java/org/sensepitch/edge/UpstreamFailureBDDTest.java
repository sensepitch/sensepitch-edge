package org.sensepitch.edge;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;
import java.io.IOException;
import java.net.ConnectException;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayList;
import java.util.List;
import net.serenitybdd.annotations.Step;
import net.serenitybdd.junit5.SerenityJUnit5Extension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

/// An upstream failure ends the exchange in [DownstreamHandler#completeWithError]: before the
/// response has started the client gets a 502, afterwards the connections are dropped without a
/// second response. The upstream reports a failed request write asynchronously, so it can arrive
/// at any point of the response, or after it. More upstream error cases are in
/// [FlowAndExceptionTest].
@ExtendWith(SerenityJUnit5Extension.class)
class UpstreamFailureBDDTest {

  @RegisterExtension LogCapture logs = new LogCapture();

  @Test
  void writeFailureBeforeTheResponseIsAnswered() {
    try (Steps steps = new Steps()) {
      steps
          .given_a_request_forwarded_upstream()
          .when_the_upstream_write_fails()
          .then_the_client_received_only_a_bad_gateway("Upstream write problem")
          .then_the_connections_are_closed()
          .then_the_outcome_is(RequestOutcome.UPSTREAM_WRITE_FAILED)
          .then_the_failure_is_in_the_request_log(RequestOutcome.UPSTREAM_WRITE_FAILED)
          .then_no_upstream_close_is_counted();
    }
  }

  /// The case seen in production: the browser leaves during the response, the edge drops the
  /// upstream. That close is the edge's, not the upstream's, so it is no upstream error.
  @Test
  void clientAbortDuringTheResponseIsNoUpstreamError() {
    try (Steps steps = new Steps()) {
      steps
          .given_a_request_forwarded_upstream()
          .when_the_upstream_sends_the_response_headers()
          .when_a_write_to_the_client_fails_since_it_left()
          .then_the_upstream_is_closed()
          .then_the_outcome_is(RequestOutcome.CLIENT_ABORTED)
          .then_no_upstream_close_is_counted();
    }
  }

  /// The upstream goes away before it answered: the client gets a 502 at once, no timer has to
  /// fire
  @Test
  void upstreamCloseBeforeTheResponseIsAnswered() {
    try (Steps steps = new Steps()) {
      steps
          .given_a_request_forwarded_upstream()
          .when_the_upstream_closes_the_connection()
          .then_the_client_received_only_a_bad_gateway("Upstream closed")
          .then_the_client_connection_is_closed()
          .then_the_outcome_is(RequestOutcome.UPSTREAM_CLOSED)
          .then_the_upstream_close_is_counted();
    }
  }

  /// After the headers no error response is possible, the client connection is closed, so the
  /// client sees the truncated response
  @Test
  void upstreamCloseAfterTheHeadersDropsTheClient() {
    try (Steps steps = new Steps()) {
      steps
          .given_a_request_forwarded_upstream()
          .when_the_upstream_sends_the_response_headers()
          .when_the_upstream_closes_the_connection()
          .then_the_client_received_only_the_upstream_response(0)
          .then_the_client_connection_is_closed()
          .then_the_outcome_is(RequestOutcome.UPSTREAM_CLOSED)
          .then_the_upstream_close_is_counted();
    }
  }

  /// A close report counts only for the upstream connection of the active exchange: a report of
  /// another channel, e.g. one the edge dropped, changes nothing, the exchange goes on
  @Test
  void closeOfAnotherUpstreamIsIgnored() {
    try (Steps steps = new Steps()) {
      steps
          .given_a_request_forwarded_upstream()
          .when_a_close_of_another_upstream_is_reported()
          .when_the_upstream_sends_the_response_headers()
          .when_the_upstream_sends_a_body_part()
          .when_the_upstream_completes_the_response()
          .then_the_client_received_the_complete_response()
          .then_the_outcome_is(RequestOutcome.COMPLETED)
          .then_no_upstream_close_is_counted();
    }
  }

  @Test
  void closeReportWhileConnectingIsIgnored() {
    try (Steps steps = new Steps()) {
      steps
          .given_a_request_while_the_upstream_is_connecting()
          .when_a_close_of_another_upstream_is_reported()
          .then_the_client_connection_is_open()
          .then_no_upstream_close_is_counted();
    }
  }

  @Test
  void connectFailureIsAnswered() {
    try (Steps steps = new Steps()) {
      steps
          .given_a_request_while_the_upstream_is_connecting()
          .when_the_upstream_connect_fails()
          .then_the_client_received_only_a_bad_gateway("Upstream connection problem")
          .then_the_client_connection_is_closed()
          .then_the_outcome_is(RequestOutcome.UPSTREAM_CONNECT_FAILED)
          .then_the_failure_is_in_the_request_log(RequestOutcome.UPSTREAM_CONNECT_FAILED);
    }
  }

  @Test
  void writeFailureAfterTheHeadersDropsTheConnections() {
    try (Steps steps = new Steps()) {
      steps
          .given_a_request_forwarded_upstream()
          .when_the_upstream_sends_the_response_headers()
          .when_the_upstream_write_fails()
          .then_the_client_received_only_the_upstream_response(0)
          .then_the_connections_are_closed()
          .then_the_outcome_is(RequestOutcome.UPSTREAM_WRITE_FAILED)
          .then_the_failure_is_in_the_request_log(RequestOutcome.UPSTREAM_WRITE_FAILED)
          .then_no_upstream_close_is_counted();
    }
  }

  @Test
  void writeFailureAfterAPartialBodyDropsTheConnections() {
    try (Steps steps = new Steps()) {
      steps
          .given_a_request_forwarded_upstream()
          .when_the_upstream_sends_the_response_headers()
          .when_the_upstream_sends_a_body_part()
          .when_the_upstream_sends_a_body_part()
          .when_the_upstream_write_fails()
          .then_the_client_received_only_the_upstream_response(2)
          .then_the_connections_are_closed()
          .then_the_outcome_is(RequestOutcome.UPSTREAM_WRITE_FAILED)
          .then_the_failure_is_in_the_request_log(RequestOutcome.UPSTREAM_WRITE_FAILED);
    }
  }

  /// The upstream may answer before it read the whole request, and the failed request write
  /// arrives after the response; the next request on the connection must not be affected.
  @Test
  void writeFailureAfterTheResponseIsIgnored() {
    try (Steps steps = new Steps()) {
      steps
          .given_a_request_forwarded_upstream()
          .when_the_upstream_sends_the_response_headers()
          .when_the_upstream_sends_a_body_part()
          .when_the_upstream_completes_the_response()
          .when_the_upstream_write_fails()
          .then_the_client_received_the_complete_response()
          .then_the_client_connection_is_open()
          .then_the_upstream_is_closed()
          .then_the_outcome_is(RequestOutcome.COMPLETED)
          .then_the_late_failure_is_ignored();
    }
  }

  @Test
  void secondRequestAfterTheHeadersDropsTheConnections() {
    try (Steps steps = new Steps()) {
      steps
          .given_a_request_forwarded_upstream()
          .when_the_upstream_sends_the_response_headers()
          .when_the_upstream_sends_a_body_part()
          .when_the_client_sends_another_request()
          .then_the_client_received_only_the_upstream_response(1)
          .then_the_connections_are_closed();
    }
  }

  /// The refusal of the second request ends the exchange, the connect failure that follows is
  /// not answered a second time.
  @Test
  void connectFailureAfterTheExchangeEndedIsIgnored() {
    try (Steps steps = new Steps()) {
      steps
          .given_a_request_while_the_upstream_is_connecting()
          .when_the_client_sends_another_request()
          .when_the_upstream_connect_fails()
          .then_the_client_received_only_a_bad_request()
          .then_the_client_connection_is_closed()
          .then_the_outcome_is(RequestOutcome.COMPLETED)
          .then_the_late_failure_is_ignored();
    }
  }

  class Steps implements AutoCloseable {
    private final ProxyMetrics metrics = new ProxyMetrics();
    private final RequestLogCapture requestLog = new RequestLogCapture();
    private final List<Throwable> loggedErrors = new ArrayList<>();
    private Throwable failure;
    private final HoldLastContentWrite heldWrite = new HoldLastContentWrite();
    private EmbeddedChannel ingress;
    private EmbeddedChannel upstream;
    private Promise<Channel> connecting;

    @Step
    Steps given_a_request_forwarded_upstream() {
      startRequest(
          (UpstreamConnector)
              ctx -> {
                upstream =
                    new EmbeddedChannel(
                        heldWrite, new ForwardHandler(ctx.channel(), metrics, UPSTREAM));
                return ctx.executor().newSucceededFuture(upstream);
              });
      assertThat(heldWrite.promise).as("request is written upstream").isNotNull();
      return this;
    }

    @Step
    Steps given_a_request_while_the_upstream_is_connecting() {
      startRequest((UpstreamConnector) ctx -> connecting = ctx.executor().newPromise());
      return this;
    }

    /// Sends a request without content through the ingress pipeline, shared by the givens
    private void startRequest(UpstreamConnector upstreamConnector) {
      RequestLogger logger = requestLog.plainText();
      ingress =
          new EmbeddedChannel(
              new TimeoutsTest.FakeSslHandler(),
              new RequestLoggingHandler(
                  metrics,
                  info -> {
                    loggedErrors.add(info.error());
                    logger.logRequest(info);
                  }),
              new IngressTimeoutHandler(ConnectionConfig.DEFAULT, metrics),
              new DownstreamHandler(upstreamConnector.named(), metrics),
              new ExceptionHandler(metrics));
      ingress.runPendingTasks();
      ingress.writeInbound(
          new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/data"),
          LastHttpContent.EMPTY_LAST_CONTENT);
      ingress.runPendingTasks();
    }

    @Step
    Steps when_the_upstream_sends_the_response_headers() {
      HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
      response.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
      return fromUpstream(response);
    }

    @Step
    Steps when_the_upstream_sends_a_body_part() {
      return fromUpstream(new DefaultHttpContent(Unpooled.copiedBuffer(new byte[] {1, 2, 3})));
    }

    @Step
    Steps when_the_upstream_completes_the_response() {
      return fromUpstream(new DefaultLastHttpContent());
    }

    /// Passes a response part from the upstream to the client, which receives it at once
    private Steps fromUpstream(Object msg) {
      upstream.writeInbound(msg);
      ingress.flush();
      ingress.runPendingTasks();
      return this;
    }

    @Step
    Steps when_the_upstream_write_fails() {
      failure = new IOException("Broken pipe");
      heldWrite.promise.setFailure(failure);
      ingress.runPendingTasks();
      return this;
    }

    @Step
    Steps when_the_upstream_connect_fails() {
      failure = new ConnectException("Connection refused");
      connecting.setFailure(failure);
      ingress.runPendingTasks();
      return this;
    }

    /// The client left, the next response write to it fails, as in production with a
    /// `StacklessClosedChannelException`. Fired directly: a closed `EmbeddedChannel` is
    /// deregistered at once, so its failed void promise would not reach the pipeline.
    @Step
    Steps when_a_write_to_the_client_fails_since_it_left() {
      ingress.pipeline().fireExceptionCaught(new ClosedChannelException());
      ingress.runPendingTasks();
      return this;
    }

    /// The upstream goes away: a close by the peer, reported by its [ForwardHandler]
    @Step
    Steps when_the_upstream_closes_the_connection() {
      upstream.unsafe().close(upstream.voidPromise());
      ingress.runPendingTasks();
      return this;
    }

    @Step
    Steps when_a_close_of_another_upstream_is_reported() {
      EmbeddedChannel other = new EmbeddedChannel();
      ingress.pipeline().fireUserEventTriggered(new ForwardHandler.UpstreamClosed(other));
      other.finishAndReleaseAll();
      ingress.runPendingTasks();
      return this;
    }

    @Step
    Steps then_the_upstream_close_is_counted() {
      assertThat(closedInFlight()).isEqualTo(1);
      return this;
    }

    @Step
    Steps then_no_upstream_close_is_counted() {
      assertThat(closedInFlight()).isZero();
      return this;
    }

    private double closedInFlight() {
      return metrics
          .upstreamErrorCounter
          .labelValues(UPSTREAM, ProxyMetrics.UpstreamErrorType.CLOSED_IN_FLIGHT.label)
          .get();
    }

    @Step
    Steps when_the_client_sends_another_request() {
      ingress.writeInbound(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/2"));
      ingress.runPendingTasks();
      return this;
    }

    @Step
    Steps then_the_client_received_only_a_bad_gateway(String reason) {
      HttpResponse response = onlyResponse();
      assertThat(response.status().code()).isEqualTo(502);
      assertThat(response.status().reasonPhrase()).isEqualTo(reason);
      return this;
    }

    @Step
    Steps then_the_client_received_only_a_bad_request() {
      assertThat(onlyResponse().status().code()).isEqualTo(400);
      return this;
    }

    /// The single message the client received, a complete error response
    private HttpResponse onlyResponse() {
      List<Object> received = received();
      assertThat(received).singleElement().isInstanceOf(LastHttpContent.class);
      return (HttpResponse) received.get(0);
    }

    @Step
    Steps then_the_client_received_only_the_upstream_response(int bodyParts) {
      List<Object> received = received();
      assertThat(received).hasSize(1 + bodyParts);
      HttpResponse response = (HttpResponse) received.get(0);
      assertThat(response.status()).isEqualTo(HttpResponseStatus.OK);
      assertThat(received.subList(1, received.size()))
          .allSatisfy(
              part ->
                  assertThat(part)
                      .isInstanceOf(HttpContent.class)
                      .isNotInstanceOf(LastHttpContent.class));
      return this;
    }

    @Step
    Steps then_the_client_received_the_complete_response() {
      List<Object> received = received();
      assertThat(received).hasSize(3);
      assertThat(((HttpResponse) received.get(0)).status()).isEqualTo(HttpResponseStatus.OK);
      assertThat(received.get(2)).isInstanceOf(LastHttpContent.class);
      return this;
    }

    /// All messages written to the client so far
    private List<Object> received() {
      List<Object> received = new ArrayList<>();
      Object msg;
      while ((msg = ingress.readOutbound()) != null) {
        received.add(msg);
        ReferenceCountUtil.release(msg);
      }
      return received;
    }

    @Step
    Steps then_the_connections_are_closed() {
      then_the_client_connection_is_closed();
      return then_the_upstream_is_closed();
    }

    @Step
    Steps then_the_client_connection_is_closed() {
      assertThat(ingress.isOpen()).isFalse();
      return this;
    }

    @Step
    Steps then_the_client_connection_is_open() {
      assertThat(ingress.isOpen()).isTrue();
      return this;
    }

    @Step
    Steps then_the_upstream_is_closed() {
      assertThat(upstream.isOpen()).isFalse();
      return this;
    }

    /// @param outcome the outcome of the single logged request
    @Step
    Steps then_the_outcome_is(RequestOutcome outcome) {
      assertThat(requestLog.outcomes()).containsExactly(outcome);
      assertThat(metrics.requestOutcomeCounter.collect().getDataPoints())
          .singleElement()
          .satisfies(
              point -> {
                assertThat(point.getLabels().get("outcome")).isEqualTo(outcome.value());
                assertThat(point.getValue()).isEqualTo(1);
              });
      return this;
    }

    /// The cause is in the request log next to the outcome, so there is no diagnostic line, see
    /// [ProxyLogger] "Levels"
    @Step
    Steps then_the_failure_is_in_the_request_log(RequestOutcome outcome) {
      assertThat(loggedErrors).containsExactly(failure);
      assertThat(requestLog.outcomes()).containsExactly(outcome);
      assertThat(logs.messages(DownstreamHandler.class)).isEmpty();
      return this;
    }

    @Step
    Steps then_the_late_failure_is_ignored() {
      assertThat(loggedErrors).containsExactly((Throwable) null);
      // no line mentions the late failure; a second request is logged, it has no record
      assertThat(logs.messages(DownstreamHandler.class))
          .noneMatch(message -> message.contains(failure.getMessage()));
      return this;
    }

    @Override
    public void close() {
      if (ingress != null) {
        ingress.finishAndReleaseAll();
      }
      if (upstream != null) {
        upstream.finishAndReleaseAll();
      }
    }
  }

  /// The `upstream` label of the metrics, the name of the upstream
  static final String UPSTREAM = "upstream";

  /// The connect of an [Upstream] as a lambda, made a named upstream by [#named()]
  interface UpstreamConnector {
    Future<Channel> connect(ChannelHandlerContext ctx);

    /// The upstream named [#UPSTREAM], the label its metrics are counted with
    default Upstream named() {
      return new Upstream() {
        @Override
        public Future<Channel> connect(ChannelHandlerContext ctx) {
          return UpstreamConnector.this.connect(ctx);
        }

        @Override
        public String name() {
          return UPSTREAM;
        }
      };
    }
  }

  /// Holds the write of the last request content on the upstream channel, so the test decides
  /// when and how it completes
  static class HoldLastContentWrite extends ChannelOutboundHandlerAdapter {
    ChannelPromise promise;

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
        throws Exception {
      if (msg instanceof LastHttpContent) {
        ReferenceCountUtil.release(msg);
        this.promise = promise;
        return;
      }
      super.write(ctx, msg, promise);
    }
  }
}
