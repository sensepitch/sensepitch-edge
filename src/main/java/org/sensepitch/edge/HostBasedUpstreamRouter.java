package org.sensepitch.edge;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Select the upstream by host header or SNI
 *
 * @author Jens Wilke
 */
public class HostBasedUpstreamRouter implements UpstreamRouter {

  private final Map<String, Upstream> host2upstream;

  public HostBasedUpstreamRouter(ProxyContext ctx, List<UpstreamConfig> cfg) {
    host2upstream = new HashMap<>();
    for (UpstreamConfig upCfg : cfg) {
      String host = upCfg.host();
      Upstream upstream = new Upstream(ctx, upCfg);
      host2upstream.put(host, upstream);
    }
  }

  @Override
  public Upstream selectUpstream(HttpRequest request) {
    String host = request.headers().get(HttpHeaderNames.HOST);
    return host2upstream.get(host);
  }

}
