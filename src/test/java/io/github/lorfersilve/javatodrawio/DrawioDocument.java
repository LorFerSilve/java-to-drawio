package io.github.lorfersilve.javatodrawio;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

record DrawioDocument(String name, Map<String, Element> cells) {
    static DrawioDocument read(Path file) throws Exception {
        try (InputStream input = Files.newInputStream(file)) {
            return read(input);
        }
    }

    static DrawioDocument read(InputStream input) throws Exception {
        List<DrawioDocument> pages = readPages(input);
        assertEquals(1, pages.size());
        return pages.get(0);
    }

    static List<DrawioDocument> readPages(Path path) throws Exception {
        try (InputStream input = Files.newInputStream(path)) {
            return readPages(input);
        }
    }

    private static List<DrawioDocument> readPages(InputStream input) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newDefaultInstance();
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        Element file = factory.newDocumentBuilder().parse(input).getDocumentElement();
        assertEquals("mxfile", file.getTagName());
        assertEquals("false", file.getAttribute("compressed"));
        var ids = new HashSet<String>();
        return elements(file.getChildNodes()).stream().map(diagram -> {
            assertEquals("diagram", diagram.getTagName());
            String id = diagram.getAttribute("id");
            assertTrue(!id.isBlank() && ids.add(id), "Missing or duplicate page ID " + id);
            return readPage(diagram);
        }).toList();
    }

    private static DrawioDocument readPage(Element diagram) {
        Element graph = onlyChild(diagram, "mxGraphModel");
        Element root = onlyChild(graph, "root");
        Map<String, Element> cells = new LinkedHashMap<>();
        for (Element cell : elements(root.getElementsByTagName("mxCell"))) {
            Element parent = (Element) cell.getParentNode();
            if ("UserObject".equals(parent.getTagName())) {
                cell.setAttribute("id", parent.getAttribute("id"));
                cell.setAttribute("value", parent.getAttribute("label"));
            }
            String id = cell.getAttribute("id");
            assertTrue(!id.isBlank(), "Missing cell ID");
            assertNull(cells.put(id, cell), "Duplicate cell ID " + id);
        }
        assertNotNull(cells.get("0"), "Missing graph root");
        assertNotNull(cells.get("1"), "Missing layer");
        assertEquals("0", cells.get("1").getAttribute("parent"));
        var document = new DrawioDocument(diagram.getAttribute("name"), cells);
        for (Element cell : cells.values()) {
            String parent = cell.getAttribute("parent");
            if (!parent.isEmpty()) {
                assertTrue(cells.containsKey(parent), "Unknown parent " + parent);
            }
            if ("1".equals(cell.getAttribute("vertex"))) {
                assertTrue(number(cell, "width") > 0 && number(cell, "height") > 0);
            }
        }
        for (Element edge : document.edges()) {
            for (String terminal : List.of("source", "target")) {
                Element vertex = cells.get(edge.getAttribute(terminal));
                assertNotNull(vertex, "Unknown edge " + terminal);
                assertEquals("1", vertex.getAttribute("vertex"));
                assertTrue(vertex.getAttribute("id").startsWith("type:"));
            }
            assertEquals("1", onlyChild(edge, "mxGeometry").getAttribute("relative"));
        }
        return document;
    }

    Element cell(String id) {
        Element cell = cells.get(id);
        assertNotNull(cell, "Missing cell " + id);
        return cell;
    }

    List<Element> classes() {
        return cells.values().stream()
                .filter(cell -> "1".equals(cell.getAttribute("vertex")) && cell.getAttribute("id").startsWith("type:"))
                .toList();
    }

    List<Element> edges() {
        return cells.values().stream().filter(cell -> "1".equals(cell.getAttribute("edge"))).toList();
    }

    void assertNoClassOverlap() {
        var shapes = classes();
        for (int i = 0; i < shapes.size(); i++) {
            Element left = shapes.get(i);
            for (int j = i + 1; j < shapes.size(); j++) {
                Element right = shapes.get(j);
                boolean overlap = position(left, "x") < position(right, "x") + number(right, "width")
                        && position(right, "x") < position(left, "x") + number(left, "width")
                        && position(left, "y") < position(right, "y") + number(right, "height")
                        && position(right, "y") < position(left, "y") + number(left, "height");
                assertTrue(!overlap, () -> left.getAttribute("id") + " overlaps " + right.getAttribute("id"));
            }
        }
    }

    private int position(Element cell, String axis) {
        String parent = cell.getAttribute("parent");
        return number(cell, axis) + (parent.startsWith("package:") ? number(cell(parent), axis) : 0);
    }

    static int number(Element cell, String attribute) {
        Element geometry = onlyChild(cell, "mxGeometry");
        assertEquals("geometry", geometry.getAttribute("as"));
        return Integer.parseInt(geometry.getAttribute(attribute));
    }

    private static Element onlyChild(Element parent, String tag) {
        List<Element> matches = elements(parent.getChildNodes()).stream()
                .filter(element -> tag.equals(element.getTagName())).toList();
        assertEquals(1, matches.size(), parent.getTagName() + " must contain one " + tag);
        return matches.get(0);
    }

    private static List<Element> elements(NodeList nodes) {
        return IntStream.range(0, nodes.getLength()).mapToObj(nodes::item)
                .filter(Element.class::isInstance).map(Element.class::cast).toList();
    }
}
