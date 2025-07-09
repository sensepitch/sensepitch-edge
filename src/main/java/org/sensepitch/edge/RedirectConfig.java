package org.sensepitch.edge;

import lombok.Builder;

import java.util.List;

/**
 * @author Jens Wilke
 */
@Builder
public record RedirectConfig(
  String defaultTarget,
  List<String> passDomains
) { }
