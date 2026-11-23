package io.github.lorfersilve.javatodrawio;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.Modifier.Keyword;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.nodeTypes.NodeWithTypeParameters;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.TypeParameter;
import io.github.lorfersilve.javatodrawio.DiagramModelReader.Diagram;
import io.github.lorfersilve.javatodrawio.JavaAnalyzer.SourceType;
import io.github.lorfersilve.javatodrawio.JavaTypeResolver.Status;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

final class SkeletonOverrides {
    private record Addition(TypeDeclaration<?> owner, MethodDeclaration method) {
    }

    private final JavaParser parser;
    private final Diagram diagram;
    private final Map<String, SourceType> sources;
    private final JavaTypeResolver resolver;

    SkeletonOverrides(JavaParser parser, Diagram diagram, Map<String, SourceType> sources) {
        this.parser = parser;
        this.diagram = diagram;
        this.sources = sources;
        this.resolver = new JavaTypeResolver(sources, JavaClasspath.empty());
    }

    void addMissingMethods(List<String> warnings) {
        List<Addition> additions = new ArrayList<>();
        for (SourceType source : sources.values()) {
            var owner = source.declaration();
            if (JavaAnalyzer.isInterfaceLike(owner) || owner.hasModifier(Keyword.ABSTRACT)) {
                continue;
            }
            Map<String, MethodDeclaration> methods = new LinkedHashMap<>();
            collectMethods(source, source, Map.of(), methods, new HashSet<>());
            for (var entry : methods.entrySet()) {
                MethodDeclaration method = entry.getValue();
                if (method.getBody().isPresent() || method.isNative() || implicitMethod(owner, entry.getKey())) {
                    continue;
                }
                method.removeModifier(Keyword.ABSTRACT);
                method.setBody(new BlockStmt().addStatement(parser.parseStatement(JavaSkeletons.TODO)
                        .getResult().orElseThrow()));
                additions.add(new Addition(owner, method));
                String location = diagram.typeLocations().get(owner.getFullyQualifiedName().orElseThrow());
                warnings.add(location + ": Added inherited operation stub " + entry.getKey());
            }
        }
        additions.forEach(addition -> addition.owner().addMember(addition.method()));
    }

    private void collectMethods(SourceType root, SourceType source, Map<String, Type> bindings,
                                Map<String, MethodDeclaration> methods, Set<String> visited) {
        if (!visited.add(source.declaration().getFullyQualifiedName().orElseThrow())) {
            return;
        }
        for (MethodDeclaration method : source.declaration().getMethods()) {
            boolean accessible = method.isPublic() || method.isProtected() || JavaAnalyzer.isInterfaceLike(source.declaration())
                    || root.packageName().equals(source.packageName());
            if (method.isStatic() || method.isPrivate() || !accessible) {
                continue;
            }
            MethodDeclaration inherited = substitute(method, source, bindings);
            String signature = signature(root, inherited);
            methods.putIfAbsent(signature, inherited);
        }
        var parents = Stream.concat(JavaTypeResolver.extendedTypes(source.declaration()).stream(),
                JavaTypeResolver.implementedTypes(source.declaration()).stream()).toList();
        for (ClassOrInterfaceType parent : parents) {
            var resolution = resolver.resolve(parent, source);
            if (resolution.status() == Status.FOUND && sources.containsKey(resolution.qualifiedName())) {
                SourceType ancestor = sources.get(resolution.qualifiedName());
                collectMethods(root, ancestor, bind(parent, source, ancestor, bindings), methods, visited);
            }
        }
    }

    private static boolean implicitMethod(TypeDeclaration<?> owner, String signature) {
        if (Set.of("equals(java.lang.Object)", "toString()", "hashCode()").contains(signature)) {
            return true;
        }
        if (owner.isRecordDeclaration()) {
            return owner.asRecordDeclaration().getParameters().stream()
                    .anyMatch(component -> signature.equals(component.getNameAsString() + "()"));
        }
        return owner.isEnumDeclaration() && Set.of("name()", "ordinal()", "getDeclaringClass()").contains(signature);
    }

    private Map<String, Type> bind(ClassOrInterfaceType reference, SourceType source, SourceType ancestor,
                                   Map<String, Type> inherited) {
        List<TypeParameter> variables = typeParameters(ancestor.declaration());
        List<Type> arguments = reference.getTypeArguments().map(List::copyOf).orElseGet(List::of);
        Map<String, Type> result = new HashMap<>();
        for (int i = 0; i < variables.size(); i++) {
            TypeParameter variable = variables.get(i);
            Type argument = i < arguments.size() ? type(arguments.get(i), source, inherited, Set.of())
                    : parentBound(variable, ancestor);
            result.put(variable.getNameAsString(), argument);
        }
        return result;
    }

    private Type parentBound(TypeParameter variable, SourceType source) {
        if (variable.getTypeBound().isEmpty()) {
            return parser.parseType("java.lang.Object").getResult().orElseThrow();
        }
        ClassOrInterfaceType bound = type(variable.getTypeBound().get(0), source, Map.of(), Set.of())
                .asClassOrInterfaceType();
        bound.removeTypeArguments();
        return bound;
    }

    private MethodDeclaration substitute(MethodDeclaration method, SourceType source, Map<String, Type> bindings) {
        Set<String> ownVariables = method.getTypeParameters().stream().map(TypeParameter::getNameAsString)
                .collect(Collectors.toSet());
        MethodDeclaration copy = method.clone();
        copy.setType(type(method.getType(), source, bindings, ownVariables));
        for (int i = 0; i < copy.getParameters().size(); i++) {
            copy.getParameter(i).setType(type(method.getParameter(i).getType(), source, bindings, ownVariables));
        }
        for (int i = 0; i < copy.getTypeParameters().size(); i++) {
            var bounds = method.getTypeParameter(i).getTypeBound().stream()
                    .map(bound -> type(bound, source, bindings, ownVariables).asClassOrInterfaceType()).toList();
            copy.getTypeParameter(i).setTypeBound(new NodeList<>(bounds));
        }
        var exceptions = method.getThrownExceptions().stream()
                .map(ref -> type(ref, source, bindings, ownVariables).asReferenceType()).toList();
        copy.setThrownExceptions(new NodeList<>(exceptions));
        if (JavaAnalyzer.isInterfaceLike(source.declaration())) {
            copy.setPublic(true);
        }
        return copy;
    }

    private Type type(Type original, SourceType source, Map<String, Type> bindings, Set<String> shadowed) {
        Type copy = original.clone();
        if (copy.isArrayType()) {
            copy.asArrayType().setComponentType(type(original.asArrayType().getComponentType(), source, bindings, shadowed));
        } else if (copy.isWildcardType()) {
            var wildcard = original.asWildcardType();
            wildcard.getExtendedType().ifPresent(bound -> copy.asWildcardType()
                    .setExtendedType(type(bound, source, bindings, shadowed).asReferenceType()));
            wildcard.getSuperType().ifPresent(bound -> copy.asWildcardType()
                    .setSuperType(type(bound, source, bindings, shadowed).asReferenceType()));
        } else if (copy.isClassOrInterfaceType()) {
            return reference(original.asClassOrInterfaceType(), source, bindings, shadowed);
        }
        return copy;
    }

    private Type reference(ClassOrInterfaceType original, SourceType source, Map<String, Type> bindings,
                           Set<String> shadowed) {
        String name = original.getNameWithScope();
        if (!shadowed.contains(name) && bindings.containsKey(name)) {
            return bindings.get(name).clone();
        }
        var resolution = resolver.resolve(original, source);
        ClassOrInterfaceType copy = original.clone();
        if (resolution.status() == Status.FOUND && !scopeHasTypeArguments(original)) {
            copy = parser.parseClassOrInterfaceType(resolution.qualifiedName()).getResult().orElseThrow();
        } else if (resolution.status() == Status.MISSING_IMPORT && original.getScope().isEmpty()
                && resolution.names().size() == 1) {
            copy = parser.parseClassOrInterfaceType(resolution.qualifiedName()).getResult().orElseThrow();
        } else if (original.getScope().isPresent()) {
            copy.setScope(reference(original.getScope().orElseThrow(), source, bindings, shadowed)
                    .asClassOrInterfaceType());
        }
        if (original.getTypeArguments().isPresent()) {
            List<Type> arguments = original.getTypeArguments().orElseThrow().stream()
                    .map(argument -> type(argument, source, bindings, shadowed)).toList();
            copy.setTypeArguments(new NodeList<>(arguments));
        }
        return copy;
    }

    private static boolean scopeHasTypeArguments(ClassOrInterfaceType type) {
        return type.getScope().map(scope -> scope.getTypeArguments().isPresent() || scopeHasTypeArguments(scope))
                .orElse(false);
    }

    private String signature(SourceType root, MethodDeclaration method) {
        Map<String, String> erasures = new HashMap<>();
        for (TypeParameter parameter : typeParameters(root.declaration())) {
            erasures.put(parameter.getNameAsString(), erase(parentBound(parameter, root), erasures));
        }
        for (TypeParameter parameter : method.getTypeParameters()) {
            erasures.put(parameter.getNameAsString(), parameter.getTypeBound().isEmpty() ? "java.lang.Object"
                    : erase(parameter.getTypeBound().get(0), erasures));
        }
        return method.getNameAsString() + method.getParameters().stream()
                .map(parameter -> erase(parameter.getType(), erasures) + (parameter.isVarArgs() ? "[]" : ""))
                .collect(Collectors.joining(", ", "(", ")"));
    }

    private static String erase(Type type, Map<String, String> erasures) {
        if (type.isArrayType()) {
            return erase(type.asArrayType().getComponentType(), erasures) + "[]";
        }
        if (type.isClassOrInterfaceType()) {
            String name = type.asClassOrInterfaceType().getNameWithScope();
            return erasures.getOrDefault(name, name);
        }
        return type.asString();
    }

    private static List<TypeParameter> typeParameters(TypeDeclaration<?> owner) {
        return owner instanceof NodeWithTypeParameters<?> generic ? generic.getTypeParameters() : List.of();
    }
}
