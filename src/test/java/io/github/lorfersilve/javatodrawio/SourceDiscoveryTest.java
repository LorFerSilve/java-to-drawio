package io.github.lorfersilve.javatodrawio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SourceDiscoveryTest {
    @TempDir
    Path source;

    @Test
    void discoversFilesAcrossRootsAndSkipsGeneratedDirectories() throws IOException {
        Path a = write("Gradle subproject/src/main/java/nested/A.java");
        Path b = write("Maven subproject/src/test/java/nested/B.java");
        Path c = write("ordinary sources/C.java");
        for (String ignored : List.of(".git", ".gradle", "build", "target", "out", "node_modules")) {
            write("Maven subproject/" + ignored + "/Ignored.java");
        }
        write("ordinary sources/U.java.bak");

        assertEquals(List.of(a, b, c), SourceDiscovery.findJavaFiles(source));
    }

    @Test
    void reportsEmptyMissingAndNonDirectorySources() throws IOException {
        var empty = assertThrows(IOException.class, () -> SourceDiscovery.findJavaFiles(source));
        assertTrue(empty.getMessage().contains("No .java files found"));
        var missing = assertThrows(IOException.class, () -> SourceDiscovery.findJavaFiles(source.resolve("missing")));
        assertTrue(missing.getMessage().contains("does not exist"));
        Path file = write("Example.java");
        var wrongType = assertThrows(IOException.class, () -> SourceDiscovery.findJavaFiles(file));
        assertTrue(wrongType.getMessage().contains("not a directory"));
    }

    @Test
    void doesNotFollowSymlinkLoopsOrSourceFileAliases() throws IOException {
        Path file = write("a/Original.java");
        try {
            Files.createSymbolicLink(source.resolve("a/loop"), source);
            Files.createSymbolicLink(source.resolve("Copy.java"), file);
        } catch (IOException | UnsupportedOperationException e) {
            assumeTrue(false, "Symbolic links are unavailable: " + e.getMessage());
        }
        assertEquals(List.of(file), SourceDiscovery.findJavaFiles(source));
    }

    private Path write(String name) throws IOException {
        Path path = source.resolve(name);
        Files.createDirectories(path.getParent());
        return Files.writeString(path, "class Example {}").toRealPath();
    }
}
