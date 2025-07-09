package org.sensepitch.edge;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpRequest;

import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * @author Jens Wilke
 */
public class DefaultNoBypassCheck implements NoBypassCheck {

  private final NavigableMap<String, Object> uriPrefixList = new TreeMap<>();

  public DefaultNoBypassCheck(NoBypassConfig cfg) {
    if (cfg.uriPrefixes() != null) {
      cfg.uriPrefixes().forEach(prefix -> uriPrefixList.put(prefix, this));
    }
  }

  @Override
  public boolean skipBypass(ChannelHandlerContext ctx, HttpRequest request) {
    String uri = request.uri();
    var candidate = uriPrefixList.floorEntry(uri);
    if (candidate != null) {
      String prefix = candidate.getKey();
      if (uri.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }

}
