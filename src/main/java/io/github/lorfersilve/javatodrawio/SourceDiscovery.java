package io.github.lorfersilve.javatodrawio;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

final class SourceDiscovery {
    private static final Set<String> IGNORED_DIRECTORIES = Set.of(
            ".git", ".gradle", "target", "build", "out", "node_modules");

    static List<Path> findJavaFiles(Path source) throws IOException {
        Path root;
        try {
            root = source.toRealPath();
        } catch (NoSuchFileException e) {
            throw new IOException("Source directory does not exist: " + source, e);
        }
        if (!Files.readAttributes(root, BasicFileAttributes.class).isDirectory()) {
            throw new IOException("Source path is not a directory: " + source);
        }

        List<Path> files = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) throws IOException {
                if (!dir.equals(root) && IGNORED_DIRECTORIES.contains(dir.getFileName().toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                // Windows directory junctions can be visited even without FOLLOW_LINKS.
                return dir.equals(dir.toRealPath()) ? FileVisitResult.CONTINUE : FileVisitResult.SKIP_SUBTREE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (attributes.isRegularFile() && file.getFileName().toString().endsWith(".java")) {
                    files.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        if (files.isEmpty()) {
            throw new IOException("No .java files found in " + source);
        }
        files.sort(Comparator.naturalOrder());
        return files;
    }

    private SourceDiscovery() {
    }
}
