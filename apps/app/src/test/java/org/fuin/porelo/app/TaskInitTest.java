package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TaskInitTest {

    @Test
    void aPathWithoutTheInitRefusesBeforePodmanCreatesAnything(@TempDir Path dir)
            throws IOException {
        // A missing init must not leave an image and a half-created task behind.
        assertThat(TaskInit.missing(new ProcessCommandRunner(), List.of(), dir.toString()))
                .as("the missing program and its installable package must be named")
                .hasValueSatisfying(reason -> assertThat(reason).contains("catatonit", "package"));

        final Path init = Files.writeString(dir.resolve("catatonit"), "#!/bin/sh\nexit 99\n");
        Files.setPosixFilePermissions(
                init, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        assertThat(TaskInit.missing(new ProcessCommandRunner(), List.of(), dir.toString()))
                .as(
                        "an executable init on the supplied PATH must permit a start without running it")
                .isEmpty();
        assertThat(TaskInit.missing(new ProcessCommandRunner(), List.of(init), ""))
                .as("the default init path must work even outside PATH")
                .isEmpty();
        assertThat(
                        TaskInit.missing(
                                new ProcessCommandRunner(),
                                List.of(dir.resolve("absent")),
                                dir.toString()))
                .as("an absent default must allow PATH lookup")
                .isEmpty();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void aNonExecutableOrDirectoryCannotStandInForInit(boolean directory, @TempDir Path dir)
            throws IOException {
        // A name that cannot run otherwise passes preflight and fails after task preparation.
        final Path init = dir.resolve("catatonit");
        if (directory) {
            Files.createDirectory(init);
        } else {
            Files.writeString(init, "not executable");
        }
        assertThat(TaskInit.missing(new ProcessCommandRunner(), List.of(), dir.toString()))
                .as("only an executable file can serve as the init")
                .isPresent();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void aBrokenDefaultInitCannotBeHiddenByAnExecutableOnPath(boolean directory, @TempDir Path dir)
            throws IOException {
        // Podman selects an existing legacy path before PATH, so falling through fails too late.
        final Path legacy = dir.resolve("legacy");
        if (directory) {
            Files.createDirectory(legacy);
        } else {
            Files.writeString(legacy, "not executable");
        }
        final Path init = Files.writeString(dir.resolve("catatonit"), "#!/bin/sh\nexit 99\n");
        Files.setPosixFilePermissions(
                init, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));

        assertThat(TaskInit.missing(new ProcessCommandRunner(), List.of(legacy), dir.toString()))
                .as("an unusable default must refuse even when PATH has another init")
                .isPresent();
    }
}
