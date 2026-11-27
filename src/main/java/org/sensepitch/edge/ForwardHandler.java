package org.sensepitch.edge;

import static org.sensepitch.edge.ProxyMetrics.UpstreamErrorType.EXCEPTION_WITHOUT_REQUEST;
import static org.sensepitch.edge.ProxyMetrics.UpstreamErrorType.UNEXPECTED_DATA;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelException;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOption;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpStatusClass;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;

/// Handler for upstream communication, receives response from upstream and passes it downstream.
///
/// An informational response (1xx) is passed on together with its `LastHttpContent`, but does not
/// end the exchange: the upstream stays attached to the downstream for the final response.
///
/// An exception during an exchange is passed to the downstream pipeline as [UpstreamException],
/// where [ExceptionHandler] counts and logs it, so it is not logged here. The other events of a misbehaving upstream are
/// counted in [ProxyMetrics#upstreamErrorCounter] and logged at debug, see [ProxyLogger] for the
/// levels: data after the response and an exception after the response. Their first occurrences
/// are logged at warning, see [ProxyLogger#incrementAndWarnOnce].
///
/// A close while the exchange is attached is passed to the client pipeline as [UpstreamClosed].
/// Only the [DownstreamHandler] knows whether it closed the upstream itself, e.g. after a timeout
/// or a client abort, or whether the upstream went away; it counts and answers the latter.
///
/// The fields of this handler are only used on the event loop of the upstream channel. It reaches
/// the client channel only through Netty calls that hand over to the client's event loop (write,
/// firing events into its pipeline, setting `AUTO_READ`), so the upstream channel may run on
/// another event loop than the client channel, see [Upstream#connect].
///
/// @author Jens Wilke
public class ForwardHandler extends ChannelInboundHandlerAdapter {

  private static final ProxyLogger LOG = ProxyLogger.get(ForwardHandler.class);

  private final ProxyMetrics metrics;
  private final String upstream;
  private Channel downstream;
  private boolean pausedDownstreamReads;

  /// An informational response was forwarded, its `LastHttpContent` follows, then the final
  /// response. The upstream stays attached to the downstream until that final response ends.
  private boolean informationalResponse;

  /// @param upstream name of the upstream, the label of [ProxyMetrics#upstreamErrorCounter]
  public ForwardHandler(Channel ingress, ProxyMetrics metrics, String upstream) {
    this.downstream = ingress;
    this.metrics = metrics;
    this.upstream = upstream;
  }

  /// Binds the next exchange to this connection, taken idle from the pool, see
  /// [UpstreamConnectionPool]. Call on the event loop of the upstream channel; [DefaultUpstream]
  /// takes the connection on that loop.
  void attach(Channel ingress) {
    downstream = ingress;
    informationalResponse = false;
    pausedDownstreamReads = false;
  }

  /// Tells the client pipeline that the upstream channel of its exchange closed. Fired after the
  /// response parts read before, a response delimited by the close is complete by then: the HTTP
  /// decoder emits its last content before `channelInactive` reaches this handler.
  @Override
  public void channelInactive(ChannelHandlerContext ctx) throws Exception {
    if (downstream != null) {
      Channel exchangeDownstream = downstream;
      downstream = null;
      exchangeDownstream.pipeline().fireUserEventTriggered(new UpstreamClosed(ctx.channel()));
    }
    super.channelInactive(ctx);
  }

  /// The upstream channel closed while an exchange was attached to it, see
  /// [DownstreamHandler#userEventTriggered]
  ///
  /// @param upstream the closed channel, to tell whether it still belongs to the active exchange
  record UpstreamClosed(Channel upstream) {}

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) {
    if (downstream == null) {
      LOG.incrementAndWarnOnce(
          metrics.upstreamErrorCounter,
          new String[] {upstream, UNEXPECTED_DATA.label},
          ctx.channel(),
          msg.getClass().getName()
              + " -> downstream is null, getting unexpected data from upstream");
      ReferenceCountUtil.release(msg);
      // out of sync with the upstream, not usable for another exchange; an idle pooled
      // connection leaves the pool with the close
      ctx.close();
      return;
    }
    if (msg instanceof HttpResponse) {
      informationalResponse =
          ((HttpResponse) msg).status().codeClass() == HttpStatusClass.INFORMATIONAL;
      // if message contains response and content, write below
      if (!(msg instanceof HttpContent)) {
        downstream.write(msg);
      }
    }
    if (msg instanceof LastHttpContent) {
      // The write of an empty last content will fail regularly, since the client might have closed
      // the connection already. Maybe introduce error handling for non empty last contents and
      // requests only.
      downstream.writeAndFlush(msg, downstream.voidPromise());
      boolean informational = informationalResponse;
      informationalResponse = false;
      if (!informational) {
        resumeDownstreamReads();
        downstream = null;
      }
    } else if (msg instanceof HttpContent) {
      downstream.write(msg, downstream.voidPromise());
    }
  }

  /// Flush if output buffer is full and apply back pressure to downstream: pause the client reads
  /// if they are on, and resume only a pause imposed here, when the upstream is writable again or
  /// at the end of the response, see [#resumeDownstreamReads()]. Today nothing else switches the
  /// client reads off while the upstream is unwritable: the pause of [DownstreamHandler] during the
  /// upstream connect ends before the request is flushed. The guard keeps a future second reason
  /// to pause, e.g. rate limiting, intact.
  @Override
  public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
    if (downstream == null) {
      return;
    }
    if (ctx.channel().isWritable()) {
      resumeDownstreamReads();
    } else {
      if (downstream.config().isAutoRead()) {
        pausedDownstreamReads = true;
        downstream.setOption(ChannelOption.AUTO_READ, false);
      }
      flush(ctx);
    }
  }

  /// Release this exchange's backpressure when the upstream catches up or finishes responding.
  private void resumeDownstreamReads() {
    if (pausedDownstreamReads) {
      pausedDownstreamReads = false;
      downstream.setOption(ChannelOption.AUTO_READ, true);
    }
  }

  /// Flush output to upstream. If we don't stop reading from ingress fast enough it may happen that
  /// the output buffer is already filled again when the flush is complete. Issue another flush
  /// until writable again.
  void flush(ChannelHandlerContext ctx) {
    ctx.channel()
        .writeAndFlush(Unpooled.EMPTY_BUFFER)
        .addListener(
            future -> {
              if (future.isSuccess()) {
                if (!ctx.channel().isWritable()) {
                  flush(ctx);
                }
              }
            });
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
    if (downstream != null) {
      // cleared first: the exception handling closes the upstream, which is no close in flight
      Channel exchangeDownstream = downstream;
      downstream = null;
      exchangeDownstream.pipeline().fireExceptionCaught(new UpstreamException(cause));
    } else {
      LOG.incrementAndWarnOnce(
          metrics.upstreamErrorCounter,
          new String[] {upstream, EXCEPTION_WITHOUT_REQUEST.label},
          ctx.channel(),
          "upstream failure without an exchange: " + cause);
    }
    ctx.close();
  }

  static class UpstreamException extends ChannelException {

    public UpstreamException(Throwable cause) {
      super(cause);
    }
  }
}
