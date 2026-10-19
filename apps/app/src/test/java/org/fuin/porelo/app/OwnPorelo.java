package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Starts a task's helpers as this build's own {@code porelo}, for the tests of a class.
 *
 * <p><strong>Not the installed one.</strong> Under a JVM, {@link PoreloBinary} found no {@code
 * porelo} of its own and took the one on the path: on a developer's machine the gate a test started
 * was the installed release, and in a Porelo task there was none, so the start failed. This writes
 * a launcher of the test's own class path, named {@code porelo} as the real one is, and names it
 * through {@value PoreloBinary#NAMED} while the class runs.
 */
final class OwnPorelo implements BeforeAllCallback, AfterAllCallback {

    private @org.jspecify.annotations.Nullable Path directory;

    @Override
    public void beforeAll(final ExtensionContext context) throws IOException {
        directory = Files.createTempDirectory("porelo-own-");
        final Path launcher = directory.resolve("porelo");
        final String java = ProcessHandle.current().info().command().orElse("java");
        Files.writeString(
                launcher,
                "#!/bin/sh\nexec '"
                        + java
                        + "' -cp '"
                        + System.getProperty("java.class.path")
                        + "' "
                        + PoreloCli.class.getName()
                        + " \"$@\"\n",
                StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(launcher, PosixFilePermissions.fromString("rwx------"));
        System.setProperty(PoreloBinary.NAMED, launcher.toString());
    }

    @Override
    public void afterAll(final ExtensionContext context) throws IOException {
        System.clearProperty(PoreloBinary.NAMED);
        if (directory != null) {
            Files.deleteIfExists(directory.resolve("porelo"));
            Files.deleteIfExists(directory);
        }
    }
}
