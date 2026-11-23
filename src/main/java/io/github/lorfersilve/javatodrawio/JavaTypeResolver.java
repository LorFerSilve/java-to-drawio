package io.github.lorfersilve.javatodrawio;

import com.github.javaparser.ast.AccessSpecifier;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.nodeTypes.NodeWithExtends;
import com.github.javaparser.ast.nodeTypes.NodeWithImplements;
import com.github.javaparser.ast.nodeTypes.NodeWithTypeParameters;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.resolution.declarations.HasAccessSpecifier;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.types.ResolvedReferenceType;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ClassLoaderTypeSolver;
import io.github.lorfersilve.javatodrawio.JavaAnalyzer.SourceType;
import io.github.lorfersilve.javatodrawio.JavaClasspath.ClassInfo;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

final class JavaTypeResolver {
    enum Status {
        FOUND, TYPE_PARAMETER, MISSING, MISSING_IMPORT, AMBIGUOUS
    }

    record Resolution(Status status, List<String> names) {
        private static final Resolution MISSING = new Resolution(Status.MISSING, List.of());
        private static final Resolution TYPE_PARAMETER = new Resolution(Status.TYPE_PARAMETER, List.of());

        static Resolution found(String name) {
            return new Resolution(Status.FOUND, List.of(name));
        }

        static Resolution candidates(Set<String> names) {
            return switch (names.size()) {
                case 0 -> MISSING;
                case 1 -> found(names.iterator().next());
                default -> new Resolution(Status.AMBIGUOUS, List.copyOf(names));
            };
        }

        String qualifiedName() {
            return names.get(0);
        }
    }

    private record MemberQuery(String owner, String name) {
    }

    private final Map<String, SourceType> sources;
    private final JavaClasspath classpath;
    // The platform loader sees JDK classes, without loading project binaries.
    private final ClassLoaderTypeSolver platform = new ClassLoaderTypeSolver(ClassLoader.getPlatformClassLoader());
    private final Map<String, Optional<ResolvedReferenceTypeDeclaration>> platformTypes = new HashMap<>();
    private final Map<ClassOrInterfaceType, Resolution> references = new IdentityHashMap<>();
    private final Set<ClassOrInterfaceType> resolving = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<MemberQuery, Resolution> members = new HashMap<>();
    private final Set<MemberQuery> resolvingMembers = new HashSet<>();

    JavaTypeResolver(Map<String, SourceType> sources, JavaClasspath classpath) {
        this.sources = sources;
        this.classpath = classpath;
    }

    Resolution resolve(ClassOrInterfaceType reference, SourceType source) {
        Resolution cached = references.get(reference);
        if (cached != null) {
            return cached;
        }
        if (!resolving.add(reference)) {
            return Resolution.MISSING;
        }
        try {
            Resolution result = resolveName(reference.getNameWithScope(), reference, source);
            references.put(reference, result);
            return result;
        } finally {
            resolving.remove(reference);
        }
    }

    private Resolution resolveName(String name, Node reference, SourceType source) {
        int dot = name.indexOf('.');
        String first = dot < 0 ? name : name.substring(0, dot);
        Resolution head = simpleName(first, reference, source);
        if (dot < 0) {
            return head;
        }
        return switch (head.status()) {
            case FOUND -> memberPath(head.qualifiedName(), name.substring(dot + 1));
            case MISSING -> qualifiedName(name);
            case TYPE_PARAMETER -> Resolution.MISSING;
            case MISSING_IMPORT, AMBIGUOUS -> head;
        };
    }

    private Resolution simpleName(String name, Node reference, SourceType source) {
        Node child = reference;
        for (Node scope = child.getParentNode().orElse(null); scope != null;
             child = scope, scope = scope.getParentNode().orElse(null)) {
            if (scope instanceof NodeWithTypeParameters<?> generic && generic.getTypeParameters().stream()
                    .anyMatch(parameter -> parameter.getNameAsString().equals(name))) {
                return Resolution.TYPE_PARAMETER;
            }
            // Member names are in scope in the class body, including nested declarations.
            if (scope instanceof TypeDeclaration<?> owner && child instanceof BodyDeclaration<?>) {
                Resolution member = member(owner.getFullyQualifiedName().orElseThrow(), name);
                if (member.status() != Status.MISSING) {
                    return member;
                }
            }
        }

        Resolution imported = singleImport(name, source);
        if (imported.status() != Status.MISSING) {
            return imported;
        }
        String samePackage = join(source.packageName(), name);
        if (declared(samePackage)) {
            return Resolution.found(samePackage);
        }

        Set<String> candidates = new TreeSet<>();
        platformType("java.lang." + name).filter(declaration -> platformAccess(declaration) == AccessSpecifier.PUBLIC)
                .ifPresent(declaration -> candidates.add(declaration.getQualifiedName()));
        for (ImportDeclaration declaration : source.unit().getImports()) {
            if (declaration.isAsterisk()) {
                String prefix = declaration.getNameAsString();
                Resolution candidate = declaration.isStatic() ? member(prefix, name)
                        : qualifiedName(join(prefix, name));
                for (String match : candidate.names()) {
                    if (importAccessible(match, source.packageName())
                            && (!declaration.isStatic() || staticMember(match))) {
                        candidates.add(match);
                    }
                }
            }
        }
        return Resolution.candidates(candidates);
    }

    private Resolution singleImport(String name, SourceType source) {
        Set<String> found = new TreeSet<>();
        Set<String> missing = new TreeSet<>();
        for (ImportDeclaration declaration : source.unit().getImports()) {
            if (declaration.isAsterisk() || !declaration.getName().getIdentifier().equals(name)) {
                continue;
            }
            String qualified = declaration.getNameAsString();
            if (!declaration.isStatic()) {
                if (declared(qualified) && importAccessible(qualified, source.packageName())) {
                    found.add(qualified);
                } else {
                    missing.add(qualified);
                }
            } else {
                String owner = qualified.substring(0, qualified.lastIndexOf('.'));
                if (!declared(owner)) {
                    missing.add(qualified);
                    continue;
                }
                member(owner, name).names().stream().filter(this::staticMember)
                        .filter(match -> importAccessible(match, source.packageName())).forEach(found::add);
            }
        }
        if (!missing.isEmpty()) {
            return new Resolution(Status.MISSING_IMPORT, List.copyOf(missing));
        }
        return Resolution.candidates(found);
    }

    private Resolution qualifiedName(String name) {
        int firstDot = name.indexOf('.');
        if (firstDot < 0) {
            return Resolution.MISSING;
        }
        // An absolute name starts with a package. Unnamed-package types resolve through lexical lookup.
        for (int dot = name.indexOf('.', firstDot + 1); dot >= 0; dot = name.indexOf('.', dot + 1)) {
            String prefix = name.substring(0, dot);
            if (declared(prefix)) {
                return memberPath(prefix, name.substring(dot + 1));
            }
        }
        return declared(name) && !packageOf(name).isEmpty() ? Resolution.found(name) : Resolution.MISSING;
    }

    private Resolution memberPath(String owner, String path) {
        for (String name : path.split("\\.")) {
            Resolution next = member(owner, name);
            if (next.status() != Status.FOUND) {
                return next;
            }
            owner = next.qualifiedName();
        }
        return Resolution.found(owner);
    }

    private Resolution member(String owner, String name) {
        String direct = join(owner, name);
        if (declared(direct)) {
            return Resolution.found(direct);
        }
        MemberQuery query = new MemberQuery(owner, name);
        Resolution cached = members.get(query);
        if (cached != null) {
            return cached;
        }
        // Invalid but parseable hierarchies can contain cycles.
        if (!resolvingMembers.add(query)) {
            return Resolution.MISSING;
        }
        try {
            Set<String> candidates = new TreeSet<>();
            for (String parent : parents(owner)) {
                member(parent, name).names().stream().filter(match -> inheritable(match, owner))
                        .forEach(candidates::add);
            }
            Resolution result = Resolution.candidates(candidates);
            members.put(query, result);
            return result;
        } finally {
            resolvingMembers.remove(query);
        }
    }

    private List<String> parents(String owner) {
        SourceType source = sources.get(owner);
        if (source != null) {
            return Stream.concat(extendedTypes(source.declaration()).stream(),
                            implementedTypes(source.declaration()).stream())
                    .map(type -> resolve(type, source))
                    .filter(result -> result.status() == Status.FOUND)
                    .map(Resolution::qualifiedName).toList();
        }
        var platformDeclaration = platformType(owner);
        if (platformDeclaration.isPresent()) {
            return platformDeclaration.get().getAncestors(true).stream()
                    .map(ResolvedReferenceType::getQualifiedName).toList();
        }
        return classpath.find(owner).map(type -> type.binaryParents().stream().map(this::canonicalParent)
                .flatMap(Optional::stream).toList()).orElseGet(List::of);
    }

    private Optional<String> canonicalParent(String binaryName) {
        if (sources.containsKey(binaryName)) {
            return Optional.of(binaryName);
        }
        return platformType(binaryName).map(ResolvedReferenceTypeDeclaration::getQualifiedName)
                .or(() -> classpath.findBinary(binaryName).map(ClassInfo::qualifiedName));
    }

    private boolean declared(String name) {
        return sources.containsKey(name) || platformType(name).isPresent() || classpath.find(name).isPresent();
    }

    private Optional<ResolvedReferenceTypeDeclaration> platformType(String name) {
        return platformTypes.computeIfAbsent(name, key -> {
            var symbol = platform.tryToSolveType(key);
            return symbol.isSolved() ? Optional.of(symbol.getCorrespondingDeclaration()) : Optional.empty();
        });
    }

    private boolean importAccessible(String name, String packageName) {
        String declaredPackage = packageOf(name);
        if (declaredPackage.isEmpty()) {
            return false;
        }
        return switch (access(name)) {
            case PUBLIC -> true;
            case PRIVATE -> false;
            case NONE, PROTECTED -> packageName.equals(declaredPackage);
        };
    }

    private boolean inheritable(String name, String inheritor) {
        return switch (access(name)) {
            case PUBLIC, PROTECTED -> true;
            case PRIVATE -> false;
            case NONE -> packageOf(name).equals(packageOf(inheritor));
        };
    }

    private AccessSpecifier access(String name) {
        SourceType type = sources.get(name);
        if (type == null) {
            return platformType(name).map(JavaTypeResolver::platformAccess)
                    .or(() -> classpath.find(name).map(ClassInfo::access)).orElse(AccessSpecifier.NONE);
        }
        AccessSpecifier explicit = type.declaration().getAccessSpecifier();
        if (explicit == AccessSpecifier.NONE && type.declaration().getParentNode().orElse(null)
                instanceof TypeDeclaration<?> enclosing && JavaAnalyzer.isInterfaceLike(enclosing)) {
            return AccessSpecifier.PUBLIC;
        }
        return explicit;
    }

    private static AccessSpecifier platformAccess(ResolvedReferenceTypeDeclaration declaration) {
        return declaration instanceof HasAccessSpecifier access ? access.accessSpecifier() : AccessSpecifier.PUBLIC;
    }

    private boolean staticMember(String name) {
        SourceType type = sources.get(name);
        if (type == null) {
            // Preserve platform resolution and check static membership explicitly for supplied JARs.
            return platformType(name).isPresent() || classpath.find(name).map(ClassInfo::staticMember).orElse(false);
        }
        TypeDeclaration<?> declaration = type.declaration();
        return declaration.getParentNode().orElse(null) instanceof TypeDeclaration<?> enclosing
                && (declaration.isStatic() || JavaAnalyzer.kindOf(declaration) != UmlModel.Kind.CLASS
                || JavaAnalyzer.isInterfaceLike(enclosing));
    }

    private String packageOf(String name) {
        SourceType type = sources.get(name);
        return type != null ? type.packageName()
                : platformType(name).map(ResolvedReferenceTypeDeclaration::getPackageName)
                .or(() -> classpath.find(name).map(ClassInfo::packageName)).orElse("");
    }

    static List<ClassOrInterfaceType> extendedTypes(TypeDeclaration<?> type) {
        return type instanceof NodeWithExtends<?> extending ? extending.getExtendedTypes() : List.of();
    }

    static List<ClassOrInterfaceType> implementedTypes(TypeDeclaration<?> type) {
        return type instanceof NodeWithImplements<?> implementing ? implementing.getImplementedTypes() : List.of();
    }

    private static String join(String prefix, String name) {
        return prefix.isEmpty() ? name : prefix + "." + name;
    }
}
