package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StandardInputTest {
    @Test
    void theSystemConsoleIsARecognizedTerminal(@TempDir Path directory) throws Exception {
        final Path descriptor = directory.resolve("fd");
        Files.createSymbolicLink(descriptor, Path.of("/dev/console"));
        assertThat(StandardInput.isTerminal(descriptor))
                .as("console input must never be mistaken for a secret-bearing pipe")
                .isTrue();
    }

    @Test
    void redirectedInputStaysUnattendedEvenWhenOutputHasATerminal() {
        final var input = new StandardInput(java.io.InputStream.nullInputStream(), false, true);
        assertThat(input.canPrompt())
                .as("a controlling terminal does not authorize reading redirected input")
                .isFalse();
    }
}
