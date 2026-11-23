package io.github.lorfersilve.javatodrawio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

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
        for (String option : List.of("--source", "--output", "--force", "--help")) {
            assertTrue(help.output().contains(option), help.output());
        }
        Invocation unknown = run("--unknown");
        unknown.assertExit(2);
        assertTrue(unknown.output().contains("Unexpected argument --unknown"));
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
        Path output = temp.resolve("diagram.drawio");
        Invocation error = convert(source, output);
        error.assertExit(3);
        assertTrue(error.output().contains("DominoBoardView.java:2:20: Parse error: add ';' after the package name."),
                error.output());
        assertTrue(error.output().contains("  package game.domino" + System.lineSeparator() + " ".repeat(21) + "^"),
                error.output());
        assertFalse(Files.exists(output));
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
