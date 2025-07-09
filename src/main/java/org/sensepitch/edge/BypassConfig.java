package org.sensepitch.edge;

import lombok.Builder;

import java.util.List;

/**
 * @author Jens Wilke
 */
@Builder
public record BypassConfig(
  DetectCrawlerConfig detectCrawler,
  List<String> uriPrefixes,
  List<String> uriSuffixes,
  boolean disableDefaultSuffixes,
  List<String> hosts,
  List<String> remotes
) { }
