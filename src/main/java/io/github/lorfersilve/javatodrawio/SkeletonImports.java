package io.github.lorfersilve.javatodrawio;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import io.github.lorfersilve.javatodrawio.DiagramModelReader.Diagram;
import io.github.lorfersilve.javatodrawio.JavaAnalyzer.SourceType;
import io.github.lorfersilve.javatodrawio.JavaTypeResolver.Status;

import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

final class SkeletonImports {
    private record ImportQuery(String simpleName, int typeArguments) {
    }

    private final Diagram diagram;
    private final Map<ImportQuery, Set<String>> platform = new HashMap<>();

    SkeletonImports(Diagram diagram) {
        this.diagram = diagram;
    }

    void resolve(Map<Path, CompilationUnit> units) {
        Map<String, SourceType> types = sourceTypes(units);
        var resolver = new JavaTypeResolver(types, JavaClasspath.empty());
        Map<String, Set<String>> publicTypes = new HashMap<>();
        for (var entry : types.entrySet()) {
            var source = entry.getValue();
            if (!source.packageName().isEmpty() && publiclyAccessible(source.declaration())) {
                publicTypes.computeIfAbsent(source.declaration().getNameAsString(), ignored -> new TreeSet<>())
                        .add(entry.getKey());
            }
        }
        for (CompilationUnit unit : units.values()) {
            Map<String, String> imported = new HashMap<>();
            Set<String> inferred = new TreeSet<>();
            boolean externalWildcard = unknownWildcardImport(unit, types);
            for (var item : unit.getImports()) {
                if (!item.isAsterisk() && !item.isStatic()) {
                    registerImport(unit.getType(0), imported, item.getName().getIdentifier(), item.getNameAsString());
                }
            }
            for (ClassOrInterfaceType reference : unit.findAll(ClassOrInterfaceType.class)) {
                // A scope component is also a node. Resolve the complete name once.
                if (reference.getParentNode().orElse(null) instanceof ClassOrInterfaceType outer
                        && outer.getScope().orElse(null) == reference) {
                    continue;
                }
                TypeDeclaration<?> owner = enclosingType(reference);
                var source = types.get(owner.getFullyQualifiedName().orElseThrow());
                var resolution = resolver.resolve(reference, source);
                if (resolution.status() == Status.AMBIGUOUS) {
                    throw problem(owner, "Ambiguous type '" + reference + "': " + String.join(", ", resolution.names()));
                }
                if (resolution.status() != Status.MISSING || externalWildcard) {
                    continue;
                }

                ClassOrInterfaceType head = reference;
                while (head.getScope().isPresent()) {
                    head = head.getScope().orElseThrow();
                }
                String name = head.getNameAsString();
                int arity = head.getTypeArguments().map(arguments -> arguments.size()).orElse(0);
                Set<String> candidates = new TreeSet<>(platform.computeIfAbsent(new ImportQuery(name, arity),
                        SkeletonImports::platformCandidates));
                candidates.addAll(publicTypes.getOrDefault(name, Set.of()));
                if (candidates.isEmpty() && head != reference) {
                    continue;
                }
                if (candidates.size() != 1) {
                    String found = candidates.isEmpty() ? "Unresolved type '" + name + "'"
                            : "Ambiguous type '" + name + "' (" + String.join(", ", candidates) + ")";
                    throw problem(owner, found + ". Use a qualified type name or an explicit import.");
                }
                String qualified = candidates.iterator().next();
                registerImport(owner, imported, name, qualified);
                inferred.add(qualified);
            }
            inferred.forEach(unit::addImport);
        }
    }

    static Map<String, SourceType> sourceTypes(Map<Path, CompilationUnit> units) {
        Map<String, SourceType> types = new LinkedHashMap<>();
        for (var entry : units.entrySet()) {
            CompilationUnit unit = entry.getValue();
            for (TypeDeclaration<?> declaration : unit.findAll(TypeDeclaration.class)) {
                types.put(declaration.getFullyQualifiedName().orElseThrow(),
                        new SourceType(entry.getKey(), unit, declaration));
            }
        }
        return types;
    }

    private void registerImport(TypeDeclaration<?> owner, Map<String, String> imported, String name, String qualified) {
        String previous = imported.putIfAbsent(name, qualified);
        if (previous != null && !previous.equals(qualified)) {
            throw problem(owner, "Conflicting imports for '" + name + "': " + previous + ", " + qualified);
        }
    }

    private static boolean unknownWildcardImport(CompilationUnit unit, Map<String, SourceType> types) {
        return unit.getImports().stream().filter(item -> item.isAsterisk()).anyMatch(item -> {
            String prefix = item.getNameAsString();
            boolean jdk = ModuleLayer.boot().modules().stream().anyMatch(module -> module.getPackages().contains(prefix));
            return !jdk && !types.containsKey(prefix)
                    && types.values().stream().noneMatch(type -> type.packageName().equals(prefix));
        });
    }

    private static Set<String> platformCandidates(ImportQuery query) {
        Set<String> result = new TreeSet<>();
        for (Module module : ModuleLayer.boot().modules()) {
            if (!module.getName().startsWith("java.")) {
                continue;
            }
            for (String packageName : module.getPackages()) {
                if (!module.isExported(packageName)) {
                    continue;
                }
                String name = packageName + "." + query.simpleName();
                Class<?> type = Class.forName(module, name);
                if (type != null && Modifier.isPublic(type.getModifiers())
                        && (query.typeArguments() == 0 || query.typeArguments() == type.getTypeParameters().length)) {
                    result.add(name);
                }
            }
        }
        return result;
    }

    private static boolean publiclyAccessible(TypeDeclaration<?> type) {
        for (TypeDeclaration<?> current = type; current != null; current = enclosingType(current)) {
            TypeDeclaration<?> enclosing = enclosingType(current);
            if (!current.isPublic() && (enclosing == null || !JavaAnalyzer.isInterfaceLike(enclosing))) {
                return false;
            }
        }
        return true;
    }

    private static TypeDeclaration<?> enclosingType(Node node) {
        for (Node parent = node.getParentNode().orElse(null); parent != null;
             parent = parent.getParentNode().orElse(null)) {
            if (parent instanceof TypeDeclaration<?> type) {
                return type;
            }
        }
        return null;
    }

    private IllegalArgumentException problem(TypeDeclaration<?> owner, String message) {
        String type = owner.getFullyQualifiedName().orElseThrow();
        return new IllegalArgumentException(diagram.typeLocations().get(type) + ": " + message);
    }
}
