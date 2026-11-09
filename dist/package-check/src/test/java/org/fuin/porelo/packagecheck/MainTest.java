package org.fuin.porelo.packagecheck;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MainTest {

    @TempDir Path root;

    @Test
    void refusesAMissingHostPackageBeforeReadingPackages() throws IOException {
        // A porelo deb must not stand in for the separate host package.
        final Path deb = Files.createDirectories(root.resolve(Main.DEB));
        Files.createFile(deb.resolve("porelo_1_amd64.deb"));
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        final int exit =
                Main.run(root, false, new PrintStream(bytes, true, StandardCharsets.UTF_8));

        assertThat(exit)
                .as("missing packages stop the check before external tools run")
                .isEqualTo(2);
        assertThat(bytes.toString(StandardCharsets.UTF_8))
                .as("the host package is required beside porelo")
                .contains("no porelo-host_*.deb under " + deb);
    }

    @Test
    void refusesAmbiguousHostPackagesBeforeReadingPackages() throws IOException {
        // Picking an older package can pass checks on bytes that will not be published.
        final Path deb = Files.createDirectories(root.resolve(Main.DEB));
        Files.createFile(deb.resolve("porelo_1_amd64.deb"));
        Files.createFile(deb.resolve("porelo-host_1_amd64.deb"));
        Files.createFile(deb.resolve("porelo-host_2_amd64.deb"));
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        final int exit =
                Main.run(root, false, new PrintStream(bytes, true, StandardCharsets.UTF_8));

        assertThat(exit)
                .as("ambiguous packages stop the check before external tools run")
                .isEqualTo(2);
        assertThat(bytes.toString(StandardCharsets.UTF_8))
                .as("no arbitrary host version is selected")
                .contains("2 packages match porelo-host_*.deb under " + deb);
    }
}
