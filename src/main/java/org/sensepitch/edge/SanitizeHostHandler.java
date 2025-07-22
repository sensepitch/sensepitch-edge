package org.sensepitch.edge;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * Augments the host header and sets only well-known values.
 *
 * <p>All valid clients are expected to set a correct host header according to the host
 * they want to contact. However, clients might leave the header out or just put garbage in there.
 * We don't want to have that further polluting our systems, or, do defensive coding every time
 * the host header is used.
 *
 * @see RequestLogInfo#requestHeaderHost()
 *
 * @author Jens Wilke
 */
@ChannelHandler.Sharable
public class SanitizeHostHandler extends ChannelInboundHandlerAdapter {

  /**
   * Alternative host that is set when the host from the client does not match any
   * of the serviced hosts.
   */
  public static final String HOST_MISMATCH = "mismatch";
  /**
   * Alternative host that is set in case the host header was absent.
   */
  public static final String HOST_MISSING = "missing";
  /**
   * Alternative host that is set if we never received a header. This value is
   * used within the {@link RequestLoggingHandler} but defined here for completeness.
   */
  public static final String HOST_NIL = "nil";

  private final Set<String> servicedHosts = new HashSet<>();

  public SanitizeHostHandler(Collection<String> servicedHosts) {
    this.servicedHosts.addAll(servicedHosts);
  }

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
    if (msg instanceof HttpRequest request) {
      String host = request.headers().get(HttpHeaderNames.HOST);
      if (host == null) {
        request.headers().set(HttpHeaderNames.HOST, HOST_MISSING);
      } else if (!servicedHosts.contains(host)) {
        request.headers().set(HttpHeaderNames.HOST, HOST_MISMATCH);
      }
    }
    super.channelRead(ctx, msg);
  }

}
