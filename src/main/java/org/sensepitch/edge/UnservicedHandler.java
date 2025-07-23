package org.sensepitch.edge;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Redirect all incoming requests that are not in a domain list to a default target.
 * For accepted domains that start with www., a redirect will be made for
 * request arriving without the www. prefix.
 *
 * @author Jens Wilke
 */
@ChannelHandler.Sharable
public class UnservicedHandler extends SkippingChannelInboundHandlerAdapter {

  private final Set<String> passDomains;
  private final String defaultTarget;
  private final Map<String, String> domainRedirects = new HashMap<>();
  public static final String NOT_FOUND_URI = "/NOT_FOUND";

  public UnservicedHandler(RedirectConfig cfg) {
    this.passDomains = Set.copyOf(cfg.passDomains());
    this.defaultTarget = cfg.defaultTarget();
    passDomains.stream()
      .filter(s -> s.startsWith("www."))
      .forEach(domain -> domainRedirects.put(domain.substring(4), "https://" + domain));
    passDomains.forEach(domain -> domainRedirects.remove(domain));
  }

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
    if (msg instanceof HttpRequest request) {
      String host = ((HttpRequest) msg).headers().get(HttpHeaderNames.HOST);
      if (host == null || SanitizeHostHandler.MISSING_HOST.equals(host)) {
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.BAD_REQUEST);
        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        ctx.writeAndFlush(response);
        skipFollowingContent(ctx);
        return;
      }
      // request to a host that we don't answer for, possible scanner
      if (SanitizeHostHandler.UNKNOWN_HOST.equals(host)) {
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.BAD_REQUEST);
        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        ctx.writeAndFlush(response);
        skipFollowingContent(ctx);
        return;
      }
      if (!passDomains.contains(host)) {
        String target = domainRedirects.get(host);
        if (target == null) {
          target = defaultTarget;
        }
        // undefined URL should not do a redirect and result in status 200
        if (!"/".equals(request.uri())) {
          target += NOT_FOUND_URI;
        }
        // TODO: configurable? permanent or temporary redirect?
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.FOUND);
        response.headers().set(HttpHeaderNames.LOCATION, target);
        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        ctx.writeAndFlush(response);
        skipFollowingContent(ctx);
        return;
      }
    }
    super.channelRead(ctx, msg);
  }

}
