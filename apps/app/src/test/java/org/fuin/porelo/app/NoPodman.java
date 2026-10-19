package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.fuin.porelo.runtime.Podman;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Names a podman that holds nothing, for what {@link Podman} starts itself, while a class runs.
 *
 * <p>{@link NoTasks} answers what goes through a test's runner. {@link Podman#execute} starts its
 * process itself, so without this a unit test ran the developer's real {@code podman exec} against
 * a container that does not exist, and failed to start anything in a Porelo task, which has no
 * podman. The stand-in answers as {@link NoTasks} does: a listing empty, anything else 125.
 */
final class NoPodman implements BeforeAllCallback, AfterAllCallback {

    private @Nullable Path directory;

    @Override
    public void beforeAll(final ExtensionContext context) throws IOException {
        directory = Files.createTempDirectory("porelo-no-podman-");
        final Path podman = directory.resolve("podman");
        Files.writeString(
                podman,
                """
                #!/bin/sh
                case "$1" in ps | images) exit 0 ;; esac
                [ "${2:-}" = ls ] && exit 0
                echo "Error: no such object" >&2
                exit %d
                """
                        .formatted(NoTasks.NO_SUCH),
                StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(podman, PosixFilePermissions.fromString("rwx------"));
        System.setProperty(Podman.NAMED, podman.toString());
    }

    @Override
    public void afterAll(final ExtensionContext context) throws IOException {
        System.clearProperty(Podman.NAMED);
        if (directory != null) {
            Files.deleteIfExists(directory.resolve("podman"));
            Files.deleteIfExists(directory);
        }
    }
}
