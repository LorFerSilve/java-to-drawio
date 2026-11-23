package io.github.lorfersilve.javatodrawio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliOptionsTest {
    @Test
    void acceptsPathsWithSpacesAndFlagsInAnyOrder() {
        CliOptions options = CliOptions.parse(new String[] {
                "--force", "--output", "my diagrams/structure.DRAWIO", "--create-output-dir", "--overview",
                "--source", "Java Project"
        });

        assertEquals(Path.of("Java Project").toAbsolutePath(), options.source());
        assertEquals(Path.of("my diagrams/structure.DRAWIO").toAbsolutePath(), options.output());
        assertTrue(options.force());
        assertTrue(options.createOutputDir());
        assertTrue(options.overview());
    }

    @Test
    void overwriteIsOptIn() {
        assertFalse(CliOptions.parse(new String[] {"--source", ".", "--output", "a.drawio"}).force());
    }

    @Test
    void collectsLocalClasspathPathsInTheGivenOrder() {
        CliOptions options = CliOptions.parse(new String[] {
                "--source", ".", "--classpath", "my libraries/first.jar", "--classpath", "second.jar",
                "--output", "a.drawio"
        });
        assertEquals(List.of(Path.of("my libraries/first.jar").toAbsolutePath(),
                Path.of("second.jar").toAbsolutePath()), options.classpath());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "--wrong", "--help --force", "--output a.drawio", "--source .",
            "--source --output a.drawio", "--source . --output",
            "--source . --output a.java", "--source . --source src --output a.drawio",
            "--source . --output a.drawio --force --force",
            "--source . --output a.drawio --create-output-dir --create-output-dir",
            "--source . --output a.drawio --overview --overview", "--source . --output a.drawio --classpath"
    })
    void rejectsInvalidArgumentCombinations(String arguments) {
        assertThrows(IllegalArgumentException.class, () -> CliOptions.parse(arguments.split(" ")));
    }
}
