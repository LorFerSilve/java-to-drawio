package io.github.lorfersilve.javatodrawio;

import io.github.lorfersilve.javatodrawio.DiagramLayout.Box;
import io.github.lorfersilve.javatodrawio.DiagramLayout.Grid;
import io.github.lorfersilve.javatodrawio.DiagramLayout.Route;
import io.github.lorfersilve.javatodrawio.DiagramLayout.Size;
import io.github.lorfersilve.javatodrawio.UmlModel.Modifier;
import io.github.lorfersilve.javatodrawio.UmlModel.Operation;
import io.github.lorfersilve.javatodrawio.UmlModel.Relationship;
import io.github.lorfersilve.javatodrawio.UmlModel.UmlType;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

final class DrawioWriter {
    private static final int MAX_COLUMNS = 90;
    private static final int LINE_HEIGHT = 18;
    private static final String FONT = "fontSize=12;fontFamily=monospace;";
    private static final String COMPARTMENT_STYLE = "text;html=1;whiteSpace=wrap;align=left;verticalAlign=top;"
            + "spacing=0;spacingTop=4;spacingLeft=10;spacingRight=10;fillColor=none;strokeColor=none;rotatable=0;" + FONT;

    private record Pair(String first, String second) {
        static Pair of(Relationship edge) {
            return edge.source().compareTo(edge.target()) <= 0
                    ? new Pair(edge.source(), edge.target()) : new Pair(edge.target(), edge.source());
        }
    }

    private record Caption(List<String> lines, String html) {
        int height() {
            return Math.max(1, lines.size()) * LINE_HEIGHT + 8;
        }
    }

    private record ClassLabels(Caption header, Caption fields, Caption methods) {
        Size size() {
            int longest = Stream.of(header, fields, methods).flatMap(caption -> caption.lines().stream())
                    .mapToInt(String::length).max().orElse(0);
            return new Size(Math.max(184, longest * 8 + 28),
                    header.height() + fields.height() + methods.height() + 1);
        }
    }

    void write(UmlModel model, OutputStream output) throws IOException {
        try {
            Document document = DocumentBuilderFactory.newDefaultInstance().newDocumentBuilder().newDocument();
            Element file = document.createElement("mxfile");
            file.setAttribute("compressed", "false");
            document.appendChild(file);
            Element page = element(file, "diagram", "id", "uml", "name", "UML Class Diagram");
            Element graph = element(page, "mxGraphModel", "grid", "1", "gridSize", "10", "page", "0");
            Element root = element(graph, "root");
            element(root, "mxCell", "id", "0");
            element(root, "mxCell", "id", "1", "parent", "0");

            Map<String, ClassLabels> labels = new HashMap<>();
            Map<String, Size> sizes = new HashMap<>();
            for (UmlType type : model.types()) {
                ClassLabels boxLabels = labelsFor(type);
                labels.put(type.qualifiedName(), boxLabels);
                sizes.put(type.qualifiedName(), boxLabels.size());
            }
            Grid grid = DiagramLayout.place(sizes, model.relationships());
            writeEdges(root, model.relationships(), grid);
            for (UmlType type : model.types()) {
                drawType(root, type, labels.get(type.qualifiedName()), grid.boxes().get(type.qualifiedName()));
            }

            var serializer = TransformerFactory.newDefaultInstance().newTransformer();
            serializer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            serializer.setOutputProperty(OutputKeys.INDENT, "yes");
            serializer.transform(new DOMSource(document), new StreamResult(output));
        } catch (ParserConfigurationException | TransformerException e) {
            throw new IOException("Cannot write Draw.io XML: " + e.getMessage(), e);
        }
    }

    private static void drawType(Element root, UmlType type, ClassLabels labels, Box box) {
        String id = "type:" + type.qualifiedName();
        String colors = colors(type);
        String style = "swimlane;html=1;whiteSpace=wrap;rounded=0;horizontal=1;collapsible=0;"
                + "childLayout=stackLayout;horizontalStack=0;resizeParent=0;resizeLast=1;container=1;dropTarget=0;"
                + "align=center;verticalAlign=middle;swimlaneFillColor=#ffffff;spacing=0;"
                + "startSize=" + labels.header().height() + ";" + FONT + colors;
        Element vertex = vertex(root, id, "1", labels.header().html(), style);
        geometry(vertex, box.x(), box.y(), box.size().width(), box.size().height());

        int width = box.size().width();
        int fieldStart = labels.header().height();
        int dividerY = fieldStart + labels.fields().height();
        compartment(root, "fields:" + type.qualifiedName(), id, labels.fields(), fieldStart, width);
        Element divider = vertex(root, "divider:" + type.qualifiedName(), id, "",
                "line;strokeWidth=1;editable=0;rotatable=0;" + colors);
        divider.setAttribute("connectable", "0");
        geometry(divider, 0, dividerY, width, 1);
        compartment(root, "methods:" + type.qualifiedName(), id, labels.methods(), dividerY + 1, width);
    }

    private static void compartment(Element root, String id, String owner, Caption caption, int y, int width) {
        Element cell = vertex(root, id, owner, caption.html(), COMPARTMENT_STYLE);
        cell.setAttribute("connectable", "0");
        geometry(cell, 0, y, width, caption.height());
    }

    private static void writeEdges(Element root, List<Relationship> relationships, Grid grid) {
        Map<Pair, Integer> totals = new HashMap<>();
        for (Relationship edge : relationships) {
            totals.merge(Pair.of(edge), 1, Integer::sum);
        }
        Map<Pair, Integer> used = new HashMap<>();
        for (Relationship edge : relationships) {
            Pair pair = Pair.of(edge);
            Route route = grid.route(edge, used.merge(pair, 1, Integer::sum) - 1, totals.get(pair));
            String id = "edge:" + edge.source() + ">" + edge.target() + ":"
                    + edge.kind().name().toLowerCase(Locale.ROOT);
            String style = "edgeStyle=orthogonalEdgeStyle;orthogonalLoop=1;rounded=0;jettySize=auto;"
                    + "startArrow=none;endSize=12;strokeColor=#475569;strokeWidth=1.4;"
                    + "exitX=" + route.exit().x() + ";exitY=" + route.exit().y() + ";"
                    + "entryX=" + route.entry().x() + ";entryY=" + route.entry().y() + ";";
            if (edge.source().equals(edge.target())) {
                style += "loopStyle=loopEdgeStyle;direction=east;segment=36;";
            }
            style += switch (edge.kind()) {
                case GENERALIZATION -> "endArrow=block;endFill=0;dashed=0;";
                case REALIZATION -> "endArrow=block;endFill=0;dashed=1;";
                case ASSOCIATION -> "endArrow=none;dashed=0;";
                case DEPENDENCY -> "endArrow=open;endFill=0;dashed=1;";
            };
            Element cell = element(root, "mxCell", "id", id, "style", style, "edge", "1", "parent", "1",
                    "source", "type:" + edge.source(), "target", "type:" + edge.target());
            Element geometry = element(cell, "mxGeometry", "relative", "1", "as", "geometry");
            if (!route.points().isEmpty()) {
                Element points = element(geometry, "Array", "as", "points");
                for (var point : route.points()) {
                    element(points, "mxPoint", "x", Integer.toString(point.x()), "y", Integer.toString(point.y()));
                }
            }
        }
    }

    private static ClassLabels labelsFor(UmlType type) {
        LabelBuilder header = new LabelBuilder();
        String stereotype = switch (type.kind()) {
            case CLASS -> "";
            case INTERFACE -> "«interface»";
            case ENUM -> "«enumeration»";
            case RECORD -> "«record»";
            case ANNOTATION -> "«annotation»";
        };
        if (!stereotype.isEmpty()) {
            header.add(stereotype, false, false, false);
        }
        String name = type.visibility().marker() + " " + type.displayName() + typeParameters(type.typeParameters());
        if (type.modifiers().contains(Modifier.SEALED)) {
            name += " {sealed}";
        } else if (type.modifiers().contains(Modifier.NON_SEALED)) {
            name += " {non-sealed}";
        }
        header.add(name, true, type.modifiers().contains(Modifier.ABSTRACT),
                type.modifiers().contains(Modifier.STATIC));
        if (!type.packageName().isEmpty()) {
            header.add(type.packageName(), false, false, false);
        }
        LabelBuilder fields = new LabelBuilder();
        type.fields().forEach(field -> fields.add(field.visibility().marker() + " " + field.name() + ": " + field.type(),
                false, false, field.isStatic()));
        LabelBuilder methods = new LabelBuilder();
        type.operations().forEach(operation -> methods.add(signature(operation), false,
                operation.modifiers().contains(Modifier.ABSTRACT), operation.modifiers().contains(Modifier.STATIC)));
        return new ClassLabels(header.caption(), fields.caption(), methods.caption());
    }

    private static String signature(Operation operation) {
        String parameters = operation.parameters().stream()
                .map(parameter -> parameter.name() + ": " + parameter.type()).collect(Collectors.joining(", "));
        String generics = typeParameters(operation.typeParameters());
        if (!generics.isEmpty()) {
            generics += " ";
        }
        String returnType = operation.returnType().map(type -> ": " + type).orElse("");
        String throwsClause = operation.thrownTypes().isEmpty() ? ""
                : " {throws " + String.join(", ", operation.thrownTypes()) + "}";
        return operation.visibility().marker() + " " + generics + operation.name()
                + "(" + parameters + ")" + returnType + throwsClause;
    }

    private static String typeParameters(List<String> parameters) {
        return parameters.isEmpty() ? "" : "<" + String.join(", ", parameters) + ">";
    }

    private static String colors(UmlType type) {
        return switch (type.kind()) {
            case CLASS -> type.modifiers().contains(Modifier.ABSTRACT) ? "fillColor=#ede9fe;strokeColor=#7962a8;"
                    : "fillColor=#dbeafe;strokeColor=#5478a9;";
            case INTERFACE -> "fillColor=#dcfce7;strokeColor=#52866a;";
            case ENUM -> "fillColor=#fef3c7;strokeColor=#9d773a;";
            case RECORD -> "fillColor=#ffe4e6;strokeColor=#a46872;";
            case ANNOTATION -> "fillColor=#f1f5f9;strokeColor=#64748b;";
        };
    }

    private static final class LabelBuilder {
        private final List<String> lines = new ArrayList<>();
        private final List<String> html = new ArrayList<>();

        void add(String text, boolean bold, boolean italic, boolean underlined) {
            for (String line : wrap(text)) {
                lines.add(line);
                String value = line.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
                if (bold) {
                    value = "<b>" + value + "</b>";
                }
                if (italic) {
                    value = "<i>" + value + "</i>";
                }
                if (underlined) {
                    value = "<u>" + value + "</u>";
                }
                html.add(value);
            }
        }

        Caption caption() {
            return new Caption(List.copyOf(lines), html.isEmpty() ? ""
                    : "<div style=\"line-height:18px\">" + String.join("<br>", html) + "</div>");
        }

        private static List<String> wrap(String text) {
            List<String> wrapped = new ArrayList<>();
            while (text.length() > MAX_COLUMNS) {
                int cut = MAX_COLUMNS;
                for (int i = MAX_COLUMNS; i > MAX_COLUMNS * 2 / 3; i--) {
                    if (Character.isWhitespace(text.charAt(i - 1)) || text.charAt(i - 1) == ',') {
                        cut = i;
                        break;
                    }
                }
                if (Character.isHighSurrogate(text.charAt(cut - 1))) {
                    cut--;
                }
                wrapped.add(text.substring(0, cut).stripTrailing());
                text = text.substring(cut).stripLeading();
            }
            wrapped.add(text);
            return wrapped;
        }
    }

    private static Element vertex(Element root, String id, String parent, String label, String style) {
        return element(root, "mxCell", "id", id, "value", label, "style", style, "vertex", "1", "parent", parent);
    }

    private static void geometry(Element cell, int x, int y, int width, int height) {
        element(cell, "mxGeometry", "x", Integer.toString(x), "y", Integer.toString(y), "width",
                Integer.toString(width), "height", Integer.toString(height), "as", "geometry");
    }

    private static Element element(Element parent, String name, String... attributes) {
        Element element = parent.getOwnerDocument().createElement(name);
        for (int i = 0; i < attributes.length; i += 2) {
            element.setAttribute(attributes[i], attributes[i + 1]);
        }
        parent.appendChild(element);
        return element;
    }
}
