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
import java.util.stream.Collectors;

public class ConfigDocGenerator {
    public static void main(String[] args) throws Exception {
        Path srcDir = Paths.get(args[0]);
        Files.walk(srcDir)
                .filter(p -> p.toString().endsWith(".java"))
                .forEach(ConfigDocGenerator::processFile);
    }

    static void processFile(Path filePath) {
        try {
            JavaParser parser = new JavaParser();
            parser.getParserConfiguration().setLanguageLevel(LanguageLevel.JAVA_21);
            CompilationUnit cu = parser.parse(filePath).getResult().get();
            cu.findAll(RecordDeclaration.class).forEach(javaRecord -> {
                if (!javaRecord.getNameAsString().endsWith("Config")) {
                    return;
                }
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
                    customDescription.ifPresent(it -> {
                        System.out.println(it);
                        System.out.println();
                    });
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

