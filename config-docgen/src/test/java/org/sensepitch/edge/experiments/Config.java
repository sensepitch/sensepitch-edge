package org.sensepitch.edge.experiments;

import lombok.Builder;


import java.util.List;

/**
 * Configuration
 * 
 * This configuration..
 * 
 * @param stringParam String parameter, very important, required
 * @param listParam List<NestedConfig> parameter, optional
 * @param objectParam NestedConfig parameter, optional. Not needed when list is used
 *
 * @author Jens Wilke
 */
@Builder
public record Config(
  @org.checkerframework.checker.nonempty.qual.NonEmpty
  String stringParam,
  List<NestedConfig> listParam,
  NestedConfig objectParam
) { 

}
