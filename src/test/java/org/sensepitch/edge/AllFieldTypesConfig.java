package org.sensepitch.edge;

import lombok.Builder;

import java.util.List;

/**
 * @author Jens Wilke
 */
@Builder
public record AllFieldTypesConfig(
  int number,
  boolean flag,
  String text,
  List<String> texts
) {
}
