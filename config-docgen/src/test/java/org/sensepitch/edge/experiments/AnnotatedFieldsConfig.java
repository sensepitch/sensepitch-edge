package org.sensepitch.edge.experiments;

public record AnnotatedFieldsConfig(
    @MyDocumentedAnnotation
    @MyUndocumentedAnnotation
    String fieldss
) {
    
}
