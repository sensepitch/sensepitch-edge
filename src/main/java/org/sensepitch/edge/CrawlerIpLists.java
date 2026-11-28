package org.sensepitch.edge;

import java.net.InetAddress;

/// The crawler IP lists [CombinedIpTraitsLookup] takes the `crawler` trait from: the lists of the
/// Sensepitch Known Crawlers release, see
/// [KnownCrawlerLists#readAdmitted(KnownCrawlersConfig)], or the lists embedded in the edge, see
/// [CombinedIpTraitsLookup#readCrawlerIpLists()]. Immutable.
///
/// @author Jens Wilke
@FunctionalInterface
public interface CrawlerIpLists {

  /// @return the name of the crawler the address belongs to, `<vendor>:<list name>`, or `null`
  String crawlerName(InetAddress address);

  /// Sets the crawler traits of `address` in `builder`, [IpTraits#crawlerName()] and, for lists
  /// that know matches which are not admitted, [IpTraits#notAdmittedCrawlerName()]. The default
  /// sets the crawler name only.
  default void collect(IpTraits.Builder builder, InetAddress address) {
    String crawlerName = crawlerName(address);
    if (crawlerName != null) {
      builder.crawlerName(crawlerName);
    }
  }
}
