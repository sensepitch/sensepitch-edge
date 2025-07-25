package org.sensepitch.edge.experiments;

import java.lang.annotation.Documented;
import java.net.URL;
import java.net.URLClassLoader;

public class AnnotationReflectionService implements AutoCloseable {
    private final URLClassLoader classLoader;

    public AnnotationReflectionService(String jarPath) throws Exception {
        URL[] urls = { new java.io.File(jarPath).toURI().toURL() };
        this.classLoader = new URLClassLoader(urls, ClassLoader.getSystemClassLoader());
    }

    public boolean isDocumented(String annotationFqn) {
        try {
            Class<?> annClass;
            if (annotationFqn.startsWith("java.") || annotationFqn.startsWith("javax.")) {
                annClass = Class.forName(annotationFqn, false, null); // null = bootstrap loader
            } else {
                annClass = Class.forName(annotationFqn, false, classLoader);
            }
            return annClass.isAnnotationPresent(Documented.class);
        } catch (Throwable t) {
            System.err.println("Failed to check if " + annotationFqn + " is documented: " + t.getMessage());
            return false;
        }
    }

    public void close() throws Exception {
        classLoader.close();
    }
}
