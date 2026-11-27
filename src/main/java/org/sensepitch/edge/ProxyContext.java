package org.sensepitch.edge;

/// @author Jens Wilke
public interface ProxyContext {

  ProxyMetrics metrics();

  /// The environment the configuration defaults are derived from, see [ProxyEnvironment]
  ProxyEnvironment environment();
}
