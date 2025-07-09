package org.sensepitch.edge;

import lombok.Builder;

/**
 * @author Jens Wilke
 */
@Builder
public record UpstreamConfig (
  String host,
  String target) { }
