package io.github.lorfersilve.javatodrawio;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.AnnotationMemberDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.CompactConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.nodeTypes.NodeWithTypeParameters;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import io.github.lorfersilve.javatodrawio.JavaAnalyzer.SourceType;
import io.github.lorfersilve.javatodrawio.JavaTypeResolver.Resolution;
import io.github.lorfersilve.javatodrawio.UmlModel.Kind;
import io.github.lorfersilve.javatodrawio.UmlModel.Relationship;
import io.github.lorfersilve.javatodrawio.UmlModel.RelationshipKind;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static io.github.lorfersilve.javatodrawio.UmlModel.RelationshipKind.ASSOCIATION;
import static io.github.lorfersilve.javatodrawio.UmlModel.RelationshipKind.DEPENDENCY;
import static io.github.lorfersilve.javatodrawio.UmlModel.RelationshipKind.GENERALIZATION;
import static io.github.lorfersilve.javatodrawio.UmlModel.RelationshipKind.REALIZATION;

final class RelationshipAnalyzer {
    record Result(List<Relationship> relationships, List<String> warnings) {
    }

    private record Pair(String source, String target) {
    }

    private final Map<String, SourceType> sources;
    private final JavaTypeResolver resolver;
    private final Map<Pair, EnumSet<RelationshipKind>> edges = new HashMap<>();
    private final Set<String> warnings = new LinkedHashSet<>();

    RelationshipAnalyzer(Map<String, SourceType> sources, JavaClasspath classpath) {
        this.sources = sources;
        this.resolver = new JavaTypeResolver(sources, classpath);
    }

    Result analyze() {
        sources.forEach(this::collect);
        List<Relationship> relationships = new ArrayList<>();
        edges.forEach((pair, kinds) -> {
            if (kinds.size() > 1) {
                kinds.remove(DEPENDENCY);
            }
            for (RelationshipKind kind : kinds) {
                relationships.add(new Relationship(pair.source(), pair.target(), kind));
            }
        });
        relationships.sort(Comparator.comparing(Relationship::source).thenComparing(Relationship::target)
                .thenComparing(Relationship::kind));
        return new Result(List.copyOf(relationships), List.copyOf(warnings));
    }

    private void collect(String id, SourceType source) {
        TypeDeclaration<?> type = source.declaration();
        JavaTypeResolver.extendedTypes(type).forEach(parent -> references(id, source, parent, GENERALIZATION));
        JavaTypeResolver.implementedTypes(type).forEach(parent -> references(id, source, parent, REALIZATION));
        if (type instanceof ClassOrInterfaceDeclaration declaration) {
            declaration.getPermittedTypes().forEach(permitted -> resolve(source, permitted));
        }
        bounds(id, source, type);
        if (type instanceof RecordDeclaration record) {
            record.getParameters().forEach(component -> references(id, source, component.getType(), ASSOCIATION));
        }
        for (BodyDeclaration<?> member : type.getMembers()) {
            if (member instanceof FieldDeclaration field) {
                field.getVariables().forEach(variable -> references(id, source, variable.getType(), ASSOCIATION));
            } else if (member instanceof CallableDeclaration<?> callable) {
                bounds(id, source, callable);
                callable.getParameters().forEach(parameter -> references(id, source, parameter.getType(), DEPENDENCY));
                callable.getThrownExceptions().forEach(thrown -> references(id, source, thrown, DEPENDENCY));
                if (callable instanceof MethodDeclaration method) {
                    references(id, source, method.getType(), DEPENDENCY);
                }
            } else if (member instanceof CompactConstructorDeclaration constructor) {
                constructor.getThrownExceptions().forEach(thrown -> references(id, source, thrown, DEPENDENCY));
            } else if (member instanceof AnnotationMemberDeclaration element) {
                references(id, source, element.getType(), DEPENDENCY);
            }
        }
    }

    private void bounds(String id, SourceType source, Node declaration) {
        if (declaration instanceof NodeWithTypeParameters<?> generic) {
            generic.getTypeParameters().forEach(parameter ->
                    parameter.getTypeBound().forEach(bound -> references(id, source, bound, DEPENDENCY)));
        }
    }

    private void references(String id, SourceType source, Type type, RelationshipKind kind) {
        if (type instanceof ClassOrInterfaceType reference) {
            resolve(source, reference).ifPresent(target -> addEdge(id, source, reference, target, kind));
            RelationshipKind argumentKind = isInheritance(kind) ? DEPENDENCY : kind;
            // A qualified type can have generic arguments at each level, as in Outer<A>.Inner<B>.
            for (ClassOrInterfaceType part = reference; part != null; part = part.getScope().orElse(null)) {
                part.getTypeArguments().ifPresent(arguments ->
                        arguments.forEach(argument -> references(id, source, argument, argumentKind)));
            }
        } else {
            for (Node child : type.getChildNodes()) {
                if (child instanceof Type nested) {
                    references(id, source, nested, kind);
                }
            }
        }
    }

    private Optional<String> resolve(SourceType source, ClassOrInterfaceType reference) {
        Resolution resolution = resolver.resolve(reference, source);
        String name = reference.getNameWithScope();
        switch (resolution.status()) {
            case FOUND -> {
                return Optional.of(resolution.qualifiedName());
            }
            case MISSING -> warn(source, reference, "cannot resolve type '" + name + "'.");
            case MISSING_IMPORT -> warn(source, reference, "cannot resolve type '" + name
                    + "' from import " + String.join(", ", resolution.names()) + ".");
            case AMBIGUOUS -> warn(source, reference, "ambiguous type '" + name
                    + "' (" + String.join(", ", resolution.names()) + ").");
            case TYPE_PARAMETER -> {
                // Its declared bounds are considered where the parameter is declared.
            }
        }
        return Optional.empty();
    }

    private void addEdge(String id, SourceType source, ClassOrInterfaceType reference, String target,
                         RelationshipKind kind) {
        SourceType targetType = sources.get(target);
        if (targetType == null || (id.equals(target) && kind == DEPENDENCY)) {
            return;
        }
        if (isInheritance(kind) && (id.equals(target) || !validInheritance(source, targetType, kind))) {
            warn(source, reference, "invalid " + (kind == GENERALIZATION ? "supertype" : "implemented type")
                    + " '" + target + "'.");
            return;
        }
        edges.computeIfAbsent(new Pair(id, target), pair -> EnumSet.noneOf(RelationshipKind.class)).add(kind);
    }

    private static boolean isInheritance(RelationshipKind kind) {
        return kind == GENERALIZATION || kind == REALIZATION;
    }

    private static boolean validInheritance(SourceType source, SourceType target, RelationshipKind kind) {
        if (kind == REALIZATION || JavaAnalyzer.isInterfaceLike(source.declaration())) {
            return JavaAnalyzer.isInterfaceLike(target.declaration());
        }
        return JavaAnalyzer.kindOf(target.declaration()) == Kind.CLASS;
    }

    private void warn(SourceType source, ClassOrInterfaceType reference, String message) {
        var begin = reference.getBegin().orElseThrow();
        warnings.add(source.file() + ":" + begin.line + ":" + begin.column + ": " + message);
    }
}
