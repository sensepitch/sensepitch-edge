package org.sensepitch.edge;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.sensepitch.edge.TestingParameters.EVENT_TIMEOUT_SECONDS;
import static org.sensepitch.edge.TestingParameters.TEST_TIMEOUT_SECONDS;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.local.LocalIoHandler;
import io.netty.channel.local.LocalServerChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;
import java.io.IOException;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/// The client and the upstream channel of an exchange on two different event loops, as an upstream
/// pool shared by the loops would hand out. [DefaultUpstream] opens the upstream on the client's
/// loop, so this is the case the handlers tolerate, see [Upstream#connect].
///
/// Runs on real event loops with the in-VM transport: an `EmbeddedChannel` cannot show a call on
/// the wrong loop, its `inEventLoop()` is always `true`. A handler that changes exchange state off
/// the client's loop trips an assertion of [DownstreamHandler], then the client gets no response.
@Timeout(TEST_TIMEOUT_SECONDS)
class UpstreamOnAnotherLoopTest {

  private static final String BODY = "from the upstream";

  private final EventLoopGroup clientLoop =
      new MultiThreadIoEventLoopGroup(1, LocalIoHandler.newFactory());
  private final EventLoopGroup upstreamLoop =
      new MultiThreadIoEventLoopGroup(1, LocalIoHandler.newFactory());
  private final ProxyMetrics metrics = new ProxyMetrics();
  private final RequestLogCapture requestLog = new RequestLogCapture();
  private final Semaphore requestsLogged = new Semaphore(0);
  private final BlockingQueue<FullHttpResponse> responses = new LinkedBlockingQueue<>();
  private volatile Channel upstreamChannel;

  /// What the upstream does with a request
  private enum Target {
    ANSWERS,
    CLOSES,
    /// never answers, so only the edge can end the exchange
    SILENT
  }

  @AfterEach
  void shutdown() throws Exception {
    for (EventLoopGroup group : new EventLoopGroup[] {clientLoop, upstreamLoop}) {
      group
          .shutdownGracefully(0, EVENT_TIMEOUT_SECONDS, SECONDS)
          .get(EVENT_TIMEOUT_SECONDS, SECONDS);
    }
    responses.forEach(ReferenceCountUtil::release);
  }

  @Test
  void completeExchange() throws Exception {
    FullHttpResponse response = exchange(Target.ANSWERS, false);
    assertThat(upstreamChannel.eventLoop().parent()).isSameAs(upstreamLoop);
    assertThat(response.status()).isEqualTo(HttpResponseStatus.OK);
    assertThat(response.content().toString(StandardCharsets.UTF_8)).isEqualTo(BODY);
    assertThat(loggedOutcome()).isEqualTo(RequestOutcome.COMPLETED);
  }

  /// The listener of the failed write runs on the upstream's loop and hands the end of the
  /// exchange over to the client's loop. The upstream stays silent, so the `502` can only come
  /// from that hand over.
  @Test
  void upstreamWriteFailure() throws Exception {
    FullHttpResponse response = exchange(Target.SILENT, true);
    assertThat(response.status().code()).isEqualTo(502);
    assertThat(response.status().reasonPhrase()).isEqualTo("Upstream write problem");
    assertThat(loggedOutcome()).isEqualTo(RequestOutcome.UPSTREAM_WRITE_FAILED);
  }

  /// The close is reported by the [ForwardHandler] on the upstream's loop, as an event fired into
  /// the client pipeline
  @Test
  void upstreamClosesInFlight() throws Exception {
    FullHttpResponse response = exchange(Target.CLOSES, false);
    assertThat(response.status().code()).isEqualTo(502);
    assertThat(response.status().reasonPhrase()).isEqualTo("Upstream closed");
    assertThat(loggedOutcome()).isEqualTo(RequestOutcome.UPSTREAM_CLOSED);
  }

  /// Sends one request through the edge handlers and returns the response the client received
  ///
  /// @param failWrites the upstream channel fails the write of the last request content
  private FullHttpResponse exchange(Target target, boolean failWrites) throws Exception {
    SocketAddress targetAddress = startTarget(target);
    SocketAddress edgeAddress = startEdge(upstreamOnAnotherLoop(targetAddress, failWrites));
    Channel client =
        new Bootstrap()
            .group(clientLoop)
            .channel(LocalChannel.class)
            .handler(
                new ChannelInitializer<LocalChannel>() {
                  @Override
                  protected void initChannel(LocalChannel ch) {
                    ch.pipeline()
                        .addLast(
                            new HttpClientCodec(),
                            new HttpObjectAggregator(Integer.MAX_VALUE),
                            new SimpleChannelInboundHandler<FullHttpResponse>() {
                              @Override
                              protected void channelRead0(
                                  ChannelHandlerContext ctx, FullHttpResponse msg) {
                                responses.add(msg.retain());
                              }
                            });
                  }
                })
            .connect(edgeAddress)
            .sync()
            .channel();
    client
        .writeAndFlush(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/data"))
        .sync();
    FullHttpResponse response = responses.poll(EVENT_TIMEOUT_SECONDS, SECONDS);
    assertThat(response).as("response to the client").isNotNull();
    return response;
  }

  /// The edge handlers of an ingress channel on the client's loop, as in [UpstreamFailureBDDTest]
  private SocketAddress startEdge(Upstream upstream) throws InterruptedException {
    RequestLogger logger = requestLog.plainText();
    return new ServerBootstrap()
        .group(clientLoop)
        .channel(LocalServerChannel.class)
        .childHandler(
            new ChannelInitializer<LocalChannel>() {
              @Override
              protected void initChannel(LocalChannel ch) {
                ch.pipeline()
                    .addLast(
                        new HttpServerCodec(),
                        new TimeoutsTest.FakeSslHandler(),
                        new RequestLoggingHandler(
                            metrics,
                            info -> {
                              logger.logRequest(info);
                              requestsLogged.release();
                            }),
                        new IngressTimeoutHandler(ConnectionConfig.DEFAULT, metrics),
                        new DownstreamHandler(upstream, metrics),
                        new ExceptionHandler(metrics));
              }
            })
        .bind(LocalAddress.ANY)
        .sync()
        .channel()
        .localAddress();
  }

  /// An upstream whose channel runs on [#upstreamLoop]; the connect future is completed on the
  /// client's loop, as [Upstream#connect] requires
  private Upstream upstreamOnAnotherLoop(SocketAddress target, boolean failWrites) {
    return new Upstream() {
      @Override
      public Future<Channel> connect(ChannelHandlerContext ingress) {
        Promise<Channel> promise = ingress.executor().newPromise();
        new Bootstrap()
            .group(upstreamLoop)
            .channel(LocalChannel.class)
            .handler(
                new ChannelInitializer<LocalChannel>() {
                  @Override
                  protected void initChannel(LocalChannel ch) {
                    ch.pipeline().addLast(new HttpClientCodec());
                    if (failWrites) {
                      ch.pipeline().addLast(new FailLastContentWrite());
                    }
                    ch.pipeline()
                        .addLast(
                            new ForwardHandler(
                                ingress.channel(), metrics, UpstreamFailureBDDTest.UPSTREAM));
                  }
                })
            .connect(target)
            .addListener(
                (ChannelFutureListener)
                    f -> {
                      if (f.isSuccess()) {
                        upstreamChannel = f.channel();
                        promise.setSuccess(f.channel());
                      } else {
                        promise.setFailure(f.cause());
                      }
                    });
        return promise;
      }

      @Override
      public String name() {
        return UpstreamFailureBDDTest.UPSTREAM;
      }
    };
  }

  /// The upstream server, on the upstream's loop
  private SocketAddress startTarget(Target target) throws InterruptedException {
    return new ServerBootstrap()
        .group(upstreamLoop)
        .channel(LocalServerChannel.class)
        .childHandler(
            new ChannelInitializer<LocalChannel>() {
              @Override
              protected void initChannel(LocalChannel ch) {
                ch.pipeline()
                    .addLast(
                        new HttpServerCodec(),
                        new SimpleChannelInboundHandler<LastHttpContent>() {
                          @Override
                          protected void channelRead0(
                              ChannelHandlerContext ctx, LastHttpContent msg) {
                            if (target == Target.SILENT) {
                              return;
                            }
                            if (target == Target.CLOSES) {
                              ctx.close();
                              return;
                            }
                            FullHttpResponse response =
                                new DefaultFullHttpResponse(
                                    HttpVersion.HTTP_1_1,
                                    HttpResponseStatus.OK,
                                    Unpooled.copiedBuffer(BODY, StandardCharsets.UTF_8));
                            response
                                .headers()
                                .set(
                                    HttpHeaderNames.CONTENT_LENGTH,
                                    response.content().readableBytes());
                            ctx.writeAndFlush(response);
                          }
                        });
              }
            })
        .bind(LocalAddress.ANY)
        .sync()
        .channel()
        .localAddress();
  }

  /// The outcome of the single request, once it is logged
  private RequestOutcome loggedOutcome() throws InterruptedException {
    assertThat(requestsLogged.tryAcquire(EVENT_TIMEOUT_SECONDS, SECONDS))
        .as("request logged")
        .isTrue();
    assertThat(requestLog.outcomes()).hasSize(1);
    return requestLog.outcomes().get(0);
  }

  /// Fails the write of the last request content, as a broken upstream connection would
  private static class FailLastContentWrite extends ChannelOutboundHandlerAdapter {
    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
        throws Exception {
      if (msg instanceof LastHttpContent) {
        ReferenceCountUtil.release(msg);
        promise.setFailure(new IOException("Broken pipe"));
        return;
      }
      super.write(ctx, msg, promise);
    }
  }
}
