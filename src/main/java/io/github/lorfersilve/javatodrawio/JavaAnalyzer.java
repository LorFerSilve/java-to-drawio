package io.github.lorfersilve.javatodrawio;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Problem;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.AccessSpecifier;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier.Keyword;
import com.github.javaparser.ast.body.AnnotationMemberDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.CompactConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.Name;
import com.github.javaparser.ast.expr.SimpleName;
import com.github.javaparser.ast.nodeTypes.NodeWithModifiers;
import com.github.javaparser.ast.nodeTypes.NodeWithTypeParameters;
import com.github.javaparser.ast.type.Type;
import io.github.lorfersilve.javatodrawio.UmlModel.Field;
import io.github.lorfersilve.javatodrawio.UmlModel.Declarations;
import io.github.lorfersilve.javatodrawio.UmlModel.Kind;
import io.github.lorfersilve.javatodrawio.UmlModel.Modifier;
import io.github.lorfersilve.javatodrawio.UmlModel.Operation;
import io.github.lorfersilve.javatodrawio.UmlModel.Parameter;
import io.github.lorfersilve.javatodrawio.UmlModel.UmlType;
import io.github.lorfersilve.javatodrawio.UmlModel.Visibility;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

final class JavaAnalyzer {
    private final JavaClasspath classpath;

    JavaAnalyzer() {
        this(JavaClasspath.empty());
    }

    JavaAnalyzer(JavaClasspath classpath) {
        this.classpath = classpath;
    }

    record Result(UmlModel model, int fileCount, List<String> warnings) {
    }

    record SourceType(Path file, CompilationUnit unit, TypeDeclaration<?> declaration) {
        String packageName() {
            return unit.getPackageDeclaration().map(pkg -> pkg.getNameAsString()).orElse("");
        }
    }

    static final class AnalysisException extends Exception {
        AnalysisException(List<String> errors) {
            super(String.join(System.lineSeparator(), errors));
        }
    }

    Result analyze(List<Path> files) throws AnalysisException {
        JavaParser parser = new JavaParser(new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17)
                .setPreprocessUnicodeEscapes(true)
                .setAttributeComments(false));
        Map<String, SourceType> sourceTypes = new TreeMap<>();
        List<String> errors = new ArrayList<>();
        for (Path file : files) {
            parse(file, parser, errors).ifPresent(unit -> {
                for (TypeDeclaration<?> type : unit.getTypes()) {
                    index(type, file, unit, sourceTypes, errors);
                }
            });
        }
        if (!errors.isEmpty()) {
            throw new AnalysisException(errors);
        }

        List<UmlType> types = sourceTypes.entrySet().stream()
                .map(entry -> describe(entry.getKey(), entry.getValue()))
                .toList();
        var relations = new RelationshipAnalyzer(sourceTypes, classpath).analyze();
        List<String> warnings = types.isEmpty()
                ? List.of("No top-level or member types declared in the Java source files.")
                : relations.warnings();
        return new Result(new UmlModel(types, relations.relationships()), files.size(), warnings);
    }

    private static Optional<CompilationUnit> parse(Path file, JavaParser parser, List<String> errors) {
        try {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            if (source.startsWith("\uFEFF")) {
                source = source.substring(1);
            }
            var result = parser.parse(source);
            if (result.isSuccessful()) {
                return result.getResult().map(JavaAnalyzer::normalizeIdentifiers);
            }
            List<String> sourceLines = source.lines().toList();
            for (Problem problem : result.getProblems()) {
                errors.add(ParseDiagnostics.format(file, sourceLines, problem));
            }
            if (result.getProblems().isEmpty()) {
                errors.add(file + ": JavaParser did not produce a compilation unit.");
            }
        } catch (CharacterCodingException e) {
            errors.add(file + ": invalid UTF-8 source file (" + e.getMessage() + ")");
        } catch (IOException | SecurityException e) {
            errors.add(file + ": cannot read source file (" + e.getMessage() + ")");
        }
        return Optional.empty();
    }

    private static CompilationUnit normalizeIdentifiers(CompilationUnit unit) {
        // Java identifier equality ignores these code points. Keep source offsets on the original nodes.
        unit.walk(node -> {
            if (node instanceof SimpleName name) {
                String normalized = normalizedIdentifier(name.getIdentifier());
                if (!normalized.equals(name.getIdentifier())) {
                    name.setIdentifier(normalized);
                }
            } else if (node instanceof Name name) {
                String normalized = normalizedIdentifier(name.getIdentifier());
                if (!normalized.equals(name.getIdentifier())) {
                    name.setIdentifier(normalized);
                }
            }
        });
        return unit;
    }

    private static String normalizedIdentifier(String identifier) {
        if (identifier.codePoints().noneMatch(Character::isIdentifierIgnorable)) {
            return identifier;
        }
        StringBuilder result = new StringBuilder();
        identifier.codePoints().filter(codePoint -> !Character.isIdentifierIgnorable(codePoint))
                .forEach(result::appendCodePoint);
        return result.toString();
    }

    private static void index(TypeDeclaration<?> type, Path file, CompilationUnit unit,
                              Map<String, SourceType> types, List<String> errors) {
        String id = type.getFullyQualifiedName().orElseThrow();
        SourceType previous = types.putIfAbsent(id, new SourceType(file, unit, type));
        if (previous != null) {
            int previousLine = previous.declaration().getBegin().orElseThrow().line;
            int line = type.getBegin().orElseThrow().line;
            errors.add(file + ":" + line + ": duplicate type " + id + " (also declared at "
                    + previous.file() + ":" + previousLine + ").");
        }
        for (BodyDeclaration<?> member : type.getMembers()) {
            if (member instanceof TypeDeclaration<?> nested) {
                index(nested, file, unit, types, errors);
            }
        }
    }

    private static UmlType describe(String id, SourceType source) {
        TypeDeclaration<?> type = source.declaration();
        Kind kind = kindOf(type);
        List<Field> fields = new ArrayList<>();
        List<Operation> operations = new ArrayList<>();
        if (type instanceof RecordDeclaration record) {
            record.getParameters().forEach(component ->
                    fields.add(new Field(component.getNameAsString(),
                            component.getType().asString() + (component.isVarArgs() ? "[]" : ""),
                            Visibility.PRIVATE, false)));
        } else if (type instanceof EnumDeclaration enumeration) {
            enumeration.getEntries().forEach(constant ->
                    fields.add(new Field(constant.getNameAsString(), type.getNameAsString(),
                            Visibility.PUBLIC, true)));
        }
        boolean interfaceMembers = kind == Kind.INTERFACE || kind == Kind.ANNOTATION;
        for (BodyDeclaration<?> member : type.getMembers()) {
            if (member instanceof FieldDeclaration field) {
                field.getVariables().forEach(variable -> fields.add(new Field(variable.getNameAsString(),
                        variable.getType().asString(), visibility(field, interfaceMembers),
                        field.isStatic() || interfaceMembers)));
            } else if (member instanceof CallableDeclaration<?> callable) {
                operations.add(operation(callable, kind));
            } else if (member instanceof CompactConstructorDeclaration constructor
                    && type instanceof RecordDeclaration record) {
                operations.add(new Operation(constructor.getNameAsString(), Optional.empty(),
                        visibility(constructor, false), Set.of(), List.of(), parameters(record.getParameters()),
                        typeNames(constructor.getThrownExceptions())));
            } else if (member instanceof AnnotationMemberDeclaration element) {
                operations.add(new Operation(element.getNameAsString(), Optional.of(element.getType().asString()),
                        Visibility.PUBLIC, Set.of(Modifier.ABSTRACT), List.of(), List.of(), List.of()));
            }
        }

        TypeDeclaration<?> enclosing = type.getParentNode().orElseThrow() instanceof TypeDeclaration<?> parent
                ? parent : null;
        boolean implicitlyPublic = enclosing != null && isInterfaceLike(enclosing);
        boolean implicitlyStatic = enclosing != null && (implicitlyPublic || kind != Kind.CLASS);
        Declarations declarations = new Declarations(
                source.unit().getImports().stream()
                        .map(item -> "import " + (item.isStatic() ? "static " : "") + item.getNameAsString()
                                + (item.isAsterisk() ? ".*" : "") + ";")
                        .toList(),
                typeNames(JavaTypeResolver.extendedTypes(type)), typeNames(JavaTypeResolver.implementedTypes(type)),
                type instanceof ClassOrInterfaceDeclaration declaration ? typeNames(declaration.getPermittedTypes())
                        : List.of(),
                type instanceof EnumDeclaration enumeration
                        ? enumeration.getEntries().stream().map(entry -> entry.getNameAsString()).toList() : List.of());
        return new UmlType(id, source.packageName(), kind, visibility(type, implicitlyPublic),
                modifiers(type, implicitlyStatic, interfaceMembers), typeParameters(type),
                List.copyOf(fields), List.copyOf(operations), declarations);
    }

    private static Operation operation(CallableDeclaration<?> callable, Kind ownerKind) {
        MethodDeclaration method = callable instanceof MethodDeclaration declared ? declared : null;
        boolean privateEnumConstructor = method == null && ownerKind == Kind.ENUM
                && callable.getAccessSpecifier() == AccessSpecifier.NONE;
        Visibility visibility = privateEnumConstructor
                ? Visibility.PRIVATE : visibility(callable, ownerKind == Kind.INTERFACE);
        Optional<String> returnType = Optional.ofNullable(method).map(declaration -> declaration.getType().asString());
        return new Operation(callable.getNameAsString(), returnType, visibility,
                modifiers(callable, false, method != null && method.isAbstract()), typeParameters(callable),
                parameters(callable.getParameters()), typeNames(callable.getThrownExceptions()));
    }

    private static List<Parameter> parameters(List<com.github.javaparser.ast.body.Parameter> parameters) {
        return parameters.stream().map(parameter -> new Parameter(parameter.getNameAsString(),
                parameterType(parameter))).toList();
    }

    private static String parameterType(com.github.javaparser.ast.body.Parameter parameter) {
        return parameter.getType().asString() + (parameter.isVarArgs() ? "..." : "");
    }

    private static List<String> typeParameters(Node declaration) {
        if (declaration instanceof NodeWithTypeParameters<?> generic) {
            return generic.getTypeParameters().stream()
                    .map(parameter -> parameter.getNameAsString()
                            + (parameter.getTypeBound().isEmpty() ? ""
                            : " extends " + String.join(" & ", typeNames(parameter.getTypeBound()))))
                    .toList();
        }
        return List.of();
    }

    private static List<String> typeNames(List<? extends Type> types) {
        return types.stream().map(Type::asString).toList();
    }

    private static Set<Modifier> modifiers(NodeWithModifiers<?> declaration, boolean implicitStatic,
                                           boolean implicitAbstract) {
        EnumSet<Modifier> modifiers = EnumSet.noneOf(Modifier.class);
        if (implicitStatic || declaration.hasModifier(Keyword.STATIC)) {
            modifiers.add(Modifier.STATIC);
        }
        if (implicitAbstract || declaration.hasModifier(Keyword.ABSTRACT)) {
            modifiers.add(Modifier.ABSTRACT);
        }
        if (declaration.hasModifier(Keyword.SEALED)) {
            modifiers.add(Modifier.SEALED);
        }
        if (declaration.hasModifier(Keyword.NON_SEALED)) {
            modifiers.add(Modifier.NON_SEALED);
        }
        if (declaration instanceof ClassOrInterfaceDeclaration && declaration.hasModifier(Keyword.FINAL)) {
            modifiers.add(Modifier.FINAL);
        }
        return Set.copyOf(modifiers);
    }

    private static Visibility visibility(NodeWithModifiers<?> declaration, boolean implicitPublic) {
        return switch (declaration.getAccessSpecifier()) {
            case PUBLIC -> Visibility.PUBLIC;
            case PROTECTED -> Visibility.PROTECTED;
            case PRIVATE -> Visibility.PRIVATE;
            case NONE -> implicitPublic ? Visibility.PUBLIC : Visibility.PACKAGE;
        };
    }

    static Kind kindOf(TypeDeclaration<?> declaration) {
        if (declaration instanceof ClassOrInterfaceDeclaration type) {
            return type.isInterface() ? Kind.INTERFACE : Kind.CLASS;
        }
        if (declaration.isEnumDeclaration()) {
            return Kind.ENUM;
        }
        if (declaration.isRecordDeclaration()) {
            return Kind.RECORD;
        }
        if (declaration.isAnnotationDeclaration()) {
            return Kind.ANNOTATION;
        }
        throw new IllegalArgumentException("Unsupported declaration: " + declaration.getNameAsString());
    }

    static boolean isInterfaceLike(TypeDeclaration<?> declaration) {
        return declaration.isAnnotationDeclaration()
                || (declaration instanceof ClassOrInterfaceDeclaration type && type.isInterface());
    }
}
