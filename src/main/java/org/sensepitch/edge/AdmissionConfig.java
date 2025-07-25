package org.sensepitch.edge;

import lombok.Builder;

import java.util.List;

/**
 * test
 * 
 * @author Jens Wilke
 */
@Builder
@Deprecated
public record AdmissionConfig(
  @Deprecated
  /**
   * javadoc for the parameter
   */
  String serverIpv4Address,
  BypassConfig bypass,
  NoBypassConfig noBypass,
  DetectCrawlerConfig detectCrawler,
  List<AdmissionTokenGeneratorConfig> tokenGenerator
) { }
