package io.github.lorfersilve.javatodrawio;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;

final class OutputFile {
    static void validate(Path target, boolean force, boolean createOutputDir) throws IOException {
        Path directory = target.getParent();
        try {
            if (!Files.readAttributes(directory, BasicFileAttributes.class).isDirectory()) {
                throw new IOException("Output parent is not a directory: " + directory);
            }
        } catch (NoSuchFileException e) {
            if (createOutputDir) {
                return;
            }
            throw new IOException("Output directory does not exist: " + directory
                    + ". Use --create-output-dir to create it.", e);
        }
        try {
            var attributes = Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile()) {
                throw new IOException("Output path is not a regular file: " + target);
            }
            if (!force) {
                throw alreadyExists(target);
            }
        } catch (NoSuchFileException e) {
            // The destination is available.
        }
    }

    static void write(Path target, UmlModel model, boolean force, boolean overview) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), ".java-to-drawio-", ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(temporary)) {
                new DrawioWriter().write(model, output, overview);
            }
            if (force) {
                replace(temporary, target);
            } else {
                publishNew(temporary, target);
            }
        } catch (FileAlreadyExistsException e) {
            throw alreadyExists(target);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void replace(Path temporary, Path target) throws IOException {
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void publishNew(Path temporary, Path target) throws IOException {
        try {
            // Linking publishes a complete file and fails atomically if a competing writer wins.
            Files.createLink(target, temporary);
        } catch (FileAlreadyExistsException e) {
            throw e;
        } catch (UnsupportedOperationException | FileSystemException e) {
            // Providers without hard links can still move within the destination directory.
            Files.move(temporary, target);
        }
    }

    private static FileAlreadyExistsException alreadyExists(Path target) {
        return new FileAlreadyExistsException(target.toString(), null, "Output file exists. Use --force to replace it");
    }

    private OutputFile() {
    }
}
