package io.github.lorfersilve.javatodrawio;

import io.github.lorfersilve.javatodrawio.DrawioLabel.Line;
import io.github.lorfersilve.javatodrawio.DrawioReader.Cell;
import io.github.lorfersilve.javatodrawio.DrawioReader.Page;
import io.github.lorfersilve.javatodrawio.UmlModel.Relationship;
import io.github.lorfersilve.javatodrawio.UmlModel.RelationshipKind;
import io.github.lorfersilve.javatodrawio.UmlModel.UmlType;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class DiagramModelReader {
    record Diagram(UmlModel model, Map<String, String> typeLocations, Map<Relationship, String> edgeLocations) {
    }

    private record TypeBox(UmlType type, String location, boolean compact) {
    }

    Diagram read(Path input) throws IOException {
        Map<String, TypeBox> types = new LinkedHashMap<>();
        Map<Relationship, String> edges = new LinkedHashMap<>();
        for (Page page : new DrawioReader().read(input)) {
            Map<String, List<Cell>> children = new LinkedHashMap<>();
            Map<String, List<Line>> labels = new LinkedHashMap<>();
            for (Cell cell : page.cells().values()) {
                if (cell.vertex()) {
                    children.computeIfAbsent(cell.parent(), ignored -> new ArrayList<>()).add(cell);
                    labels.put(cell.id(), DrawioLabel.lines(cell));
                }
            }

            Map<String, String> classes = new LinkedHashMap<>();
            for (Cell cell : page.cells().values()) {
                if (!cell.vertex() || !isClass(cell, labels.get(cell.id()), children.getOrDefault(cell.id(), List.of()))) {
                    continue;
                }
                String location = page.location(cell);
                try {
                    String enclosing = enclosingPackage(cell, page, labels);
                    boolean compact = cell.id().startsWith("type:")
                            && ("overview".equals(page.id()) || cell.parent().startsWith("package:"));
                    UmlType type = UmlLabels.readType(cell, labels.get(cell.id()), memberRows(cell, children,
                            labels, location), enclosing, compact);
                    TypeBox box = new TypeBox(type, location, compact);
                    TypeBox existing = types.get(type.qualifiedName());
                    if (existing == null || (existing.compact() && !compact)) {
                        types.put(type.qualifiedName(), box);
                    } else if (!existing.compact() && !compact && !existing.type().equals(type)) {
                        throw new IllegalArgumentException("Conflicting declarations for " + type.qualifiedName()
                                + " (also in " + existing.location() + ")");
                    }
                    classes.put(cell.id(), type.qualifiedName());
                } catch (IllegalArgumentException e) {
                    throw new IOException(location + ": " + e.getMessage(), e);
                }
            }
            readEdges(page, classes, edges);
        }
        if (types.isEmpty()) {
            throw new IOException("No UML class boxes found. Use a UML class shape or a class label with typed members.");
        }
        Map<String, String> locations = new LinkedHashMap<>();
        for (TypeBox box : types.values()) {
            if (box.compact()) {
                throw new IOException(box.location() + ": No detail box for " + box.type().qualifiedName());
            }
            locations.put(box.type().qualifiedName(), box.location());
        }
        return new Diagram(new UmlModel(types.values().stream().map(TypeBox::type).toList(),
                List.copyOf(edges.keySet())), locations, edges);
    }

    private static boolean isClass(Cell cell, List<Line> lines, List<Cell> children) {
        if (cell.id().startsWith("type:")) {
            return true;
        }
        if (lines.isEmpty() || cell.id().startsWith("package:") || cell.id().startsWith("fields:")
                || cell.id().startsWith("methods:") || cell.style().containsKey("text")
                || cell.style().containsKey("line") || cell.styled("shape", "note")) {
            return false;
        }
        String title = lines.get(0).text();
        if (title.startsWith("package ") || title.contains("(") || title.contains(":")) {
            return false;
        }
        boolean nativeBox = (cell.style().containsKey("swimlane") || cell.styled("shape", "swimlane"))
                && (cell.styled("childLayout", "stackLayout") || children.stream().anyMatch(row ->
                        row.style().containsKey("text") || row.styled("shape", "text")));
        return nativeBox || UmlLabels.stereotype(title) || UmlLabels.startsWithVisibility(title)
                || lines.stream().skip(1).anyMatch(line -> UmlLabels.visiblyMember(line.text()));
    }

    private static List<Cell> memberRows(Cell type, Map<String, List<Cell>> children, Map<String, List<Line>> labels,
                                         String location) {
        List<Cell> rows = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<Cell> next = new ArrayList<>(children.getOrDefault(type.id(), List.of()));
        seen.add(type.id());
        for (int index = 0; index < next.size(); index++) {
            Cell child = next.get(index);
            if (!seen.add(child.id())) {
                throw new IllegalArgumentException("Cyclic shape nesting near " + location);
            }
            if (!child.id().startsWith("type:")) {
                if (!labels.get(child.id()).isEmpty()) {
                    rows.add(child);
                }
                next.addAll(children.getOrDefault(child.id(), List.of()));
            }
        }
        return rows;
    }

    private static String enclosingPackage(Cell cell, Page page, Map<String, List<Line>> labels) {
        Set<String> seen = new HashSet<>();
        for (Cell parent = page.cells().get(cell.parent()); parent != null; parent = page.cells().get(parent.parent())) {
            if (!seen.add(parent.id())) {
                throw new IllegalArgumentException("Cyclic parent chain");
            }
            if (parent.id().equals("package:(default)")) {
                return "";
            }
            if (parent.id().startsWith("package:")) {
                return parent.id().substring("package:".length());
            }
            List<Line> lines = labels.getOrDefault(parent.id(), List.of());
            if (!lines.isEmpty() && lines.get(0).text().startsWith("package ")) {
                return lines.get(0).text().substring("package ".length()).strip();
            }
        }
        return "";
    }

    private static void readEdges(Page page, Map<String, String> classes, Map<Relationship, String> edges)
            throws IOException {
        for (Cell cell : page.cells().values()) {
            if (!cell.edge()) {
                continue;
            }
            boolean atStart = hollowTriangle(cell, "start");
            boolean atEnd = hollowTriangle(cell, "end");
            if (!atStart && !atEnd) {
                continue;
            }
            String from = owner(atStart ? cell.target() : cell.source(), page, classes);
            String to = owner(atStart ? cell.source() : cell.target(), page, classes);
            if (atStart && atEnd || from == null || to == null) {
                throw new IOException(page.location(cell)
                        + ": Connect the inheritance arrow between two UML classes with one triangle at the parent end");
            }
            if (from.equals(to)) {
                throw new IOException(page.location(cell) + ": A UML type cannot inherit from itself");
            }
            RelationshipKind kind = cell.styled("dashed", "1") ? RelationshipKind.REALIZATION
                    : RelationshipKind.GENERALIZATION;
            edges.putIfAbsent(new Relationship(from, to, kind), page.location(cell));
        }
    }

    private static boolean hollowTriangle(Cell cell, String end) {
        String arrow = cell.style().getOrDefault(end + "Arrow", "");
        return (arrow.equals("block") || arrow.equals("blockThin")) && cell.styled(end + "Fill", "0");
    }

    private static String owner(String id, Page page, Map<String, String> classes) {
        Set<String> seen = new LinkedHashSet<>();
        for (Cell cell = page.cells().get(id); cell != null && seen.add(cell.id());
             cell = page.cells().get(cell.parent())) {
            if (classes.containsKey(cell.id())) {
                return classes.get(cell.id());
            }
        }
        return null;
    }
}
