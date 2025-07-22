package org.sensepitch.edge.experiments;

import org.checkerframework.checker.index.qual.NonNegative;
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
public record NestedConfig(
    boolean booleanParam,
    @NonNegative
    int intParam
) {
}
