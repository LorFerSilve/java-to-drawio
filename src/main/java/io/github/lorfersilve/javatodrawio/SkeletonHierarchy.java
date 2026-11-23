package io.github.lorfersilve.javatodrawio;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import io.github.lorfersilve.javatodrawio.DiagramModelReader.Diagram;
import io.github.lorfersilve.javatodrawio.UmlModel.Kind;
import io.github.lorfersilve.javatodrawio.UmlModel.Modifier;
import io.github.lorfersilve.javatodrawio.UmlModel.Relationship;
import io.github.lorfersilve.javatodrawio.UmlModel.RelationshipKind;
import io.github.lorfersilve.javatodrawio.UmlModel.UmlType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class SkeletonHierarchy {
    record Parents(List<ClassOrInterfaceType> extended, List<ClassOrInterfaceType> implemented,
                   List<ClassOrInterfaceType> permitted) {
    }

    private final Diagram diagram;
    private final Map<String, UmlType> types;
    private final Map<String, Parents> parents = new LinkedHashMap<>();
    private final Map<String, Set<UmlType>> children = new LinkedHashMap<>();
    private final Map<String, UmlType> classParents = new LinkedHashMap<>();

    SkeletonHierarchy(Diagram diagram, Map<String, UmlType> types, JavaParser parser) {
        this.diagram = diagram;
        this.types = types;
        for (UmlType type : types.values()) {
            var declarations = type.declarations();
            parents.put(type.qualifiedName(), new Parents(parse(type, declarations.superclasses(), parser),
                    parse(type, declarations.interfaces(), parser), parse(type, declarations.permits(), parser)));
        }
        for (Relationship edge : diagram.model().relationships()) {
            merge(edge, parser);
        }
        for (UmlType type : types.values()) {
            Parents clauses = parents.get(type.qualifiedName());
            if (type.kind() == Kind.CLASS && clauses.extended().size() > 1) {
                throw problem(type, "A class can extend only one superclass: " + clauses.extended());
            }
            for (ClassOrInterfaceType reference : clauses.extended()) {
                recordParent(type, find(type, reference), type.kind() == Kind.CLASS);
            }
            for (ClassOrInterfaceType reference : clauses.implemented()) {
                recordParent(type, find(type, reference), false);
            }
        }
        Set<String> visited = new HashSet<>();
        for (UmlType type : types.values()) {
            checkAcyclic(type, visited, new LinkedHashSet<>());
            Parents clauses = parents.get(type.qualifiedName());
            if (type.modifiers().contains(Modifier.SEALED) && clauses.permitted().isEmpty()) {
                for (UmlType child : children.getOrDefault(type.qualifiedName(), Set.of())) {
                    clauses.permitted().add(parser.parseClassOrInterfaceType(reference(type, child))
                            .getResult().orElseThrow());
                }
                if (clauses.permitted().isEmpty()) {
                    throw problem(type, "List the permitted types or connect the permitted subclasses");
                }
            }
        }
    }

    Parents parents(UmlType type) {
        return parents.get(type.qualifiedName());
    }

    UmlType classParent(UmlType type) {
        return classParents.get(type.qualifiedName());
    }

    boolean hasChildren(UmlType type) {
        return !children.getOrDefault(type.qualifiedName(), Set.of()).isEmpty();
    }

    boolean inheritsSealedType(UmlType child) {
        for (var entry : children.entrySet()) {
            if (entry.getValue().contains(child) && types.get(entry.getKey()).modifiers().contains(Modifier.SEALED)) {
                return true;
            }
        }
        return false;
    }

    private void merge(Relationship edge, JavaParser parser) {
        UmlType child = types.get(edge.source());
        UmlType parent = types.get(edge.target());
        Parents clauses = parents.get(child.qualifiedName());
        String location = diagram.edgeLocations().get(edge);
        boolean implementsInterface = edge.kind() == RelationshipKind.REALIZATION;
        boolean valid = implementsInterface
                ? parent.kind() == Kind.INTERFACE && Set.of(Kind.CLASS, Kind.RECORD, Kind.ENUM).contains(child.kind())
                : parent.kind() == Kind.CLASS && child.kind() == Kind.CLASS
                    || parent.kind() == Kind.INTERFACE && child.kind() == Kind.INTERFACE;
        if (!valid) {
            throw new IllegalArgumentException(location + ": Incompatible " + (implementsInterface ? "implements"
                    : "extends") + " relationship between " + child.qualifiedName() + " and " + parent.qualifiedName());
        }
        if (parent.modifiers().contains(Modifier.FINAL)) {
            throw new IllegalArgumentException(location + ": Cannot extend final class " + parent.qualifiedName());
        }
        if (!child.packageName().isEmpty() && parent.packageName().isEmpty()) {
            throw new IllegalArgumentException(location + ": A named package cannot inherit from the unnamed package");
        }
        List<ClassOrInterfaceType> clause = implementsInterface ? clauses.implemented() : clauses.extended();
        boolean represented = clause.stream().anyMatch(item -> {
            UmlType resolved = find(child, item);
            return resolved == null ? item.getNameAsString().equals(UmlLabels.simpleName(parent.qualifiedName()))
                    && item.getScope().isEmpty() : resolved.equals(parent);
        });
        if (!represented) {
            clause.addAll(parse(child, List.of(reference(child, parent)), parser));
        }
        recordParent(child, parent, !implementsInterface && child.kind() == Kind.CLASS);
    }

    private void recordParent(UmlType child, UmlType parent, boolean superclass) {
        if (parent != null) {
            children.computeIfAbsent(parent.qualifiedName(), ignored -> new LinkedHashSet<>()).add(child);
            if (superclass) {
                classParents.put(child.qualifiedName(), parent);
            }
        }
    }

    private UmlType find(UmlType owner, ClassOrInterfaceType reference) {
        String name = reference.getNameWithScope();
        for (String scope = owner.qualifiedName(); scope.length() > owner.packageName().length();) {
            UmlType candidate = types.get(scope + "." + name);
            if (candidate != null) {
                return candidate;
            }
            int dot = scope.lastIndexOf('.');
            scope = dot < 0 ? "" : scope.substring(0, dot);
        }
        String lead = name.contains(".") ? name.substring(0, name.indexOf('.')) : name;
        for (String text : owner.declarations().imports()) {
            String imported = text.strip().replaceFirst("^import\\s+(?:static\\s+)?", "").replaceFirst(";\\s*$", "");
            if (imported.endsWith("." + lead)) {
                return types.get(imported + name.substring(lead.length()));
            }
            if (imported.endsWith(".*")) {
                UmlType candidate = types.get(imported.substring(0, imported.length() - 1) + name);
                if (candidate != null) {
                    return candidate;
                }
            }
        }
        UmlType samePackage = types.get(owner.packageName().isEmpty() ? name : owner.packageName() + "." + name);
        return samePackage == null ? types.get(name) : samePackage;
    }

    private static String reference(UmlType child, UmlType parent) {
        return child.packageName().equals(parent.packageName()) ? parent.displayName() : parent.qualifiedName();
    }

    private void checkAcyclic(UmlType type, Set<String> visited, Set<String> chain) {
        String name = type.qualifiedName();
        if (chain.contains(name)) {
            throw problem(type, "Cyclic inheritance: " + String.join(" -> ", chain) + " -> " + name);
        }
        if (!visited.add(name)) {
            return;
        }
        chain.add(name);
        for (UmlType child : children.getOrDefault(name, Set.of())) {
            checkAcyclic(child, visited, chain);
        }
        chain.remove(name);
    }

    private List<ClassOrInterfaceType> parse(UmlType type, List<String> names, JavaParser parser) {
        List<ClassOrInterfaceType> parsed = new ArrayList<>();
        for (String name : new LinkedHashSet<>(names)) {
            var result = parser.parseClassOrInterfaceType(name);
            if (!result.isSuccessful()) {
                throw problem(type, "Invalid type in inheritance clause '" + name + "'");
            }
            parsed.add(result.getResult().orElseThrow());
        }
        return parsed;
    }

    private IllegalArgumentException problem(UmlType type, String message) {
        return new IllegalArgumentException(diagram.typeLocations().get(type.qualifiedName()) + ": " + message);
    }
}
