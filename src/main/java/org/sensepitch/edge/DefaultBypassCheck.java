package org.sensepitch.edge;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * @author Jens Wilke
 */
public class DefaultBypassCheck implements BypassCheck {

  static ProxyLogger LOG = ProxyLogger.get(DefaultBypassCheck.class);

  private final NavigableMap<String, BypassCheck> uriPrefixList = new TreeMap<>();
  private final NavigableMap<String, BypassCheck> uriSuffixList = new TreeMap<>();
  private final Map<String, BypassCheck> hostMap = new HashMap<>();
  private final Map<String, BypassCheck> remoteAddressMap = new HashMap<>();
  private final DetectCrawler detectCrawler;

  /**
   * List of suffixes we always pass through without challenge check.
   */
  public final List<String> DEFAULT_STATIC_SUFFIXES = Arrays.asList(
    ".txt",
    ".png",
    ".css"
  );

  public DefaultBypassCheck(BypassConfig cfg) {
    if (cfg.uriPrefixes() != null) {
      cfg.uriPrefixes().forEach(prefix -> uriPrefixList.put(prefix, BypassCheck.DO_BYPASS));
    }
    if (cfg.uriSuffixes() != null) {
      addSuffixes(cfg.uriSuffixes());
    }
    if (!cfg.disableDefaultSuffixes()) {
      addSuffixes(DEFAULT_STATIC_SUFFIXES);
    }
    if (cfg.hosts() != null) {
      cfg.hosts().forEach(host -> hostMap.put(host, BypassCheck.DO_BYPASS));
    }
    if (cfg.remotes() != null) {
      for (String remote : cfg.remotes()) {
        try {
          InetAddress addr = InetAddress.getByName(remote);
          remoteAddressMap.put(addr.getHostAddress(),  BypassCheck.DO_BYPASS);
        } catch (UnknownHostException e) {
          // only report error, if there is a temporary DNS problem when we start,
          // we still want to start
          LOG.error("Cannot resolve bypass remote " + remote, e);
        }
      }
    }
    if (cfg.detectCrawler() != null) {
      detectCrawler = new DetectCrawler(cfg.detectCrawler());
    } else {
      detectCrawler = new DetectCrawler(DetectCrawlerConfig.builder().build());
    }
  }

  private void addSuffixes(List<String> suffixes) {
    suffixes.forEach(suffix -> {
        String reversed = new StringBuilder(suffix).reverse().toString();
        uriSuffixList.put(reversed, BypassCheck.DO_BYPASS);
      }
    );
  }

  @Override
  public boolean allowBypass(Channel channel, HttpRequest request) {
    BypassCheck hostCheck = hostMap.get(request.headers().get(HttpHeaderNames.HOST));
    if (hostCheck != null) {
      return hostCheck.allowBypass(channel, request);
    }
    if (channel.remoteAddress() instanceof InetSocketAddress addr) {
      String remoteHost = addr.getAddress().getHostAddress();
      BypassCheck remoteCheck = remoteAddressMap.get(remoteHost);
      if (remoteCheck != null) {
        return remoteCheck.allowBypass(channel, request);
      }
    }
    if (detectCrawler != null &&  detectCrawler.allowBypass(channel, request)) {
      return true;
    }
    String uri = request.uri();
    Map.Entry<String, BypassCheck> candidate = uriPrefixList.floorEntry(uri);
    if (candidate != null) {
      String prefix = candidate.getKey();
      if (uri.startsWith(prefix)) {
        return candidate.getValue().allowBypass(channel, request);
      }
    }
    String uriReversed = new StringBuilder(uri).reverse().toString();
    candidate = uriSuffixList.floorEntry(uriReversed);
    if (candidate != null) {
      String prefix = candidate.getKey();
      if (uri.startsWith(prefix)) {
        return candidate.getValue().allowBypass(channel, request);
      }
    }
    return false;
  }

}
