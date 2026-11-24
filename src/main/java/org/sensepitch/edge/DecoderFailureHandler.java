package org.sensepitch.edge;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.TooLongHttpHeaderException;
import io.netty.handler.codec.http.TooLongHttpLineException;
import io.netty.util.ReferenceCountUtil;

/// Answers a request head the HTTP codec could not decode, instead of passing it on to site
/// selection, where the empty head would be rejected as a missing host:
/// - 431 if the header is larger than the codec's limit (`TooLongHttpHeaderException`)
/// - 414 if the request line is longer than the codec's limit (`TooLongHttpLineException`)
/// - 400 for any other decoder failure, e.g. a malformed request line or header
///
/// Both size refusals are reported as [RequestOutcome#REQUEST_TOO_LARGE]; a 400 keeps the derived
/// outcome [RequestOutcome#EDGE_RESPONSE], like other requests the edge rejects itself. The
/// response has `Connection: close`, so [IngressTimeoutHandler] closes the connection after it:
/// the codec discards everything the client sends after a failure, so the connection cannot be
/// reused.
///
/// Place it after [RequestLoggingHandler] and [IngressTimeoutHandler], so the request is logged
/// and counted and the timeouts see the response, and before every handler that looks at the
/// request. The limits are Netty's defaults, see `doc/design/request-body-limit-draft.md`.
///
/// @author Jens Wilke
@ChannelHandler.Sharable
public class DecoderFailureHandler extends ChannelInboundHandlerAdapter {

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
    if (msg instanceof HttpRequest request) {
      Throwable cause = request.decoderResult().cause();
      if (cause != null) {
        ReferenceCountUtil.release(request);
        reject(ctx, cause);
        return;
      }
    }
    super.channelRead(ctx, msg);
  }

  /// Sends the status for the decoder failure `cause`, the connection is closed after it
  private static void reject(ChannelHandlerContext ctx, Throwable cause) {
    HttpResponseStatus status = statusFor(cause);
    if (status != HttpResponseStatus.BAD_REQUEST) {
      RequestLoggingHandler.reportOutcome(ctx, RequestOutcome.REQUEST_TOO_LARGE);
    }
    FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status);
    response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
    ctx.writeAndFlush(response);
  }

  /// The response status for a decoder failure, see the class doc
  private static HttpResponseStatus statusFor(Throwable cause) {
    if (cause instanceof TooLongHttpHeaderException) {
      return HttpResponseStatus.REQUEST_HEADER_FIELDS_TOO_LARGE;
    }
    if (cause instanceof TooLongHttpLineException) {
      return HttpResponseStatus.REQUEST_URI_TOO_LONG;
    }
    return HttpResponseStatus.BAD_REQUEST;
  }
}
