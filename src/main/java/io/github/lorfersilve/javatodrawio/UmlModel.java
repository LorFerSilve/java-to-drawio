package io.github.lorfersilve.javatodrawio;

import java.util.List;
import java.util.Optional;
import java.util.Set;

record UmlModel(List<UmlType> types, List<Relationship> relationships) {
    enum Kind {
        CLASS, INTERFACE, ENUM, RECORD, ANNOTATION
    }

    enum Visibility {
        PUBLIC("+"), PROTECTED("#"), PACKAGE("~"), PRIVATE("-");

        private final String marker;

        Visibility(String marker) {
            this.marker = marker;
        }

        String marker() {
            return marker;
        }
    }

    enum Modifier {
        STATIC, ABSTRACT, SEALED, NON_SEALED, DEFAULT, FINAL
    }

    record Declarations(List<String> imports, List<String> superclasses, List<String> interfaces,
                        List<String> permits, List<String> enumConstants) {
    }

    record UmlType(String qualifiedName, String packageName, Kind kind, Visibility visibility,
                   Set<Modifier> modifiers, List<String> typeParameters, List<Field> fields,
                   List<Operation> operations, Declarations declarations) {
        String displayName() {
            return packageName.isEmpty() ? qualifiedName : qualifiedName.substring(packageName.length() + 1);
        }
    }

    record Field(String name, String type, Visibility visibility, boolean isStatic) {
    }

    record Parameter(String name, String type) {
    }

    record Operation(String name, Optional<String> returnType, Visibility visibility, Set<Modifier> modifiers,
                     List<String> typeParameters, List<Parameter> parameters, List<String> thrownTypes) {
    }

    enum RelationshipKind {
        GENERALIZATION, REALIZATION, ASSOCIATION, DEPENDENCY
    }

    record Relationship(String source, String target, RelationshipKind kind) {
    }
}
