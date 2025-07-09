package org.sensepitch.edge;

import lombok.Builder;

/**
 * @author Jens Wilke
 */
@Builder
public record PrometheusConfig(
  int port,
  boolean enableJvmMetrics) { }
