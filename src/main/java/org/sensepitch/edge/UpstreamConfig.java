package org.sensepitch.edge;

import lombok.Builder;

/**
 *
 * @param host match for the requested host name
 * @param target target host with optional port number. Names are supported, however the standard
 *  *             Java DNS resolver is used
 *
 * @author Jens Wilke
 */
@Builder
public record UpstreamConfig (
  String host,
  String target) { }
