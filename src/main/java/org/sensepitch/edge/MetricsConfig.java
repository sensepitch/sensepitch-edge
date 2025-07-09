package org.sensepitch.edge;

import lombok.Builder;

/**
 * @author Jens Wilke
 */
@Builder
public record MetricsConfig(
  boolean enable,
  PrometheusConfig prometheus
) {
}
