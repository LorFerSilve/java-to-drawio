package io.github.lorfersilve.javatodrawio;

import io.github.lorfersilve.javatodrawio.JavaAnalyzer.AnalysisException;
import io.github.lorfersilve.javatodrawio.UmlModel.Field;
import io.github.lorfersilve.javatodrawio.UmlModel.Kind;
import io.github.lorfersilve.javatodrawio.UmlModel.Modifier;
import io.github.lorfersilve.javatodrawio.UmlModel.Operation;
import io.github.lorfersilve.javatodrawio.UmlModel.Parameter;
import io.github.lorfersilve.javatodrawio.UmlModel.Visibility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaAnalyzerTest {
    @TempDir
    Path source;

    @Test
    void extractsDeclarationsAndSignaturesFromUnusualButValidJava() throws Exception {
        write("source root with spaces/unrelated-layout.java", """
                /* Single-line and multiline comments may precede the package. */
                package demo;
                import java.util.List;
                import java.util.Map;
                // Comments can also appear between modifiers and names.
                /** Documentation with <T> and a class Invisible {}. */
                @Deprecated abstract /* comment */ class
                Catalog<T extends Comparable<T> & java.io.Serializable> {
                    private Map<String, List<? extends T[]>> items;
                    protected int a[], b;
                    static String description;
                    Catalog() { }
                    public   <R extends T> List<R> find(@Deprecated R... keys)
                            throws java.io.IOException {
                        String text = \"""
                                class StringContent {}
                                \""";
                        Runnable f = () -> { class Local {} };
                        Object anonymous = new Object() { class AnonymousMember {} };
                        return List.of();
                    }
                    protected abstract T first();
                    private native void nativeMethod();
                    private class Cursor { record Position(int row) {} }
                }
                interface Repository {
                    int DEFAULT_SIZE = 0;
                    Object get();
                    default Object getOther() { return null; }
                    private void clear() {}
                    static void reset() {}
                    class Entry {}
                }
                enum State {
                    ACTIVE, CLOSED;
                    State() {}
                }
                record Entry(String id, int... values) {
                    Entry { if (values.length == 0) throw new IllegalArgumentException(); }
                }
                sealed interface Shape permits Circle, Polygon {}
                final class Circle implements Shape {}
                non-sealed class Polygon implements Shape {}
                @interface Note { String text() default ""; }
                """);

        JavaAnalyzer.Result result = analyze();
        assertEquals(1, result.fileCount());
        assertEquals(List.of("demo.Catalog", "demo.Catalog.Cursor", "demo.Catalog.Cursor.Position", "demo.Circle",
                "demo.Entry", "demo.Note", "demo.Polygon", "demo.Repository", "demo.Repository.Entry",
                "demo.Shape", "demo.State"), result.model().types().stream().map(UmlModel.UmlType::qualifiedName).toList());
        var catalog = result.model().types().get(0);
        assertEquals(Kind.CLASS, catalog.kind());
        assertEquals(Visibility.PACKAGE, catalog.visibility());
        assertEquals(Set.of(Modifier.ABSTRACT), catalog.modifiers());
        assertEquals(List.of("T extends Comparable<T> & java.io.Serializable"), catalog.typeParameters());
        assertEquals(List.of(new Field("items", "Map<String,List<? extends T[]>>", Visibility.PRIVATE, false),
                new Field("a", "int[]", Visibility.PROTECTED, false), new Field("b", "int", Visibility.PROTECTED, false),
                new Field("description", "String", Visibility.PACKAGE, true)), catalog.fields());
        assertEquals(new Operation("Catalog", Optional.empty(), Visibility.PACKAGE,
                Set.of(), List.of(), List.of(), List.of()), catalog.operations().get(0));
        assertEquals(new Operation("find", Optional.of("List<R>"), Visibility.PUBLIC, Set.of(),
                List.of("R extends T"), List.of(new Parameter("keys", "R...")), List.of("java.io.IOException")),
                catalog.operations().get(1));
        assertTrue(catalog.operations().get(2).modifiers().contains(Modifier.ABSTRACT));
        assertFalse(catalog.operations().get(3).modifiers().contains(Modifier.ABSTRACT));
        assertEquals(Visibility.PRIVATE, result.model().types().get(1).visibility());
        assertEquals(Set.of(Modifier.STATIC), result.model().types().get(2).modifiers());

        var record = result.model().types().get(4);
        assertEquals(Kind.RECORD, record.kind());
        assertEquals(List.of(new Field("id", "String", Visibility.PRIVATE, false),
                new Field("values", "int[]", Visibility.PRIVATE, false)), record.fields());
        assertEquals(List.of(new Parameter("id", "String"), new Parameter("values", "int...")),
                record.operations().get(0).parameters());
        assertEquals(Kind.ANNOTATION, result.model().types().get(5).kind());
        assertEquals(Set.of(Modifier.NON_SEALED), result.model().types().get(6).modifiers());
        var repository = result.model().types().get(7);
        assertEquals(Kind.INTERFACE, repository.kind());
        assertEquals(new Field("DEFAULT_SIZE", "int", Visibility.PUBLIC, true), repository.fields().get(0));
        assertEquals(Visibility.PUBLIC, repository.operations().get(0).visibility());
        assertEquals(Set.of(Modifier.ABSTRACT), repository.operations().get(0).modifiers());
        assertEquals(Set.of(), repository.operations().get(1).modifiers());
        assertEquals(Visibility.PRIVATE, repository.operations().get(2).visibility());
        assertEquals(Set.of(Modifier.STATIC), repository.operations().get(3).modifiers());
        assertEquals(Visibility.PUBLIC, result.model().types().get(8).visibility());
        assertTrue(result.model().types().get(9).modifiers().contains(Modifier.SEALED));
        var enumeration = result.model().types().get(10);
        assertEquals(Kind.ENUM, enumeration.kind());
        assertEquals(new Field("ACTIVE", "State", Visibility.PUBLIC, true), enumeration.fields().get(0));
        assertEquals(Visibility.PRIVATE, enumeration.operations().get(0).visibility());
    }

    @Test
    void acceptsFilesWithoutTypesAndDecodesUnicodeEscapesAndBom() throws Exception {
        write("module-info.java", "/* comment */ open module demo.application { requires java.logging; }");
        write("src/demo/package-info.java", "/** Package docs. */ @Deprecated package demo;");
        write("src/only-comments.java", "/** No types here. */");
        write("src/with-bom.java", "\uFEFF// prefix\ncl" + "\\u0061" + "ss École { int café; }");

        JavaAnalyzer.Result result = analyze();
        assertEquals(4, result.fileCount());
        assertEquals(1, result.model().types().size());
        assertEquals("École", result.model().types().get(0).qualifiedName());
        assertEquals("café", result.model().types().get(0).fields().get(0).name());
        assertTrue(result.warnings().isEmpty());
    }

    @Test
    void warnsWhenValidFilesDeclareNoTypes() throws Exception {
        write("module-info.java", "module numbers {}");
        var result = analyze();
        assertTrue(result.model().types().isEmpty());
        assertEquals(1, result.warnings().size());
        assertTrue(result.warnings().get(0).contains("No top-level or member types"));
    }

    @Test
    void includesPathsForAllBrokenSourceFiles() throws Exception {
        write("Fine.java", "class Fine {}");
        Path malformed = write("Broken path/Malformed.java", "class Malformed { void test( }");
        Path unsupported = write("Newer.java", "class Newer { String text = STR.\"test\"; }");
        Path encoding = Files.write(source.resolve("Encoding.java"), new byte[] {(byte) 0xC3, 0x28}).toRealPath();

        var failure = assertThrows(AnalysisException.class, this::analyze);
        assertTrue(failure.getMessage().contains(malformed.toString()), failure.getMessage());
        assertTrue(failure.getMessage().contains(unsupported.toString()), failure.getMessage());
        assertTrue(failure.getMessage().contains(encoding + ": invalid UTF-8"), failure.getMessage());
    }

    @Test
    void diagnosesDuplicateQualifiedNamesAcrossRoots() throws Exception {
        Path first = write("module-one/src/main/java/Foo.java", "package same; class Foo {}");
        Path second = write("module-two/src/main/java/Foo.java", "package same; class Foo {}");
        var failure = assertThrows(AnalysisException.class, this::analyze);
        assertTrue(failure.getMessage().contains("duplicate type same.Foo"), failure.getMessage());
        assertTrue(failure.getMessage().contains(first.toString()), failure.getMessage());
        assertTrue(failure.getMessage().contains(second.toString()), failure.getMessage());
    }

    private JavaAnalyzer.Result analyze() throws IOException, AnalysisException {
        return new JavaAnalyzer().analyze(SourceDiscovery.findJavaFiles(source));
    }

    private Path write(String name, String text) throws IOException {
        Path path = source.resolve(name);
        Files.createDirectories(path.getParent());
        return Files.writeString(path, text).toRealPath();
    }
}
