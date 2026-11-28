package org.sensepitch.edge;

import lombok.Builder;

/// Export of the metrics for Prometheus.
///
/// @param disabled no metrics export. A flag, so a later configuration layer can switch off a
///   default.
/// @param prometheus port and content of the export
/// @author Jens Wilke
@Builder(toBuilder = true)
public record MetricsConfig(boolean disabled, PrometheusConfig prometheus) {

  public static final MetricsConfig DEFAULT =
      builder().prometheus(PrometheusConfig.DEFAULT).build();
}
