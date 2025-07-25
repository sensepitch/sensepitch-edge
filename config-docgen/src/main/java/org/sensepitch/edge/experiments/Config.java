package org.sensepitch.edge.experiments;

import lombok.Builder;

import java.util.List;

/**
 * Configuration
 * 
 * This configuration..
 * 
 * @param stringParam very important
 * @param listParam list of nested configs
 * @param objectParam nested config
 *
 * @author Jens Wilke
 */
@Builder
public record Config(
  String stringParam,
  List<NestedConfig> listParam,
  NestedConfig objectParam
) { 

}
