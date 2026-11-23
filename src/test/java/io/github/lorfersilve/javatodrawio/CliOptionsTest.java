package io.github.lorfersilve.javatodrawio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliOptionsTest {
    @Test
    void acceptsPathsWithSpacesAndFlagsInAnyOrder() {
        CliOptions options = CliOptions.parse(new String[] {
                "--force", "--output", "my diagrams/structure.DRAWIO", "--source", "Java Project"
        });

        assertEquals(Path.of("Java Project").toAbsolutePath(), options.source());
        assertEquals(Path.of("my diagrams/structure.DRAWIO").toAbsolutePath(), options.output());
        assertTrue(options.force());
    }

    @Test
    void overwriteIsOptIn() {
        assertFalse(CliOptions.parse(new String[] {"--source", ".", "--output", "a.drawio"}).force());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "--wrong", "--help --force", "--output a.drawio", "--source .",
            "--source --output a.drawio", "--source . --output",
            "--source . --output a.java", "--source . --source src --output a.drawio",
            "--source . --output a.drawio --force --force"
    })
    void rejectsInvalidArgumentCombinations(String arguments) {
        assertThrows(IllegalArgumentException.class, () -> CliOptions.parse(arguments.split(" ")));
    }
}
