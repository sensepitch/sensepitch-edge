package org.sensepitch.edge;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author Jens Wilke
 */
public class UpstreamRouter {

  private final Map<String, Upstream> host2upstream;

  public UpstreamRouter(List<UpstreamConfig> cfg) {
    host2upstream = new HashMap<String, Upstream>();
    for (UpstreamConfig upCfg : cfg) {
      String host = upCfg.host();
      Upstream upstream = new Upstream(upCfg);
      host2upstream.put(host, upstream);
    }
  }

  public Upstream selectUpstream(ChannelHandlerContext ctx, HttpRequest request) {
    String host = request.headers().get(HttpHeaderNames.HOST);
    return host2upstream.get(host);
  }

}
