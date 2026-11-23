package org.sensepitch.edge;

import java.util.List;
import java.util.Map;
import lombok.Builder;

/// A configuration record with every field type the generic loading supports, see `ConfigTest`.
/// Rejects a negative `number`, like a component validating its record.
///
/// @author Jens Wilke
@Builder(toBuilder = true)
public record AllFieldTypesConfig(
    int number,
    long longNumber,
    boolean flag,
    String text,
    List<String> texts,
    List<AllFieldTypesConfig> list,
    Map<String, TestWithKeyConfig> map,
    Map<String, String> labels) {

  public AllFieldTypesConfig {
    if (number < 0) {
      throw new IllegalArgumentException("number must be >= 0, was: " + number);
    }
  }
}
