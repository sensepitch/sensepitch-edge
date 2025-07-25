package org.sensepitch.edge.experiments;

import org.sensepitch.edge.experiments.annotation.MyDocumentedAnnotation;
import org.sensepitch.edge.experiments.annotation.MyUndocumentedAnnotation;

public record AnnotatedFieldsConfig(
    @MyDocumentedAnnotation
    @MyUndocumentedAnnotation
    String fieldss
) {
    
}
