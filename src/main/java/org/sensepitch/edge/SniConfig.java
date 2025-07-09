package org.sensepitch.edge;

import lombok.Builder;

/**
 * @author Jens Wilke
 */
@Builder
public record SniConfig(
  String domain,
  SslConfig ssl,
  String certificateFile,
  String keyFile) {
}
