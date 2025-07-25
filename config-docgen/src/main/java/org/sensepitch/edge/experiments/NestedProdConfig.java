package org.sensepitch.edge.experiments;

import org.checkerframework.checker.index.qual.NonNegative;

import jakarta.validation.constraints.Min;
import lombok.Builder;

/**
 * Nested config
 * 
 * @param booleanParam boolean parameter
 * @param intParam int parameter
 *
 * @author Jens Wilke
 */
@Builder
public record NestedProdConfig(
    boolean booleanParam,
    @Min(0)
    @NonNegative
    int intParam
) {
}
