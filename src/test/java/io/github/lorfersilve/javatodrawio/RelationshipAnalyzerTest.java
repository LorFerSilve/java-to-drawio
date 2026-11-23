package io.github.lorfersilve.javatodrawio;

import io.github.lorfersilve.javatodrawio.UmlModel.Relationship;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static io.github.lorfersilve.javatodrawio.UmlModel.RelationshipKind.ASSOCIATION;
import static io.github.lorfersilve.javatodrawio.UmlModel.RelationshipKind.DEPENDENCY;
import static io.github.lorfersilve.javatodrawio.UmlModel.RelationshipKind.GENERALIZATION;
import static io.github.lorfersilve.javatodrawio.UmlModel.RelationshipKind.REALIZATION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RelationshipAnalyzerTest {
    @TempDir
    Path source;

    @Test
    void classifiesSignatureReferencesAndSuppressesRedundantDependencies() throws Exception {
        write("module a/src/main/java/Shape.java", """
                package one;
                public sealed interface Shape<T> extends Thing permits Geometry {}
                interface Thing {}
                """);
        write("module a/src/main/java/Geometry.java", """
                package one;
                import java.util.Map;
                import java.util.List;
                public final class Geometry extends Base<Point> implements Shape<Work> {
                    private Map<String, List<? super Point[]>> points;
                    private Base<?> base;
                    private Geometry next;
                    private Point previous, last[];
                    Geometry(Point previous) {}
                    public Geometry copy() { return this; }
                    <T extends Work> Point move(Work work) throws GeometryException { return null; }
                }
                class Base<T> {}
                class Point { Geometry owner; }
                class Work {}
                class GeometryException extends Exception {}
                record Dimension(Point point) implements Thing {}
                enum Venue implements Thing { HOME }
                """);
        var result = analyze();
        assertTrue(result.warnings().isEmpty(), result.warnings().toString());
        Set<Relationship> expected = Set.of(
                new Relationship("one.Dimension", "one.Point", ASSOCIATION),
                new Relationship("one.Dimension", "one.Thing", REALIZATION),
                new Relationship("one.Geometry", "one.Base", GENERALIZATION),
                new Relationship("one.Geometry", "one.Base", ASSOCIATION),
                new Relationship("one.Geometry", "one.Shape", REALIZATION),
                new Relationship("one.Geometry", "one.Point", ASSOCIATION),
                new Relationship("one.Geometry", "one.Geometry", ASSOCIATION),
                new Relationship("one.Geometry", "one.Work", DEPENDENCY),
                new Relationship("one.Geometry", "one.GeometryException", DEPENDENCY),
                new Relationship("one.Point", "one.Geometry", ASSOCIATION),
                new Relationship("one.Shape", "one.Thing", GENERALIZATION),
                new Relationship("one.Venue", "one.Thing", REALIZATION));
        assertEquals(expected, Set.copyOf(result.model().relationships()));
        assertEquals(expected.size(), result.model().relationships().size());
    }

    @Test
    void handlesQualifiedNamesTypeArgumentsAndInheritedAndImportedMemberTypes() throws Exception {
        write("arbitrary directory/ShapeDefinitions.java", """
                package gfx;
                public class ShapeDefinitions {
                    public interface Coordinates {}
                    public static class Point {}
                    public static class Marker {}
                    private static class Hidden {}
                }
                """);
        write("another src root/Aliases.java", """
                package clients;
                import gfx.ShapeDefinitions;
                import static gfx.ShapeDefinitions.Marker;
                import static more.More.*;
                import java.util.*;

                class Point {}
                class Widget {}
                class Hidden {}
                class Outer<T> { class Inner<U> {} }
                class Aliases extends ShapeDefinitions {
                    Point inherited;
                    Hidden localPackage;
                    Coordinates inheritedInterface;
                    Marker imported;
                    Mark wildcard;
                    Outer<Widget>.Inner<clients.Point> parameterizedOwner;
                    Map.Entry<gfx.ShapeDefinitions.Point, Widget> nestedPlatformType;
                    static Aliases.Point alias() { return null; }
                    <Widget> Widget shadow(Widget value) { return value; }
                }
                """);
        write("more/More.java", "package more; public class More { public static class Mark {} }");
        var result = analyze();
        assertTrue(result.warnings().isEmpty(), result.warnings().toString());
        assertEquals(Set.of(
                new Relationship("clients.Aliases", "gfx.ShapeDefinitions", GENERALIZATION),
                new Relationship("clients.Aliases", "gfx.ShapeDefinitions.Point", ASSOCIATION),
                new Relationship("clients.Aliases", "gfx.ShapeDefinitions.Coordinates", ASSOCIATION),
                new Relationship("clients.Aliases", "gfx.ShapeDefinitions.Marker", ASSOCIATION),
                new Relationship("clients.Aliases", "more.More.Mark", ASSOCIATION),
                new Relationship("clients.Aliases", "clients.Hidden", ASSOCIATION),
                new Relationship("clients.Aliases", "clients.Widget", ASSOCIATION),
                new Relationship("clients.Aliases", "clients.Outer.Inner", ASSOCIATION),
                new Relationship("clients.Aliases", "clients.Point", ASSOCIATION)),
                Set.copyOf(result.model().relationships()));
    }

    @Test
    void importsAndPackagesDisambiguateSameNamesWithoutGuessing() throws Exception {
        write("a/A.java", "package a; public class Thing {}");
        write("b/B.java", "package b; public class Thing {}");
        write("local/C.java", """
                package local;
                import b.Thing;
                import a.*;
                class Imported {
                    Thing imported;
                    a.Thing qualified;
                }
                """);
        write("local/Thing.java", "package local; class Thing {}");
        write("local/Same.java", "package local; import a.*; class Same { Thing field; }");
        write("unnamed/Default.java", "class Default { Other other; } class Other { Default field; }");

        var result = analyze();
        assertTrue(result.warnings().isEmpty(), result.warnings().toString());
        assertEquals(Set.of(
                new Relationship("local.Imported", "a.Thing", ASSOCIATION),
                new Relationship("local.Imported", "b.Thing", ASSOCIATION),
                new Relationship("local.Same", "local.Thing", ASSOCIATION),
                new Relationship("Default", "Other", ASSOCIATION),
                new Relationship("Other", "Default", ASSOCIATION)), Set.copyOf(result.model().relationships()));
    }

    @Test
    void warnsAboutAmbiguityAndUnknownImportsAndStillResolvesKnownGenericArguments() throws Exception {
        write("a/A.java", "package a; public class Status {}");
        write("b/B.java", "package b; public class Status {}");
        write("local/Status.java", "package local; class Status {}");
        Path explicit = write("local/Explicit.java", """
                package local;
                import external.Status;
                class Explicit { Status status; }
                """);
        Path user = write("User.java", """
                import a.*;
                import b.*;
                import nowhere.Container;
                class User<T> {
                    Status ambiguous;
                    Container<Good> field;
                    T typeVariable;
                    java.lang.String platform;
                    Missing value;
                }
                class Good {}
                """);
        write("T.java", "class T {}");
        var result = analyze();
        assertEquals(Set.of(new Relationship("User", "Good", ASSOCIATION)), Set.copyOf(result.model().relationships()));
        String diagnostics = String.join("\n", result.warnings());
        assertEquals(4, result.warnings().size(), diagnostics);
        assertTrue(diagnostics.contains(user + ":5:5: ambiguous type 'Status' (a.Status, b.Status)"), diagnostics);
        assertTrue(diagnostics.contains("cannot resolve type 'Container' from import nowhere.Container"), diagnostics);
        assertTrue(diagnostics.contains("cannot resolve type 'Missing'"), diagnostics);
        assertTrue(diagnostics.contains(explicit + ":3:18: cannot resolve type 'Status' from import external.Status"),
                diagnostics);
        assertFalse(diagnostics.contains("String"));
    }

    @Test
    void doesNotConfuseAnnotatedTypeArgumentsWithAnnotationValuesOrMethodBodies() throws Exception {
        write("Annot.java", """
                import java.lang.annotation.*;
                import java.util.List;
                @Target(ElementType.TYPE_USE)
                @interface Mark { Class<?> value(); }
                class A {
                    List<@Mark(NotAFieldType.class) Element> elements;
                    void method(A this) { new BodyType(); }
                }
                class NotAFieldType {}
                class Element {}
                class BodyType {}
                """);
        var result = analyze();
        assertTrue(result.warnings().isEmpty(), result.warnings().toString());
        assertEquals(Set.of(new Relationship("A", "Element", ASSOCIATION)), Set.copyOf(result.model().relationships()));
    }

    @Test
    void reportsInvalidHierarchyReferencesWithoutRecursingForever() throws Exception {
        write("Broken.java", """
                class First extends Second { Unavailable value; }
                class Second extends First { Unavailable other; }
                class Self extends Self {}
                class NotInterface implements First {}
                sealed interface Sealed permits Undeclared {}
                """);
        var result = analyze();
        assertEquals(Set.of(new Relationship("First", "Second", GENERALIZATION),
                new Relationship("Second", "First", GENERALIZATION)), Set.copyOf(result.model().relationships()));
        assertEquals(5, result.warnings().size(), result.warnings().toString());
        assertTrue(result.warnings().stream().anyMatch(warning -> warning.contains("invalid supertype 'Self'")));
        assertTrue(result.warnings().stream().anyMatch(warning -> warning.contains("invalid implemented type 'First'")));
        assertTrue(result.warnings().stream().anyMatch(warning -> warning.contains("'Undeclared'")));
    }

    private JavaAnalyzer.Result analyze() throws IOException, JavaAnalyzer.AnalysisException {
        return new JavaAnalyzer().analyze(SourceDiscovery.findJavaFiles(source));
    }

    private Path write(String name, String content) throws IOException {
        Path path = source.resolve(name);
        Files.createDirectories(path.getParent());
        return Files.writeString(path, content).toRealPath();
    }
}
