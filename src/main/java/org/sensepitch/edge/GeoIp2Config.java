package org.sensepitch.edge;

import lombok.Builder;

/**
 * @author Jens Wilke
 */
@Builder
public record GeoIp2Config(
  String asnDb,
  String countryDb
) { }
