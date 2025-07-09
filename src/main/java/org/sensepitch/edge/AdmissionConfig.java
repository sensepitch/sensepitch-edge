package org.sensepitch.edge;

import lombok.Builder;

import java.util.List;

/**
 * @author Jens Wilke
 */
@Builder
public record AdmissionConfig(
  String serverIpv4Address,
  BypassConfig bypass,
  NoBypassConfig noBypass,
  DetectCrawlerConfig detectCrawler,
  List<AdmissionTokenGeneratorConfig> tokenGenerator
) { }
