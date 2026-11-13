package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
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
 *
 * <p><strong>All but the clearance watcher.</strong> A watcher follows its events file until
 * something stops it: one whose pid file was not yet written when a test's teardown looked, or one
 * a test leaves running on purpose, outlived the JVM and never exited. The stand-in does what a
 * start waits for, making the socket a watcher listens on and recording its pid, as a {@link
 * HelperStandIn} stopped after each test.
 *
 * <p><strong>And but the gate.</strong> This build's {@code gate serve} served whatever project of
 * that name the account itself has; a start waits for its gate's pid, so the gate is a {@link
 * HelperStandIn}, stopped after each test.
 */
final class OwnPorelo implements BeforeAllCallback, AfterAllCallback, AfterEachCallback {

    /**
     * Returns the lines that answer {@code shield watch} by making its {@code --socket}, then
     * standing in as a {@link HelperStandIn} does.
     *
     * @param registry Where the stand-in records itself.
     * @param recordsItsPid Whether it writes its {@code --pid-file}.
     * @return The lines, which end the program for a watcher.
     */
    static String watcher(final Path registry, final boolean recordsItsPid) {
        return """
               if [ "$1" = shield ] && [ "$2" = watch ]; then
                 previous=
                 for argument; do
                   [ "$previous" = --socket ] && : > "$argument"
                   previous=$argument
                 done
               """
                + HelperStandIn.lines(registry, recordsItsPid)
                + "fi\n";
    }

    private @org.jspecify.annotations.Nullable Path directory;

    @Override
    public void beforeAll(final ExtensionContext context) throws IOException {
        directory = Files.createTempDirectory("porelo-own-");
        final Path launcher = directory.resolve("porelo");
        final String java = ProcessHandle.current().info().command().orElse("java");
        Files.writeString(
                launcher,
                "#!/bin/sh\n"
                        + watcher(HelperStandIn.registry(directory), true)
                        + "if [ \"$1\" = gate ] && [ \"$2\" = serve ]; then\n"
                        + HelperStandIn.lines(HelperStandIn.registry(directory), true)
                        + "fi\n"
                        + "exec '"
                        + java
                        + "' -D"
                        + org.fuin.porelo.core.config.RealAccount.REFUSED
                        + "=true -cp '"
                        + System.getProperty("java.class.path")
                        + "' "
                        + PoreloCli.class.getName()
                        + " \"$@\"\n",
                StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(launcher, PosixFilePermissions.fromString("rwx------"));
        System.setProperty(PoreloBinary.NAMED, launcher.toString());
    }

    @Override
    public void afterEach(final ExtensionContext context) throws IOException {
        if (directory != null) {
            HelperStandIn.stop(directory);
        }
    }

    @Override
    public void afterAll(final ExtensionContext context) throws IOException {
        System.clearProperty(PoreloBinary.NAMED);
        if (directory != null) {
            HelperStandIn.stop(directory);
            final Path registry = HelperStandIn.registry(directory);
            if (Files.isDirectory(registry)) {
                Files.delete(registry);
            }
            Files.deleteIfExists(directory.resolve("porelo"));
            Files.deleteIfExists(directory);
        }
    }
}
