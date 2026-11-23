package io.github.lorfersilve.javatodrawio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DrawioWriterTest {
    @TempDir
    Path temp;

    @Test
    void rendersEmptyModelAsAnEditablePage() throws Exception {
        ByteArrayOutputStream xml = new ByteArrayOutputStream();
        new DrawioWriter().write(new UmlModel(List.of(), List.of()), xml, false);
        DrawioDocument diagram = DrawioDocument.read(new ByteArrayInputStream(xml.toByteArray()));
        assertEquals(2, diagram.cells().size());
        assertTrue(diagram.classes().isEmpty());
    }

    @Test
    void publishesOnlyOneOutputWhenTwoWritersRaceWithoutForce() throws Exception {
        Path destination = temp.resolve("shared output.drawio");
        var executor = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        Callable<Boolean> write = () -> {
            start.await();
            try {
                OutputFile.write(destination, new UmlModel(List.of(), List.of()), false, false);
                return true;
            } catch (FileAlreadyExistsException e) {
                return false;
            }
        };
        try {
            var first = executor.submit(write);
            var second = executor.submit(write);
            start.countDown();
            assertTrue(first.get(10, TimeUnit.SECONDS) ^ second.get(10, TimeUnit.SECONDS));
            assertEquals(2, DrawioDocument.read(destination).cells().size());
            try (var files = Files.list(temp)) {
                assertEquals(List.of(destination), files.toList());
            }
        } finally {
            executor.shutdownNow();
        }
    }
}
