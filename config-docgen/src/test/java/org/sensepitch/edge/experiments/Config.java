package org.sensepitch.edge.experiments;

import lombok.Builder;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

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
  @NotBlank
  String stringParam,
  @Size(min = 1)
  List<NestedConfig> listParam,
  @NotNull
  NestedConfig objectParam
) { 

}
