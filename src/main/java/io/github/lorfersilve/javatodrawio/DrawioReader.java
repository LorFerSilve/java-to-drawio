package io.github.lorfersilve.javatodrawio;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

final class DrawioReader {
    record Page(String id, String name, Map<String, Cell> cells) {
        String location(Cell cell) {
            return "Page '" + name + "', cell '" + cell.id() + "'";
        }
    }

    record Cell(String id, String label, String parent, String source, String target, boolean vertex, boolean edge,
                Map<String, String> style, Map<String, String> properties) {
        boolean styled(String key, String value) {
            return value.equals(style.get(key));
        }
    }

    // The limit applies to inflated, URL-encoded page text, before it becomes XML.
    private static final int MAX_INFLATED_PAGE = 64 * 1024 * 1024;

    List<Page> read(Path path) throws IOException {
        DocumentBuilder parser = xmlParser();
        try (InputStream input = Files.newInputStream(path)) {
            Element root = parse(parser, input).getDocumentElement();
            if ("mxGraphModel".equals(root.getTagName())) {
                return List.of(readPage("", path.getFileName().toString(), root));
            }
            if (!"mxfile".equals(root.getTagName())) {
                throw new IOException("Expected a Draw.io mxfile or mxGraphModel document");
            }
            List<Page> pages = new ArrayList<>();
            for (Element diagram : children(root, "diagram")) {
                String id = diagram.getAttribute("id");
                String name = diagram.getAttribute("name");
                if (name.isBlank()) {
                    name = "Page " + (pages.size() + 1);
                }
                try {
                    List<Element> models = children(diagram, "mxGraphModel");
                    if (models.size() > 1) {
                        throw new IOException("Expected one mxGraphModel");
                    }
                    Element model = models.isEmpty() ? inflatePage(parser, diagram.getTextContent()) : models.get(0);
                    pages.add(readPage(id, name, model));
                } catch (IOException e) {
                    throw new IOException("Page '" + name + "': " + e.getMessage(), e);
                }
            }
            if (pages.isEmpty()) {
                throw new IOException("The Draw.io file contains no diagram pages");
            }
            return List.copyOf(pages);
        }
    }

    private static Page readPage(String id, String name, Element model) throws IOException {
        List<Element> roots = children(model, "root");
        if (!"mxGraphModel".equals(model.getTagName()) || roots.size() != 1) {
            throw new IOException("Expected one root in the mxGraphModel");
        }
        Map<String, Cell> cells = new LinkedHashMap<>();
        for (Element element : children(roots.get(0), null)) {
            Element cell = element;
            String cellId = element.getAttribute("id");
            Map<String, String> properties = Map.of();
            if ("UserObject".equals(element.getTagName()) || "object".equals(element.getTagName())) {
                List<Element> wrapped = children(element, "mxCell");
                if (wrapped.size() != 1) {
                    throw new IOException("Expected one mxCell in object '" + cellId + "'");
                }
                cell = wrapped.get(0);
                properties = propertiesOf(element);
            } else if (!"mxCell".equals(element.getTagName())) {
                continue;
            }
            if (cellId.isBlank()) {
                throw new IOException("An mxCell has no ID");
            }
            String label = properties.getOrDefault("label", cell.getAttribute("value"));
            Cell decoded = new Cell(cellId, label, cell.getAttribute("parent"), cell.getAttribute("source"),
                    cell.getAttribute("target"), "1".equals(cell.getAttribute("vertex")),
                    "1".equals(cell.getAttribute("edge")), parseStyle(cell.getAttribute("style")), properties);
            if (cells.putIfAbsent(cellId, decoded) != null) {
                throw new IOException("Duplicate cell ID '" + cellId + "'");
            }
        }
        return new Page(id, name, Collections.unmodifiableMap(cells));
    }

    private static Map<String, String> parseStyle(String style) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String item : style.split(";")) {
            int equals = item.indexOf('=');
            if (equals > 0) {
                result.put(item.substring(0, equals), item.substring(equals + 1));
            } else if (!item.isEmpty()) {
                result.put(item, "1");
            }
        }
        return result;
    }

    private static Map<String, String> propertiesOf(Element object) {
        Map<String, String> properties = new LinkedHashMap<>();
        var attributes = object.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            var attribute = attributes.item(i);
            properties.put(attribute.getNodeName(), attribute.getNodeValue());
        }
        return properties;
    }

    private static Element inflatePage(DocumentBuilder parser, String text) throws IOException {
        Inflater inflater = new Inflater(true);
        try {
            byte[] compressed = Base64.getDecoder().decode(text.replaceAll("\\s", ""));
            try (var stream = new InflaterInputStream(new ByteArrayInputStream(compressed), inflater)) {
                byte[] encoded = stream.readNBytes(MAX_INFLATED_PAGE + 1);
                if (encoded.length > MAX_INFLATED_PAGE) {
                    throw new IOException("Inflated Draw.io page exceeds 64 MiB");
                }
                String xml = URLDecoder.decode(new String(encoded, StandardCharsets.UTF_8).replace("+", "%2B"),
                        StandardCharsets.UTF_8);
                return parse(parser, new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))).getDocumentElement();
            }
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid compressed Draw.io page: " + e.getMessage(), e);
        } finally {
            inflater.end();
        }
    }

    private static DocumentBuilder xmlParser() throws IOException {
        try {
            var factory = DocumentBuilderFactory.newDefaultInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler() {
                @Override
                public void error(SAXParseException e) throws SAXException {
                    throw e;
                }

                @Override
                public void fatalError(SAXParseException e) throws SAXException {
                    throw e;
                }
            });
            return builder;
        } catch (ParserConfigurationException e) {
            throw new IOException("Cannot configure the Draw.io XML reader", e);
        }
    }

    private static Document parse(DocumentBuilder parser, InputStream input) throws IOException {
        try {
            return parser.parse(input);
        } catch (SAXException e) {
            throw new IOException("Invalid Draw.io XML: " + e.getMessage(), e);
        }
    }

    private static List<Element> children(Element parent, String tag) {
        List<Element> result = new ArrayList<>();
        for (var child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && (tag == null || tag.equals(element.getTagName()))) {
                result.add(element);
            }
        }
        return result;
    }
}
