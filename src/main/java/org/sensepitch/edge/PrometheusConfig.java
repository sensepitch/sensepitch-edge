package org.sensepitch.edge;

import lombok.Builder;

/// The Prometheus endpoint of the metrics export.
///
/// @param port the port of the endpoint
/// @param disableJvmMetrics export no JVM metrics (memory, GC, threads), only those of the proxy
/// @author Jens Wilke
@Builder(toBuilder = true)
public record PrometheusConfig(int port, boolean disableJvmMetrics) {

  public static final PrometheusConfig DEFAULT = builder().port(9400).build();
}
