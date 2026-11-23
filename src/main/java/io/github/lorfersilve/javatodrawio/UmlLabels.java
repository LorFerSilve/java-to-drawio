package io.github.lorfersilve.javatodrawio;

import io.github.lorfersilve.javatodrawio.DrawioLabel.Line;
import io.github.lorfersilve.javatodrawio.DrawioReader.Cell;
import io.github.lorfersilve.javatodrawio.UmlModel.Declarations;
import io.github.lorfersilve.javatodrawio.UmlModel.Field;
import io.github.lorfersilve.javatodrawio.UmlModel.Kind;
import io.github.lorfersilve.javatodrawio.UmlModel.Modifier;
import io.github.lorfersilve.javatodrawio.UmlModel.Operation;
import io.github.lorfersilve.javatodrawio.UmlModel.Parameter;
import io.github.lorfersilve.javatodrawio.UmlModel.UmlType;
import io.github.lorfersilve.javatodrawio.UmlModel.Visibility;

import javax.lang.model.SourceVersion;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

final class UmlLabels {
    private record NameWithVisibility(String text, Visibility visibility) {
    }

    private record Signature(String name, List<String> typeParameters) {
    }

    private record Decorated(String text, EnumSet<Modifier> modifiers, List<String> thrownTypes) {
    }

    static UmlType readType(Cell cell, List<Line> lines, List<Cell> rows, String enclosingPackage, boolean compact) {
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("The class name is missing");
        }
        List<Line> header = new ArrayList<>(lines);
        Kind kind = Kind.CLASS;
        boolean abstractClass = false;
        String title = header.get(0).text();
        if (stereotype(title)) {
            int end = title.startsWith("«") ? title.indexOf('»') + 1 : title.indexOf(">>") + 2;
            if (end < 2) {
                throw new IllegalArgumentException("Unterminated type stereotype");
            }
            abstractClass = stereotypeName(title.substring(0, end)).equals("abstract");
            if (!abstractClass) {
                kind = kind(title.substring(0, end));
            }
            String name = title.substring(end).strip();
            if (name.isEmpty()) {
                header.remove(0);
            } else {
                header.set(0, header.get(0).withText(name));
            }
        }
        if (header.isEmpty()) {
            throw new IllegalArgumentException("The class name is missing");
        }

        boolean generated = cell.id().startsWith("type:");
        List<String> imports = properties(cell, "javaImports");
        List<String> superclasses = properties(cell, "javaExtends");
        List<String> interfaces = properties(cell, "javaImplements");
        List<String> permits = properties(cell, "javaPermits");
        List<String> constants = properties(cell, "javaEnumConstants");
        String packageName = enclosingPackage;

        Line name = header.get(0);
        int body = 1;
        for (; body < header.size(); body++) {
            Line line = header.get(body);
            String text = line.text();
            if (text.startsWith("package ")) {
                packageName = removeTerminator(text.substring(8));
            } else if (text.startsWith("import ")) {
                imports.add(text);
            } else if (text.startsWith("extends ") && !unclosed(name.text())) {
                superclasses.addAll(splitTypes(text.substring(8)));
            } else if (text.startsWith("implements ")) {
                interfaces.addAll(splitTypes(text.substring(11)));
            } else if (text.startsWith("permits ")) {
                permits.addAll(splitTypes(text.substring(8)));
            } else if (generated && !compact && body == header.size() - 1
                    && isJavaName(text) && !unclosed(name.text())) {
                packageName = text;
            } else if ((visiblyMember(text) || (kind == Kind.ENUM && isIdentifier(text)))
                    && !unclosed(name.text())) {
                break;
            } else {
                name = name.append(line, generated);
            }
        }
        Decorated decorated = decorations(name);
        if (abstractClass) {
            decorated.modifiers().add(Modifier.ABSTRACT);
        }
        NameWithVisibility visibleName = visibility(decorated.text(), Visibility.PUBLIC);
        Signature titleSignature = signature(visibleName.text(), false);
        String qualified = titleSignature.name();
        if (!isJavaName(qualified)) {
            throw new IllegalArgumentException("Invalid Java class name '" + qualified + "'");
        }
        if (packageName.isEmpty() && !generated) {
            // The old format carries the package separately. An unqualified single-label UML title uses its last dot.
            int dot = qualified.lastIndexOf('.');
            packageName = dot < 0 ? "" : qualified.substring(0, dot);
        } else if (!packageName.isEmpty()) {
            if (!isJavaName(packageName)) {
                throw new IllegalArgumentException("Invalid Java package name '" + packageName + "'");
            }
            if (!qualified.startsWith(packageName + ".")) {
                qualified = packageName + "." + qualified;
            }
        }
        String simpleName = simpleName(qualified);
        List<Field> fields = new ArrayList<>();
        List<Operation> operations = new ArrayList<>();
        List<Line> members = joinMembers(header.subList(body, header.size()), generated);
        for (Cell row : rows) {
            members.addAll(joinMembers(DrawioLabel.lines(row), generated));
        }
        List<String> enumConstants = new ArrayList<>();
        for (Line member : members) {
            readMember(member, kind, simpleName, generated, constants, fields, operations, enumConstants);
        }
        return new UmlType(qualified, packageName, kind, visibleName.visibility(), Set.copyOf(decorated.modifiers()),
                titleSignature.typeParameters(), List.copyOf(fields), List.copyOf(operations),
                new Declarations(List.copyOf(imports), List.copyOf(superclasses), List.copyOf(interfaces),
                        List.copyOf(permits), List.copyOf(enumConstants)));
    }

    private static void readMember(Line line, Kind kind, String owner, boolean generated, List<String> constants,
                                   List<Field> fields, List<Operation> operations, List<String> enumConstants) {
        Decorated decorated = decorations(line);
        boolean interfaceLike = kind == Kind.INTERFACE || kind == Kind.ANNOTATION;
        NameWithVisibility member = visibility(decorated.text(), interfaceLike ? Visibility.PUBLIC : Visibility.PACKAGE);
        String text = member.text();
        int open = text.indexOf('(');
        if (open >= 0) {
            operations.add(operation(member, decorated, open, owner, kind, generated));
            return;
        }
        int colon = text.indexOf(':');
        if (kind == Kind.ENUM && colon < 0 && isIdentifier(text)) {
            enumConstants.add(text);
            return;
        }
        if (colon < 0) {
            throw new IllegalArgumentException("Expected an attribute as 'name: Type' or an operation: '" + text + "'");
        }
        String name = identifier(text.substring(0, colon).strip());
        String type = required(text.substring(colon + 1), "Type of field '" + name + "'");
        boolean isStatic = decorated.modifiers().contains(Modifier.STATIC) || interfaceLike;
        if (kind == Kind.ENUM && (constants.contains(name) || (constants.isEmpty() && generated
                && isStatic && member.visibility() == Visibility.PUBLIC && type.equals(owner)
                && name.equals(name.toUpperCase(Locale.ROOT))))) {
            enumConstants.add(name);
        } else {
            fields.add(new Field(name, type, member.visibility(), isStatic));
        }
    }

    private static Operation operation(NameWithVisibility member, Decorated decorated, int open, String owner,
                                       Kind kind, boolean generated) {
        String text = member.text();
        int close = text.lastIndexOf(')');
        if (close < open) {
            throw new IllegalArgumentException("Unterminated parameter list in '" + text + "'");
        }
        Signature signature = signature(text.substring(0, open).strip(), true);
        String name = identifier(signature.name());
        String result = text.substring(close + 1).strip();
        Optional<String> returnType;
        if (result.startsWith(":")) {
            returnType = Optional.of(required(result.substring(1), "Return type of '" + name + "'"));
        } else if (result.isEmpty() && name.equals(owner)) {
            returnType = Optional.empty();
        } else {
            throw new IllegalArgumentException("Specify the return type of '" + name + "' after ':' (or use void)");
        }
        List<Parameter> parameters = new ArrayList<>();
        for (String parameter : splitTypes(text.substring(open + 1, close))) {
            int colon = parameter.indexOf(':');
            if (colon <= 0) {
                throw new IllegalArgumentException("Specify a parameter as 'name: Type' in '" + name + "'");
            }
            String parameterName = identifier(parameter.substring(0, colon).strip());
            parameters.add(new Parameter(parameterName, required(parameter.substring(colon + 1),
                    "Type of parameter '" + parameterName + "'")));
        }

        EnumSet<Modifier> modifiers = decorated.modifiers();
        if (kind == Kind.INTERFACE && !modifiers.contains(Modifier.STATIC)
                && !modifiers.contains(Modifier.ABSTRACT) && member.visibility() != Visibility.PRIVATE) {
            if (generated || modifiers.contains(Modifier.DEFAULT)) {
                modifiers.add(Modifier.DEFAULT);
            } else {
                modifiers.add(Modifier.ABSTRACT);
            }
        }
        return new Operation(name, returnType, member.visibility(), Set.copyOf(modifiers),
                signature.typeParameters(), List.copyOf(parameters), decorated.thrownTypes());
    }

    private static List<Line> joinMembers(List<Line> lines, boolean generated) {
        List<Line> members = new ArrayList<>();
        for (Line line : lines) {
            if (members.isEmpty() || startsWithVisibility(line.text()) || (!generated && visiblyMember(line.text()))
                    || (!generated && isIdentifier(line.text()))) {
                members.add(line);
            } else {
                int last = members.size() - 1;
                members.set(last, members.get(last).append(line, generated));
            }
        }
        return members;
    }

    private static Signature signature(String text, boolean method) {
        int open = text.indexOf('<');
        if (open < 0) {
            return new Signature(text, List.of());
        }
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char current = text.charAt(i);
            if (current == '<') {
                depth++;
            } else if (current == '>' && --depth == 0) {
                List<String> parameters = splitTypes(text.substring(open + 1, i));
                if (method && open == 0) {
                    return new Signature(text.substring(i + 1).strip(), parameters);
                }
                if (!method && i == text.length() - 1) {
                    return new Signature(text.substring(0, open).strip(), parameters);
                }
                break;
            }
        }
        throw new IllegalArgumentException("Invalid generic declaration '" + text + "'");
    }

    static List<String> splitTypes(String text) {
        if (text.isBlank()) {
            return List.of();
        }
        List<String> items = new ArrayList<>();
        int angles = 0;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            switch (text.charAt(i)) {
                case '<' -> angles++;
                case '>' -> angles--;
                case ',' -> {
                    if (angles == 0) {
                        items.add(required(text.substring(start, i), "List entry"));
                        start = i + 1;
                    }
                }
                default -> {
                }
            }
        }
        if (angles != 0) {
            throw new IllegalArgumentException("Unbalanced generic type list '" + text + "'");
        }
        items.add(required(text.substring(start), "List entry"));
        return List.copyOf(items);
    }

    private static Decorated decorations(Line line) {
        String text = line.text();
        EnumSet<Modifier> modifiers = EnumSet.noneOf(Modifier.class);
        if (line.italic()) {
            modifiers.add(Modifier.ABSTRACT);
        }
        if (line.underline()) {
            modifiers.add(Modifier.STATIC);
        }
        List<String> thrown = List.of();
        while (text.endsWith("}")) {
            int start = text.lastIndexOf('{');
            if (start < 0) {
                break;
            }
            String marker = text.substring(start + 1, text.length() - 1).strip();
            switch (marker) {
                case "static" -> modifiers.add(Modifier.STATIC);
                case "abstract" -> modifiers.add(Modifier.ABSTRACT);
                case "default" -> modifiers.add(Modifier.DEFAULT);
                case "sealed" -> modifiers.add(Modifier.SEALED);
                case "non-sealed" -> modifiers.add(Modifier.NON_SEALED);
                case "final" -> modifiers.add(Modifier.FINAL);
                default -> {
                    if (!marker.startsWith("throws ")) {
                        throw new IllegalArgumentException("Unsupported UML modifier '{" + marker + "}'");
                    }
                    thrown = splitTypes(marker.substring(7));
                }
            }
            text = text.substring(0, start).stripTrailing();
        }
        return new Decorated(text, modifiers, thrown);
    }

    private static NameWithVisibility visibility(String text, Visibility fallback) {
        for (Visibility visibility : Visibility.values()) {
            if (text.startsWith(visibility.marker())) {
                return new NameWithVisibility(text.substring(1).strip(), visibility);
            }
        }
        return new NameWithVisibility(text, fallback);
    }

    static boolean startsWithVisibility(String text) {
        return !text.isEmpty() && "+-#~".indexOf(text.charAt(0)) >= 0;
    }

    static boolean stereotype(String text) {
        return text.startsWith("«") || text.startsWith("<<");
    }

    private static String stereotypeName(String stereotype) {
        return stereotype.replace("«", "").replace("»", "").replace("<", "").replace(">", "")
                .strip().toLowerCase(Locale.ROOT);
    }

    private static Kind kind(String stereotype) {
        return switch (stereotypeName(stereotype)) {
            case "class" -> Kind.CLASS;
            case "interface" -> Kind.INTERFACE;
            case "enum", "enumeration" -> Kind.ENUM;
            case "record" -> Kind.RECORD;
            case "annotation" -> Kind.ANNOTATION;
            default -> throw new IllegalArgumentException("Unsupported type stereotype '" + stereotype + "'");
        };
    }

    static boolean visiblyMember(String text) {
        return startsWithVisibility(text) || text.contains(":") || text.contains("(");
    }

    private static boolean unclosed(String text) {
        return text.chars().filter(character -> character == '<').count()
                > text.chars().filter(character -> character == '>').count();
    }

    static boolean isJavaName(String name) {
        return SourceVersion.isName(name, SourceVersion.RELEASE_17)
                && name.codePoints().noneMatch(Character::isIdentifierIgnorable);
    }

    private static boolean isIdentifier(String name) {
        return !name.contains(".") && isJavaName(name);
    }

    static String identifier(String name) {
        if (!isIdentifier(name)) {
            throw new IllegalArgumentException("Invalid Java identifier '" + name + "'");
        }
        return name;
    }

    static String simpleName(String qualified) {
        return qualified.substring(qualified.lastIndexOf('.') + 1);
    }

    private static String required(String text, String label) {
        if (text.isBlank()) {
            throw new IllegalArgumentException(label + " is missing");
        }
        return text.strip();
    }

    private static String removeTerminator(String text) {
        return text.strip().endsWith(";") ? text.strip().substring(0, text.strip().length() - 1).strip() : text.strip();
    }

    private static List<String> properties(Cell cell, String key) {
        return new ArrayList<>(cell.properties().getOrDefault(key, "").lines().filter(line -> !line.isBlank()).toList());
    }

    private UmlLabels() {
    }
}
