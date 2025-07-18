package org.sensepitch.edge;

import lombok.Builder;

import java.util.List;

/**
 * @author Jens Wilke
 */
@Builder
public record ListenConfig (
  boolean https,
  ConnectionConfig connection,
  SslConfig ssl,
  boolean letsEncrypt,
  List<String> domains,
  List<SniConfig> sni,
  int port) {

}
