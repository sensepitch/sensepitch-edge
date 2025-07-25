package org.sensepitch.edge.experiments;

import com.github.javaparser.*;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.ast.*;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Name;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.javadoc.JavadocBlockTag;

import java.nio.file.*;
import java.util.Optional;
import java.util.List;
import java.util.stream.Collectors;

public class Main {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("Usage: ConfigDocGenerator <source file> <jar-with-dependencies>");
        }
        Path filePath = Paths.get(args[0]);
        String jarPath = args[1];
        // ensure file exists
        if (!Files.exists(filePath)) {
            throw new IllegalArgumentException("No such file: " + filePath + " files here are: "
                    + Files.list(filePath.getParent()).collect(Collectors.toList()));
        }
        processFile(filePath, jarPath);
    }

    static void processFile(Path filePath, String jarPath) throws Exception {
        try {
            JavaParser parser = new JavaParser();
            parser.getParserConfiguration().setLanguageLevel(LanguageLevel.JAVA_21);
            CompilationUnit cu = parser.parse(filePath).getResult().get();
            cu.findFirst(RecordDeclaration.class).ifPresent(javaRecord -> {
                System.out.println("# " + javaRecord.getNameAsString());
                System.out.println();

                // Description
                Optional<String> description = javaRecord.getJavadoc().map(it -> it.getDescription().toText());
                description.ifPresent(it -> {
                    System.out.println(it);
                    System.out.println();
                });

                // Parameters section
                System.out.println();
                System.out.println("## Parameters:");
                System.out.println();
                for (Parameter param : javaRecord.getParameters()) {
                    String fieldName = param.getNameAsString();
                    Type fieldType = param.getType();
                    Optional<String> customDescription = javaRecord
                            .getJavadoc()
                            .map(javaDoc -> javaDoc.getBlockTags().stream()
                                    .filter(block -> block.getType().equals(JavadocBlockTag.Type.PARAM))
                                    .filter(block -> block.getName().get().equals(fieldName))
                                    .findFirst()
                                    .map(block -> block.getContent().toText()))
                            .orElse(Optional.empty());
                    System.out.println();
                    System.out.println("### " + fieldName + " `" + fieldType + "`");
                    System.out.println();

                    // Annotations
                    try (AnnotationReflectionService annotationService = new AnnotationReflectionService(jarPath)) {
                        param.getAnnotations().stream()
                                .filter(it -> {
                                    String annName = it.getNameAsString();
                                    Optional<String> fqnOpt = cu.getImports().stream()
                                            .map(imp -> imp.getNameAsString())
                                            .filter(imp -> imp.endsWith("." + annName) || imp.equals(annName))
                                            .findFirst();
                                    String fqn = fqnOpt.orElse("java.lang.annotation." + annName);
                                    boolean documented = annotationService.isDocumented(fqn);
                                    return documented;
                                })
                                .forEach(it -> {
                                    System.out.println("- `" + it.toString() + "`");
                                    System.out.println();
                                });
                    } catch (Exception e) {
                        System.err.println("Failed to process annotations: " + e.getMessage());
                    }


                    customDescription.ifPresent(it -> {
                        System.out.println(it);
                        System.out.println();
                    });
                }
            });
        } catch (Exception e) {
            System.err.println("Failed to process file: " + e.getMessage());
        }
    }
}
