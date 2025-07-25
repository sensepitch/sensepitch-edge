package org.sensepitch.edge.experiments;

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
    int intParam
) {
}
