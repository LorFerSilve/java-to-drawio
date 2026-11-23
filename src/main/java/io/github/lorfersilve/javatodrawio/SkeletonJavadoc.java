package io.github.lorfersilve.javatodrawio;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.AnnotationMemberDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.CompactConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.nodeTypes.NodeWithTypeParameters;
import com.github.javaparser.ast.type.ReferenceType;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

final class SkeletonJavadoc {
    static void addTo(CompilationUnit unit, String author) {
        String authorText = escapeAuthor(author);
        for (TypeDeclaration<?> type : unit.findAll(TypeDeclaration.class)) {
            documentType(type, authorText);
            for (var member : type.getMembers()) {
                if (member instanceof CallableDeclaration<?> callable) {
                    documentCallable(callable);
                } else if (member instanceof CompactConstructorDeclaration constructor
                        && type instanceof RecordDeclaration record) {
                    documentCompactConstructor(constructor, record);
                } else if (member instanceof AnnotationMemberDeclaration element) {
                    element.setJavadocComment(comment("TODO Describe what this annotation element represents.",
                            List.of("@return TODO Describe the annotation value.")));
                } else if (member instanceof FieldDeclaration field) {
                    String names = field.getVariables().stream().map(variable -> code(variable.getNameAsString()))
                            .collect(Collectors.joining(", "));
                    field.setJavadocComment("TODO Describe the purpose of " + names + ".");
                }
            }
        }
    }

    private static void documentType(TypeDeclaration<?> type, String authorText) {
        List<String> tags = new ArrayList<>();
        if (type instanceof NodeWithTypeParameters<?> generic) {
            addTypeParameters(tags, generic);
        }
        if (type instanceof RecordDeclaration record) {
            for (Parameter component : record.getParameters()) {
                tags.add("@param " + component.getNameAsString() + " TODO Describe the record component.");
            }
        }
        if (!authorText.isEmpty()) {
            tags.add("@author " + authorText);
        }
        type.setJavadocComment(comment("TODO Describe the role of " + code(type.getNameAsString()) + ".", tags));

        if (type instanceof EnumDeclaration enumeration) {
            for (var constant : enumeration.getEntries()) {
                constant.setJavadocComment("TODO Describe what " + code(constant.getNameAsString()) + " represents.");
            }
        }
    }

    private static void documentCallable(CallableDeclaration<?> callable) {
        List<String> tags = parameterTags(callable, callable.getParameters());
        String summary = constructorSummary(callable.getNameAsString());
        if (callable instanceof MethodDeclaration method) {
            summary = "TODO Describe what " + code(method.getNameAsString()) + " does.";
            if (!method.getType().isVoidType()) {
                tags.add("@return TODO Describe the returned value.");
            }
        }
        addExceptions(tags, callable.getThrownExceptions());
        callable.setJavadocComment(comment(summary, tags));
    }

    private static void documentCompactConstructor(CompactConstructorDeclaration constructor, RecordDeclaration record) {
        List<String> tags = parameterTags(constructor, record.getParameters());
        addExceptions(tags, constructor.getThrownExceptions());
        constructor.setJavadocComment(comment(constructorSummary(constructor.getNameAsString()), tags));
    }

    private static List<String> parameterTags(NodeWithTypeParameters<?> callable, List<Parameter> parameters) {
        List<String> tags = new ArrayList<>();
        addTypeParameters(tags, callable);
        for (Parameter parameter : parameters) {
            tags.add("@param " + parameter.getNameAsString() + " TODO Describe this argument.");
        }
        return tags;
    }

    private static void addTypeParameters(List<String> tags, NodeWithTypeParameters<?> generic) {
        for (var parameter : generic.getTypeParameters()) {
            tags.add("@param <" + parameter.getNameAsString() + "> TODO Describe this type parameter.");
        }
    }

    private static void addExceptions(List<String> tags, List<ReferenceType> exceptions) {
        for (ReferenceType exception : exceptions) {
            tags.add("@throws " + exception.asString() + " TODO Describe when this exception occurs.");
        }
    }

    private static String comment(String summary, List<String> tags) {
        return tags.isEmpty() ? summary : summary + "\n\n" + String.join("\n", tags);
    }

    private static String code(String name) {
        return "{@code " + name + "}";
    }

    private static String constructorSummary(String name) {
        return "TODO Describe how this constructor initializes " + code(name) + ".";
    }

    private static String escapeAuthor(String author) {
        // Keep author names as text in both Java source and the generated HTML.
        return author.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("{", "&#123;").replace("}", "&#125;").replace("\\", "&#92;").replace("*/", "*&#47;");
    }

    private SkeletonJavadoc() {
    }
}
