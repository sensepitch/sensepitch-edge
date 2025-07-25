package org.sensepitch.edge.experiments;

import java.util.List;

import org.sensepitch.edge.experiments.annotation.MyDocumentedAnnotation;
import org.sensepitch.edge.experiments.annotation.MyUndocumentedAnnotation;

import jakarta.validation.constraints.Size;

public record AnnotatedFieldsConfig(
    @MyDocumentedAnnotation
    @MyUndocumentedAnnotation
    String fieldss,

    @Size(min = 1, max = 10)
    @Deprecated
    List<String> fieldss2
) {
    
}
