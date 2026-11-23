package io.github.lorfersilve.javatodrawio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class ConverterIT {
    @TempDir
    Path temp;

    private record Invocation(int exitCode, String output) {
        void assertExit(int expected) {
            assertEquals(expected, exitCode, output);
        }
    }

    @Test
    void packagesAnExecutableJarWithHelpAndArgumentValidation() throws Exception {
        Invocation help = run("--help");
        help.assertExit(0);
        for (String option : List.of("--source", "--output", "--create-output-dir", "--overview", "--classpath",
                "--force", "--help")) {
            assertTrue(help.output().contains(option), help.output());
        }
        Invocation unknown = run("--unknown");
        unknown.assertExit(2);
        assertTrue(unknown.output().contains("Unexpected argument --unknown"));
    }

    @Test
    void createsACompactOverviewAndFullDetailPagesGroupedByPackage() throws Exception {
        Path output = temp.resolve("catalog overview.drawio");
        Path fixture = fixture();
        run("--source", fixture.toString(), "--output", output.toString(), "--overview").assertExit(0);
        var pages = DrawioDocument.readPages(output);
        assertEquals(List.of("Overview", "sample.audit", "sample.catalog", "sample.service"),
                pages.stream().map(DrawioDocument::name).toList());

        DrawioDocument overview = pages.get(0);
        assertEquals(11, overview.classes().size());
        assertEquals(13, overview.edges().size());
        assertTrue(overview.cells().keySet().stream().noneMatch(id -> id.startsWith("fields:") || id.startsWith("methods:")));
        assertEquals("package:sample.catalog", overview.cell("type:sample.catalog.Product").getAttribute("parent"));
        assertEquals("package:sample.audit", overview.cell("type:sample.audit.Product").getAttribute("parent"));
        assertEdge(overview, "sample.service.CatalogService", "sample.audit.Product", "dependency",
                "endArrow=open;endFill=0;dashed=1;");
        for (DrawioDocument page : pages) {
            page.assertNoClassOverlap();
        }

        DrawioDocument catalog = pages.get(2);
        assertEquals(5, catalog.classes().size());
        assertEquals(6, catalog.edges().size());
        assertTrue(catalog.cell("methods:sample.catalog.Product").getAttribute("value").contains("displayName(): String"));
        assertEquals("1", catalog.cell("type:sample.catalog.Product").getAttribute("parent"));
        assertTrue(pages.get(3).cell("methods:sample.service.CatalogService").getAttribute("value")
                .contains("+ export(product: Product): sample.audit.Product"));

        Path repeated = temp.resolve("repeated overview.drawio");
        run("--source", fixture.toString(), "--output", repeated.toString(), "--overview").assertExit(0);
        assertEquals(-1L, Files.mismatch(output, repeated));
    }

    @Test
    void includesTheUnnamedPackageInOverviewMode() throws Exception {
        Path source = Files.createDirectory(temp.resolve("plain sources"));
        Files.writeString(source.resolve("Example.java"), "class Example { int id; }");
        Path output = temp.resolve("unnamed.drawio");
        run("--source", source.toString(), "--output", output.toString(), "--overview").assertExit(0);
        var pages = DrawioDocument.readPages(output);
        assertEquals(List.of("Overview", "(default package)"), pages.stream().map(DrawioDocument::name).toList());
        assertEquals("package:", pages.get(0).cell("type:Example").getAttribute("parent"));
        assertTrue(pages.get(1).cell("fields:Example").getAttribute("value").contains("id: int"));
    }

    @Test
    void convertsAJava17ProjectAcrossSourceRootsToEditableUml() throws Exception {
        Path destination = temp.resolve("Catalog architecture.drawio");
        Path fixture = fixture();
        Invocation result = run("--source", fixture.toString(), "--output", destination.toString());
        result.assertExit(0);
        assertTrue(result.output().contains("Processed 8 Java files, 11 UML types, 13 relationships, 0 warnings."),
                result.output());
        assertTrue(result.output().contains(destination.toString()), result.output());

        var diagram = DrawioDocument.read(destination);
        assertEquals(11, diagram.classes().size());
        assertEquals(13, diagram.edges().size());
        diagram.assertNoClassOverlap();
        assertEquals("type:sample.service.CatalogService",
                diagram.cell("fields:sample.service.CatalogService").getAttribute("parent"));
        assertTrue(diagram.cell("fields:sample.service.CatalogService").getAttribute("value")
                .contains("- repository: Repository&lt;I&gt;"));
        assertTrue(diagram.cell("fields:sample.service.CatalogService").getAttribute("value")
                .contains("Map&lt;String,List&lt;? extends I[]&gt;&gt;"));
        String operations = diagram.cell("methods:sample.service.CatalogService").getAttribute("value");
        assertTrue(operations.contains("~ CatalogService(repository: Repository&lt;I&gt;)"), operations);
        assertTrue(operations.contains("<u># &lt;T extends Item &amp; Serializable&gt; find(candidates: T...): List&lt;T&gt;"),
                operations);
        assertTrue(operations.contains("+ export(product: Product): sample.audit.Product"), operations);
        assertTrue(diagram.cell("type:sample.catalog.Item").getAttribute("value").contains("«interface»"));
        assertTrue(diagram.cell("type:sample.catalog.StockItem").getAttribute("value").contains("<i>"));
        assertTrue(diagram.cell("type:sample.catalog.Item.Availability").getAttribute("value").contains("«enumeration»"));
        assertTrue(diagram.cell("type:sample.catalog.Bundle").getAttribute("value").contains("«record»"));
        assertTrue(diagram.cell("type:sample.service.Reviewed").getAttribute("value").contains("«annotation»"));
        assertNotEquals(diagram.cell("type:sample.catalog.Product").getAttribute("value"),
                diagram.cell("type:sample.audit.Product").getAttribute("value"));
        assertTrue(Files.readString(destination).contains("Item &amp;amp; Serializable"));

        assertEdge(diagram, "sample.catalog.Product", "sample.catalog.StockItem", "generalization",
                "endArrow=block;endFill=0;dashed=0;");
        assertEdge(diagram, "sample.catalog.Product", "sample.catalog.Item", "realization",
                "endArrow=block;endFill=0;dashed=1;");
        assertEdge(diagram, "sample.catalog.Product", "sample.catalog.Product", "association",
                "endArrow=none;dashed=0;");
        assertEdge(diagram, "sample.service.CatalogService", "sample.audit.Product", "dependency",
                "endArrow=open;endFill=0;dashed=1;");
    }

    @Test
    void emitsIdenticalXmlAcrossProcessesAndOnlyReplacesExistingFilesWithForce() throws Exception {
        Path original = temp.resolve("original.drawio");
        Path repeated = temp.resolve("repeated.drawio");
        convert(fixture(), original).assertExit(0);
        convert(fixture(), repeated).assertExit(0);
        assertEquals(-1L, Files.mismatch(original, repeated));

        Files.writeString(repeated, "my manual edits");
        Invocation blocked = convert(fixture(), repeated);
        blocked.assertExit(4);
        assertTrue(blocked.output().contains("--force"), blocked.output());
        assertEquals("my manual edits", Files.readString(repeated));
        run("--force", "--source", fixture().toString(), "--output", repeated.toString()).assertExit(0);
        assertEquals(-1L, Files.mismatch(original, repeated));
    }

    @Test
    void diagnosesMalformedInputAndDoesNotPublishOrReplaceOutput() throws Exception {
        Path source = Files.createDirectory(temp.resolve("broken project"));
        Files.writeString(source.resolve("Good.java"), "class Good {}");
        Path broken = Files.writeString(source.resolve("Broken File.java"), "class Broken { void foo( }");
        Path destination = temp.resolve("architecture.drawio");
        Invocation error = convert(source, destination);
        error.assertExit(3);
        assertTrue(error.output().contains(broken.getFileName().toString()), error.output());
        assertTrue(error.output().contains("Broken File.java:1:26: Parse error: unexpected"), error.output());
        assertTrue(error.output().contains("class Broken { void foo( }"), error.output());
        assertTrue(error.output().contains("^"), error.output());
        assertFalse(Files.exists(destination));

        Files.writeString(destination, "preserve me");
        run("--source", source.toString(), "--output", destination.toString(), "--force").assertExit(3);
        assertEquals("preserve me", Files.readString(destination));
        try (var files = Files.list(temp)) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }

    @Test
    void pointsToTheMissingPackageSemicolonAfterLeadingComments() throws Exception {
        Path source = Files.createDirectory(temp.resolve("domino project"));
        Files.writeString(source.resolve("DominoBoardView.java"), """
                /* A leading comment stays valid. */
                package game.domino
                /** Shows the board. */
                public class DominoBoardView {}
                """);
        Path output = temp.resolve("unused output/diagram.drawio");
        Invocation error = run("--source", source.toString(), "--output", output.toString(), "--create-output-dir");
        error.assertExit(3);
        assertTrue(error.output().contains("DominoBoardView.java:2:20: Parse error: add ';' after the package name."),
                error.output());
        assertTrue(error.output().contains("  package game.domino" + System.lineSeparator() + " ".repeat(21) + "^"),
                error.output());
        assertFalse(Files.exists(output.getParent()));
    }

    @Test
    void createsMissingOutputDirectoriesWhenRequestedAndStillProtectsFiles() throws Exception {
        Path output = temp.resolve("my diagrams/architecture/overview.drawio");
        String[] args = {"--source", fixture().toString(), "--output", output.toString(), "--create-output-dir"};
        run(args).assertExit(0);
        assertEquals(11, DrawioDocument.read(output).classes().size());
        Files.writeString(output, "manual changes");
        run(args).assertExit(4);
        assertEquals("manual changes", Files.readString(output));
    }

    @Test
    void reportsMissingEmptyAndInvalidLocations() throws Exception {
        Path empty = Files.createDirectory(temp.resolve("empty project"));
        Path output = temp.resolve("output.drawio");
        Invocation missing = convert(temp.resolve("absent project"), output);
        missing.assertExit(2);
        assertTrue(missing.output().contains("Source directory does not exist"), missing.output());
        Invocation noSources = convert(empty, output);
        noSources.assertExit(2);
        assertTrue(noSources.output().contains("No .java files found"), noSources.output());
        Invocation absentParent = convert(fixture(), temp.resolve("absent/output.drawio"));
        absentParent.assertExit(4);
        assertTrue(absentParent.output().contains("Output directory does not exist"), absentParent.output());
        Files.createDirectory(output);
        Invocation directory = run("--force", "--source", fixture().toString(), "--output", output.toString());
        directory.assertExit(4);
        assertTrue(directory.output().contains("not a regular file"), directory.output());
    }

    @Test
    void warnsWhenDependenciesAreUnavailableWhileKeepingKnownTypesAndEdges() throws Exception {
        Path source = Files.createDirectory(temp.resolve("src"));
        Files.writeString(source.resolve("Sample.java"), """
                /* This project needs an external library to compile. */
                import external.Bundle;
                class Sample { Bundle<Entry> values; }
                record Entry(String name) {}
                """);
        Path output = temp.resolve("result.drawio");
        Invocation invocation = convert(source, output);
        invocation.assertExit(0);
        assertTrue(invocation.output().contains("Sample.java:3:"), invocation.output());
        assertTrue(invocation.output().contains("cannot resolve type 'Bundle' from import external.Bundle"),
                invocation.output());
        assertTrue(invocation.output().contains("2 UML types, 1 relationships, 1 warnings."), invocation.output());
        assertEdge(DrawioDocument.read(output), "Sample", "Entry", "association", "endArrow=none;dashed=0;");
    }

    @Test
    void resolvesLocalLibraryTypesAcrossJarsWithoutAddingThemToTheDiagram() throws Exception {
        Path base = compileLibrary("base library.jar", null, Map.of(
                "api/Base.java", """
                        package api;
                        public class Base {
                            static {
                                if (System.getProperty("java.version") != null) throw new AssertionError();
                            }
                            public static class Entry {}
                            public class Inner {}
                        }
                        """,
                "api/Literal$Type.java", "package api; public class Literal$Type {}"));
        Path adapter = compileLibrary("adapter library.JAR", base, Map.of(
                "adapter/Adapter.java", "package adapter; public class Adapter extends api.Base {}"));
        Path source = Files.createDirectory(temp.resolve("library client"));
        Files.writeString(source.resolve("Client.java"), """
                package demo;
                import adapter.Adapter;
                import api.Base;
                import api.Literal$Type;
                import static adapter.Adapter.Entry;
                import static api.Base.*;
                import project.*;
                class Client extends Adapter {
                    Entry entry;
                    Base.Inner inner;
                    Literal$Type type;
                    Node process(Node node) { return node; }
                }
                class Helper { Inner field; }
                """);
        Files.writeString(source.resolve("Node.java"), "package project; public class Node {}");
        Files.writeString(source.resolve("Inner.java"), "package project; public class Inner {}");

        Invocation withoutClasspath = convert(source, temp.resolve("without classpath.drawio"));
        withoutClasspath.assertExit(0);
        assertTrue(withoutClasspath.output().contains("cannot resolve type 'Adapter'"), withoutClasspath.output());

        Path output = temp.resolve("with libraries.drawio");
        Invocation resolved = run("--source", source.toString(), "--output", output.toString(),
                "--classpath", adapter.toString(), "--classpath", base.toString());
        resolved.assertExit(0);
        assertTrue(resolved.output().contains("4 UML types, 2 relationships, 0 warnings."), resolved.output());
        DrawioDocument diagram = DrawioDocument.read(output);
        assertEquals(4, diagram.classes().size());
        assertEquals(2, diagram.edges().size());
        assertEdge(diagram, "demo.Client", "project.Node", "dependency", "endArrow=open;endFill=0;dashed=1;");
        assertEdge(diagram, "demo.Helper", "project.Inner", "association", "endArrow=none;dashed=0;");
    }

    @Test
    void rejectsMissingAndInvalidClasspathJarsBeforeCreatingOutput() throws Exception {
        Path invalid = Files.writeString(temp.resolve("invalid archive.jar"), "plain text");
        for (Path jar : List.of(temp.resolve("missing.jar"), invalid, temp.toAbsolutePath().getRoot())) {
            Path output = temp.resolve("uncreated/output.drawio");
            Invocation error = run("--source", fixture().toString(), "--output", output.toString(),
                    "--create-output-dir", "--classpath", jar.toString());
            error.assertExit(2);
            assertTrue(error.output().contains("Classpath error"), error.output());
            assertTrue(error.output().contains(jar.toString()), error.output());
            assertFalse(Files.exists(output.getParent()));
        }
    }

    @Test
    void reportsMalformedClassMetadataInAClasspathJar() throws Exception {
        Path library = temp.resolve("broken classes.jar");
        try (var jar = new JarOutputStream(Files.newOutputStream(library))) {
            jar.putNextEntry(new JarEntry("api/Invalid.class"));
            jar.write(new byte[] {1, 2, 3});
            jar.closeEntry();
        }
        Path source = Files.createDirectory(temp.resolve("src"));
        Files.writeString(source.resolve("Client.java"), "class Client { api.Invalid field; }");
        Path output = temp.resolve("diagram.drawio");
        Invocation error = run("--source", source.toString(), "--output", output.toString(),
                "--classpath", library.toString());
        error.assertExit(3);
        assertTrue(error.output().contains("Cannot read class api.Invalid from " + library), error.output());
        assertFalse(Files.exists(output));
    }

    @Test
    void usesJavaIdentifierEqualityAndWritesXmlForUnusualUnicodeIdentifiers() throws Exception {
        Path source = Files.createDirectory(temp.resolve("unicode project"));
        String supplementary = new String(Character.toChars(0x10400));
        Files.writeString(source.resolve("Example.java"), """
                package sample%s;
                class Cata%slog { sample.Catalog next; %sType type; }
                class %sType {}
                """.formatted((char) 1, (char) 0x200D, supplementary, supplementary));
        Path output = temp.resolve("unicode.drawio");
        Invocation result = convert(source, output);
        result.assertExit(0);
        assertTrue(result.output().contains("2 UML types, 2 relationships, 0 warnings."), result.output());
        var diagram = DrawioDocument.read(output);
        assertEdge(diagram, "sample.Catalog", "sample.Catalog", "association", "endArrow=none;dashed=0;");
        assertEdge(diagram, "sample.Catalog", "sample." + supplementary + "Type", "association",
                "endArrow=none;dashed=0;");
    }

    @Test
    void laysOutHundredsOfClassesWithContentSizedBounds() throws Exception {
        Path source = Files.createDirectory(temp.resolve("large project"));
        int types = 240;
        for (int i = 0; i < types; i++) {
            int next = (i + 1) % types;
            String text = """
                    package applications.area%d;
                    import java.util.Map;
                    import java.util.List;
                    class Type%d {
                        private List<? extends applications.area%d.Type%d[]> next;
                        Map<String, List<List<Map<String, List<? super Type%d>>>>> longOperation(
                                String exceptionallyLongParameterNameForDimensionTesting, Type%d other) { return null; }
                    }
                    """.formatted(i % 6, i, next % 6, next, i, i);
            Files.writeString(source.resolve("Type" + i + ".java"), text);
        }
        Path output = temp.resolve("large.drawio");
        Invocation result = convert(source, output);
        result.assertExit(0);
        assertTrue(result.output().contains("240 Java files, 240 UML types, 240 relationships, 0 warnings."),
                result.output());
        var diagram = DrawioDocument.read(output);
        assertEquals(types, diagram.classes().size());
        assertEquals(types, diagram.edges().size());
        assertTrue(DrawioDocument.number(diagram.cell("methods:applications.area0.Type0"), "height") > 26);
        diagram.assertNoClassOverlap();
    }

    private static void assertEdge(DrawioDocument diagram, String source, String target, String kind, String style) {
        var edge = diagram.cell("edge:" + source + ">" + target + ":" + kind);
        assertEquals("type:" + source, edge.getAttribute("source"));
        assertEquals("type:" + target, edge.getAttribute("target"));
        assertTrue(edge.getAttribute("style").contains(style), edge.getAttribute("style"));
    }

    private Path fixture() throws Exception {
        return Path.of(getClass().getResource("/fixtures/Catalog project").toURI());
    }

    private Path compileLibrary(String name, Path dependency, Map<String, String> sources) throws Exception {
        Path root = Files.createTempDirectory(temp, "library sources ");
        Path classes = Files.createDirectory(root.resolve("compiled"));
        List<String> arguments = new ArrayList<>(List.of("--release", "17", "-d", classes.toString()));
        if (dependency != null) {
            arguments.addAll(List.of("-classpath", dependency.toString()));
        }
        for (var source : sources.entrySet()) {
            Path file = root.resolve(source.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue());
            arguments.add(file.toString());
        }
        var report = new ByteArrayOutputStream();
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, report, report, arguments.toArray(String[]::new)),
                report::toString);

        Path output = temp.resolve(name);
        try (var jar = new JarOutputStream(Files.newOutputStream(output)); var files = Files.walk(classes)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String entry = classes.relativize(file).toString().replace('\\', '/');
                jar.putNextEntry(new JarEntry(entry));
                Files.copy(file, jar);
                jar.closeEntry();
            }
        }
        return output;
    }

    private Invocation convert(Path source, Path output) throws Exception {
        return run("--source", source.toString(), "--output", output.toString());
    }

    private Invocation run(String... arguments) throws Exception {
        Path bin = Path.of(System.getProperty("java.home"), "bin");
        Path java = Files.exists(bin.resolve("java.exe")) ? bin.resolve("java.exe") : bin.resolve("java");
        Path jar = Path.of(System.getProperty("converter.jar"));
        assertTrue(Files.isRegularFile(jar), "Packaged JAR missing: " + jar);
        List<String> command = new ArrayList<>(List.of(java.toString(), "-Dfile.encoding=UTF-8", "-jar", jar.toString()));
        command.addAll(List.of(arguments));
        Path log = Files.createTempFile(temp, "converter-", ".txt");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!process.waitFor(45, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("Converter timed out: " + Files.readString(log));
        }
        return new Invocation(process.exitValue(), Files.readString(log));
    }
}
