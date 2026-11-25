package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.gate.review.CommentReading;
import org.fuin.porelo.gate.review.ForgeInstance;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A machine's own parts of the answer read a pull request through its forge's adapter. */
class ReviewOpenPartsTest {

    @TempDir Path dir;

    private PoreloContext context() {
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                                    default -> null;
                                },
                        dir);
        return new PoreloContext(
                new FakeCommandRunner(), new PoreloPaths(xdg, dir.resolve("bin")), arguments -> 0);
    }

    private static void installed(final PoreloContext context, final String name)
            throws IOException {
        final Path builds = Files.createDirectories(context.paths().xdg().data().resolve("builds"));
        final Path adapter = builds.resolve(name);
        Files.writeString(adapter, "#!/bin/sh\nexit 0\n");
        Files.setPosixFilePermissions(adapter, PosixFilePermissions.fromString("rwx------"));
    }

    @Test
    void aKnownForgeWhoseAdapterIsInstalledIsReadThroughIt() throws IOException {
        final PoreloContext context = context();
        installed(context, "github");

        assertThat(
                        ReviewOpen.Parts.of(context)
                                .adapters()
                                .of(
                                        new ForgeInstance(
                                                "github.com",
                                                "github",
                                                CommentReading.EXTENDED,
                                                null)))
                .as("the machine's reader of a pull request there")
                .isPresent();
    }

    @Test
    void aForgeWhoseAdapterIsNotInstalledHasNoReader() {
        // A name no package installs, so the machine's own adapters cannot answer for it.
        final PoreloContext context = context();

        assertThat(
                        ReviewOpen.Parts.of(context)
                                .adapters()
                                .of(
                                        new ForgeInstance(
                                                "forge.stub.invalid",
                                                "stand-in-forge-nobody-packages",
                                                CommentReading.EXTENDED,
                                                null)))
                .as("no adapter to read it with")
                .isEmpty();
    }
}
