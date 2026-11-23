package io.github.lorfersilve.javatodrawio;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;

final class JavaSourceOutput {
    static void write(Path root, Map<Path, String> sources) throws IOException {
        boolean exists = Files.exists(root, LinkOption.NOFOLLOW_LINKS);
        if (exists && !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Output is not a directory: " + root);
        }
        for (Path relative : sources.keySet()) {
            Path target = root.resolve(relative);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new FileAlreadyExistsException(target.toString(), null,
                        "Choose a different output directory to protect existing files");
            }
            checkParents(root, target.getParent());
        }

        Path stagingParent = exists ? root : root.getParent();
        Files.createDirectories(stagingParent);
        Path staging = Files.createTempDirectory(stagingParent, ".drawio-to-java-");
        try {
            for (var entry : sources.entrySet()) {
                Path file = staging.resolve(entry.getKey());
                Files.createDirectories(file.getParent());
                Files.writeString(file, entry.getValue(), StandardCharsets.UTF_8);
            }
            if (exists) {
                publishBatch(root, staging, sources);
            } else {
                Files.move(staging, root);
            }
        } finally {
            if (Files.isDirectory(staging, LinkOption.NOFOLLOW_LINKS)) {
                try (var paths = Files.walk(staging)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                        Files.deleteIfExists(path);
                    }
                }
            }
        }
    }

    private static void publishBatch(Path root, Path staging, Map<Path, String> sources) throws IOException {
        int published = 0;
        for (Path relative : sources.keySet()) {
            Path target = root.resolve(relative);
            try {
                checkParents(root, target.getParent());
                Files.createDirectories(target.getParent());
                OutputFile.publishNew(staging.resolve(relative), target);
                published++;
            } catch (IOException e) {
                throw new IOException("Published " + published + " of " + sources.size() + " Java files. Cannot write "
                        + target + " (" + e.getMessage() + ")", e);
            }
        }
    }

    private static void checkParents(Path root, Path directory) throws IOException {
        Path realRoot = Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) ? root.toRealPath() : null;
        for (Path parent = directory; !parent.equals(root); parent = parent.getParent()) {
            if (Files.exists(parent, LinkOption.NOFOLLOW_LINKS)
                    && (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)
                    || realRoot == null || !parent.toRealPath().equals(realRoot.resolve(root.relativize(parent))))) {
                throw new IOException("Output package path is not a plain directory: " + parent);
            }
        }
    }

    private JavaSourceOutput() {
    }
}
