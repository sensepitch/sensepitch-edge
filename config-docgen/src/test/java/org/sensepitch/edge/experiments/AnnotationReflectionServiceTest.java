package org.sensepitch.edge.experiments;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;

public class AnnotationReflectionServiceTest {
    @Test
    @SneakyThrows
    void test() {
        System.out.println(System.getProperty("java.version"));
        System.out.println(System.getProperty("java.home"));
        System.out.println(Class.forName("java.lang.Deprecated", false, null).getName());

        try (AnnotationReflectionService service = new AnnotationReflectionService("./src/main/resources/config-docgen-with-dependencies.jar")) {
            boolean b  = service.isDocumented("java.lang.Deprecated");
            assertTrue(b);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    @Test
    void test2() {
        try (AnnotationReflectionService service = new AnnotationReflectionService("./src/main/resources/config-docgen-with-dependencies.jar")) {
            boolean b  = service.isDocumented("org.sensepitch.edge.experiments.annotation.MyDocumentedAnnotation");
            assertTrue(b);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
