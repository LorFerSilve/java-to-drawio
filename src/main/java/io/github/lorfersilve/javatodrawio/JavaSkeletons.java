package io.github.lorfersilve.javatodrawio;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.type.Type;
import io.github.lorfersilve.javatodrawio.DiagramModelReader.Diagram;
import io.github.lorfersilve.javatodrawio.UmlModel.Field;
import io.github.lorfersilve.javatodrawio.UmlModel.Kind;
import io.github.lorfersilve.javatodrawio.UmlModel.Modifier;
import io.github.lorfersilve.javatodrawio.UmlModel.Operation;
import io.github.lorfersilve.javatodrawio.UmlModel.Parameter;
import io.github.lorfersilve.javatodrawio.UmlModel.UmlType;
import io.github.lorfersilve.javatodrawio.UmlModel.Visibility;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

final class JavaSkeletons {
    record Result(Map<Path, String> sources, List<String> warnings) {
    }

    static final String TODO = "throw new java.lang.UnsupportedOperationException(\"TODO: implement\");";

    private final JavaParser parser = new JavaParser(new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17));
    private final Diagram diagram;
    private final Map<String, UmlType> types = new LinkedHashMap<>();
    private final Map<String, List<UmlType>> members = new LinkedHashMap<>();
    private final List<String> warnings = new ArrayList<>();
    private final SkeletonHierarchy hierarchy;

    JavaSkeletons(Diagram diagram) {
        this.diagram = diagram;
        for (UmlType type : diagram.model().types()) {
            types.put(type.qualifiedName(), type);
        }
        for (UmlType type : types.values()) {
            if (nested(type)) {
                String name = type.qualifiedName();
                String owner = name.substring(0, name.lastIndexOf('.'));
                if (!types.containsKey(owner)) {
                    throw problem(type, "Missing enclosing type " + owner);
                }
                members.computeIfAbsent(owner, ignored -> new ArrayList<>()).add(type);
            }
        }
        hierarchy = new SkeletonHierarchy(diagram, types, parser);
    }

    Result generate() {
        Map<Path, CompilationUnit> units = new LinkedHashMap<>();
        for (UmlType type : types.values()) {
            if (nested(type)) {
                continue;
            }
            StringBuilder source = new StringBuilder();
            if (!type.packageName().isEmpty()) {
                source.append("package ").append(type.packageName()).append(";\n");
            }
            for (String item : imports(type)) {
                source.append(item).append('\n');
            }
            appendType(source, type);
            Path path = Path.of(type.qualifiedName().replace('.', '/') + ".java");
            var unit = parsed(type, parser.parse(source.toString()), "Generated Java declarations");
            if (units.putIfAbsent(path, unit) != null) {
                throw problem(type, "Two types map to the same file " + path);
            }
        }
        new SkeletonImports(diagram).resolve(units);
        new SkeletonOverrides(parser, diagram, SkeletonImports.sourceTypes(units)).addMissingMethods(warnings);
        Map<Path, String> sources = new LinkedHashMap<>();
        units.forEach((file, unit) -> sources.put(file, unit.toString()));
        return new Result(sources, List.copyOf(warnings));
    }

    private void appendType(StringBuilder source, UmlType type) {
        String name = UmlLabels.simpleName(type.qualifiedName());
        var parents = hierarchy.parents(type);
        source.append(access(type.visibility()));
        appendModifiers(source, type);
        source.append(switch (type.kind()) {
            case CLASS -> "class ";
            case INTERFACE -> "interface ";
            case ENUM -> "enum ";
            case RECORD -> "record ";
            case ANNOTATION -> "@interface ";
        });
        source.append(name);
        appendTypeParameters(source, type, type.typeParameters());

        var components = type.kind() == Kind.RECORD ? recordComponents(type) : List.<Parameter>of();
        if (type.kind() == Kind.RECORD) {
            appendParameters(source, type, components);
        }
        appendClause(source, "extends", parents.extended());
        appendClause(source, "implements", parents.implemented());
        appendClause(source, "permits", parents.permitted());
        source.append(" {\n");
        if (type.kind() == Kind.ENUM) {
            appendConstants(source, type);
        }

        Set<String> names = new LinkedHashSet<>(type.declarations().enumConstants());
        for (Field field : type.fields()) {
            if (!names.add(field.name())) {
                throw problem(type, "Duplicate field '" + field.name() + "'");
            }
            if (type.kind() != Kind.RECORD || field.isStatic()) {
                appendField(source, type, field);
            }
        }
        List<Operation> constructors = constructors(type);
        Map<String, Operation> signatures = new LinkedHashMap<>();
        for (Operation operation : type.operations()) {
            String signature = (operation.returnType().isEmpty() ? "constructor " : "") + operation.name()
                    + operation.parameters().stream().map(parameter -> parameter.type().replace("...", "[]"))
                    .collect(Collectors.joining(", ", "(", ")"));
            Operation previous = signatures.putIfAbsent(signature, operation);
            if (previous == null) {
                appendOperation(source, type, operation, components);
            } else if (previous.equals(operation)) {
                warnings.add(location(type) + ": Repeated identical operation " + signature + "; generated once");
            } else {
                throw problem(type, "Conflicting declarations of operation " + signature);
            }
        }
        if (type.kind() == Kind.CLASS && constructors.isEmpty()) {
            Operation parent = parentConstructor(type, List.of(), List.of());
            if (parent != null && !requiredParameters(parent.parameters()).isEmpty()) {
                source.append(access(type.visibility())).append(name).append("()");
                appendThrows(source, type, parent.thrownTypes());
                source.append(" { ");
                appendCall(source, type, "super", requiredParameters(parent.parameters()), List.of(), false);
                source.append(TODO).append(" }\n");
            }
        }

        for (UmlType member : members.getOrDefault(type.qualifiedName(), List.of())) {
            appendType(source, member);
        }
        source.append("}\n");
    }

    private void appendModifiers(StringBuilder source, UmlType type) {
        Set<Modifier> modifiers = type.modifiers();
        boolean isClass = type.kind() == Kind.CLASS;
        if (nested(type) && modifiers.contains(Modifier.STATIC)) {
            source.append("static ");
        }
        if (isClass && modifiers.contains(Modifier.ABSTRACT)) {
            source.append("abstract ");
        }
        if (modifiers.contains(Modifier.SEALED)) {
            source.append("sealed ");
        } else if (modifiers.contains(Modifier.NON_SEALED)) {
            source.append("non-sealed ");
        } else if (isClass && modifiers.contains(Modifier.FINAL)) {
            source.append("final ");
        } else if ((isClass || type.kind() == Kind.INTERFACE) && hierarchy.inheritsSealedType(type)) {
            source.append(isClass && !modifiers.contains(Modifier.ABSTRACT) && !hierarchy.hasChildren(type)
                    ? "final " : "non-sealed ");
        }
    }

    private void appendField(StringBuilder source, UmlType owner, Field field) {
        Type type = javaType(owner, field.type(), false);
        source.append(access(field.visibility()));
        if (field.isStatic()) {
            source.append("static ");
        }
        source.append(type.asString()).append(' ').append(field.name());
        if (owner.kind() == Kind.INTERFACE || owner.kind() == Kind.ANNOTATION) {
            source.append(" = ").append(defaultValue(type));
        }
        source.append(";\n");
    }

    private void appendOperation(StringBuilder source, UmlType owner, Operation operation, List<Parameter> components) {
        boolean constructor = operation.returnType().isEmpty();
        Set<Modifier> modifiers = operation.modifiers();
        boolean interfaceMethod = owner.kind() == Kind.INTERFACE || owner.kind() == Kind.ANNOTATION;
        boolean abstractOperation = modifiers.contains(Modifier.ABSTRACT);
        source.append(access(operation.visibility()));
        if (modifiers.contains(Modifier.STATIC)) {
            source.append("static ");
        }
        if (modifiers.contains(Modifier.DEFAULT)) {
            source.append("default ");
        }
        if (abstractOperation && !interfaceMethod) {
            source.append("abstract ");
        }
        appendTypeParameters(source, owner, operation.typeParameters());
        if (!operation.typeParameters().isEmpty()) {
            source.append(' ');
        }
        operation.returnType().ifPresent(type -> source.append(javaType(owner, type, true).asString()).append(' '));
        source.append(operation.name());

        if (constructor && owner.kind() == Kind.RECORD && components.equals(operation.parameters())) {
            // The compact form satisfies Java's definite-assignment rules while retaining the canonical signature.
            source.append(" { ").append(TODO).append(" }\n");
            return;
        }
        appendParameters(source, owner, operation.parameters());
        appendThrows(source, owner, operation.thrownTypes());
        if (abstractOperation || owner.kind() == Kind.ANNOTATION) {
            source.append(";\n");
            return;
        }

        source.append(" { ");
        if (constructor && owner.kind() == Kind.RECORD) {
            appendCall(source, owner, "this", components, List.of(), true);
        } else if (constructor && owner.kind() == Kind.CLASS) {
            Operation parent = parentConstructor(owner, operation.parameters(), operation.thrownTypes());
            if (parent != null && !requiredParameters(parent.parameters()).isEmpty()) {
                appendCall(source, owner, "super", requiredParameters(parent.parameters()), operation.parameters(),
                        false);
            }
        }
        source.append(TODO).append(" }\n");
    }

    private void appendConstants(StringBuilder source, UmlType type) {
        List<String> constants = type.declarations().enumConstants();
        if (constants.size() != Set.copyOf(constants).size()) {
            throw problem(type, "Duplicate enum constant");
        }
        List<Parameter> arguments = constructors(type).stream().min(Comparator.comparingInt(
                operation -> requiredParameters(operation.parameters()).size())).map(Operation::parameters)
                .map(JavaSkeletons::requiredParameters).orElseGet(List::of);
        List<String> values = new ArrayList<>();
        for (String constant : constants) {
            StringBuilder value = new StringBuilder(constant);
            if (!arguments.isEmpty()) {
                appendArguments(value, type, arguments, List.of(), true);
            }
            values.add(value.toString());
        }
        source.append(String.join(", ", values)).append(";\n");
    }

    private Operation parentConstructor(UmlType type, List<Parameter> available, List<String> thrown) {
        UmlType parent = hierarchy.classParent(type);
        if (parent == null) {
            return null;
        }
        List<Operation> constructors = constructors(parent);
        if (constructors.isEmpty()) {
            return null;
        }
        return constructors.stream().filter(operation -> operation.visibility() == Visibility.PUBLIC
                        || operation.visibility() == Visibility.PROTECTED
                        || operation.visibility() == Visibility.PACKAGE && parent.packageName().equals(type.packageName()))
                .min(Comparator.<Operation>comparingInt(operation -> thrown.containsAll(operation.thrownTypes()) ? 0 : 1)
                        .thenComparingInt(operation -> requiredParameters(operation.parameters()).size())
                        .thenComparingLong(operation -> -operation.parameters().stream().filter(available::contains).count()))
                .orElseThrow(() -> problem(type, "No accessible constructor in " + parent.qualifiedName()));
    }

    private void appendCall(StringBuilder source, UmlType owner, String name, List<Parameter> parameters,
                            List<Parameter> available, boolean castReferences) {
        source.append(name);
        appendArguments(source, owner, parameters, available, castReferences);
        source.append("; ");
    }

    private void appendArguments(StringBuilder source, UmlType owner, List<Parameter> parameters,
                                 List<Parameter> available, boolean castReferences) {
        List<String> arguments = new ArrayList<>();
        for (Parameter parameter : parameters) {
            String rawType = parameter.type().replace("...", "[]");
            Type type = javaType(owner, rawType, false);
            if (available.contains(parameter)) {
                arguments.add(parameter.name());
            } else if (castReferences && !type.isPrimitiveType()) {
                arguments.add("(" + type.asString() + ") null");
            } else {
                arguments.add(defaultValue(type));
            }
        }
        source.append('(').append(String.join(", ", arguments)).append(')');
    }

    private static List<Parameter> requiredParameters(List<Parameter> parameters) {
        if (!parameters.isEmpty() && parameters.get(parameters.size() - 1).type().endsWith("...")) {
            return parameters.subList(0, parameters.size() - 1);
        }
        return parameters;
    }

    private static List<Parameter> recordComponents(UmlType type) {
        List<Parameter> fields = type.fields().stream().filter(Predicate.not(Field::isStatic))
                .map(field -> new Parameter(field.name(), field.type())).toList();
        for (Operation constructor : constructors(type)) {
            List<Parameter> parameters = constructor.parameters();
            if (!fields.isEmpty() && parameters.size() == fields.size()
                    && parameters.get(parameters.size() - 1).type().endsWith("...")
                    && fields.subList(0, fields.size() - 1).equals(parameters.subList(0, parameters.size() - 1))) {
                Parameter field = fields.get(fields.size() - 1);
                Parameter varargs = parameters.get(parameters.size() - 1);
                if (varargs.name().equals(field.name()) && varargs.type().replace("...", "[]").equals(field.type())) {
                    return parameters;
                }
            }
        }
        return fields;
    }

    private void appendParameters(StringBuilder source, UmlType owner, List<Parameter> parameters) {
        List<String> rendered = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        for (Parameter parameter : parameters) {
            boolean varargs = parameter.type().endsWith("...");
            String name = parameter.name();
            if (!names.add(name)) {
                throw problem(owner, "Duplicate parameter '" + name + "'");
            }
            if (varargs && rendered.size() != parameters.size() - 1) {
                throw problem(owner, "Variable-arity parameter '" + name + "' must be last");
            }
            String text = varargs ? parameter.type().substring(0, parameter.type().length() - 3) : parameter.type();
            rendered.add(javaType(owner, text, false).asString() + (varargs ? "..." : "") + " " + name);
        }
        source.append('(').append(String.join(", ", rendered)).append(')');
    }

    private void appendTypeParameters(StringBuilder source, UmlType type, List<String> parameters) {
        if (!parameters.isEmpty()) {
            source.append(parameters.stream()
                    .map(parameter -> parsed(type, parser.parseTypeParameter(parameter),
                            "Type parameter '" + parameter + "'").toString())
                    .collect(Collectors.joining(", ", "<", ">")));
        }
    }

    private void appendThrows(StringBuilder source, UmlType type, List<String> thrown) {
        appendClause(source, "throws", thrown.stream()
                .map(name -> parsed(type, parser.parseClassOrInterfaceType(name), "Exception type '" + name + "'"))
                .toList());
    }

    private static void appendClause(StringBuilder source, String keyword, List<?> types) {
        if (!types.isEmpty()) {
            source.append(' ').append(keyword).append(' ').append(types.stream().map(Object::toString)
                    .collect(Collectors.joining(", ")));
        }
    }

    private Type javaType(UmlType owner, String name, boolean allowVoid) {
        Type type = parsed(owner, parser.parseType(name), "Type '" + name + "'");
        if (type.isVoidType() && allowVoid) {
            return type;
        }
        if (!type.isClassOrInterfaceType() && !type.isPrimitiveType() && !type.isArrayType()) {
            throw problem(owner, "'" + name + "' is invalid in this type position");
        }
        return type;
    }

    private Set<String> imports(UmlType type) {
        Set<String> statements = new LinkedHashSet<>();
        for (String item : type.declarations().imports()) {
            String text = item.endsWith(";") ? item : item + ";";
            ImportDeclaration declaration = parsed(type, parser.parseImport(text), "Import '" + item + "'");
            statements.add(declaration.toString().strip());
        }
        for (UmlType member : members.getOrDefault(type.qualifiedName(), List.of())) {
            statements.addAll(imports(member));
        }
        return statements;
    }

    private <T> T parsed(UmlType type, ParseResult<T> result, String context) {
        if (!result.isSuccessful()) {
            String message = result.getProblems().isEmpty() ? "invalid syntax" : result.getProblem(0).getMessage();
            throw problem(type, context + ": " + message);
        }
        return result.getResult().orElseThrow();
    }

    private IllegalArgumentException problem(UmlType type, String message) {
        return new IllegalArgumentException(location(type) + ": " + message);
    }

    private String location(UmlType type) {
        return diagram.typeLocations().get(type.qualifiedName());
    }

    private static boolean nested(UmlType type) {
        return type.displayName().contains(".");
    }

    private static List<Operation> constructors(UmlType type) {
        return type.operations().stream().filter(operation -> operation.returnType().isEmpty()).toList();
    }

    private static String access(Visibility visibility) {
        return switch (visibility) {
            case PUBLIC -> "public ";
            case PROTECTED -> "protected ";
            case PRIVATE -> "private ";
            case PACKAGE -> "";
        };
    }

    private static String defaultValue(Type type) {
        if (!type.isPrimitiveType()) {
            return "null";
        }
        return switch (type.asPrimitiveType().getType()) {
            case BOOLEAN -> "false";
            case CHAR -> "'\\0'";
            case BYTE -> "(byte) 0";
            case SHORT -> "(short) 0";
            case INT -> "0";
            case LONG -> "0L";
            case FLOAT -> "0.0F";
            case DOUBLE -> "0.0D";
        };
    }
}
