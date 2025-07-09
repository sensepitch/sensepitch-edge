package org.sensepitch.edge;

import lombok.Builder;

import java.util.List;

/**
 * @author Jens Wilke
 */
@Builder
public record NestedTestConfig(
  boolean enable,
  AllFieldTypesConfig all,
  List<AllFieldTypesConfig> list) {
}
