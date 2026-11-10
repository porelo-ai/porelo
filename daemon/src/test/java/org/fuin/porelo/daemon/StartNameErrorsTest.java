package org.fuin.porelo.daemon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.nio.file.Path;
import java.util.Map;
import org.fuin.porelo.app.PoreloContext;
import org.fuin.porelo.app.PoreloPaths;
import org.fuin.porelo.app.TaskReservations;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A caller must choose another name without guessing ownership from terminal output. */
class StartNameErrorsTest {
    @Test
    void anUnknownOwnerIsAbsentRatherThanGuessed(@TempDir Path dir) throws Exception {
        final var context =
                new PoreloContext(
                        new FakeCommandRunner(),
                        new PoreloPaths(
                                new XdgPaths(
                                        dir.resolve("config"),
                                        dir.resolve("data"),
                                        dir.resolve("state"),
                                        dir.resolve("run"),
                                        dir),
                                dir.resolve("bin")),
                        args -> 0);
        java.nio.file.Files.createDirectories(context.paths().messaging().mailbox("porelo-web-x"));
        final var taken =
                catchThrowableOfType(
                        () ->
                                new TaskReservations(context)
                                        .reserve("web", "x", "porelo-web-x", "first"),
                        TaskReservations.ContainerNameTaken.class);
        assertThat(taken).isNotNull();
        final var error =
                catchThrowableOfType(
                        () -> TaskMethods.containerNameRefused(taken), VarlinkException.class);
        assertThat(error).isNotNull();
        assertThat(error.getParameters())
                .isEqualTo(Map.of("container", "porelo-web-x", "project", "web", "task", "x"));
    }

    @Test
    void aLegacyOverlapCarriesTheProvenOwner(@TempDir Path dir) throws Exception {
        final var context =
                new PoreloContext(
                        new FakeCommandRunner(),
                        new PoreloPaths(
                                new XdgPaths(
                                        dir.resolve("config"),
                                        dir.resolve("data"),
                                        dir.resolve("state"),
                                        dir.resolve("run"),
                                        dir),
                                dir.resolve("bin")),
                        args -> 0);
        final var reservations = new TaskReservations(context);
        try (var held = reservations.reserve("web", "app-x", "porelo-web-app-x", "first")) {
            final var taken =
                    catchThrowableOfType(
                            () -> reservations.reserve("web-app", "x", "porelo-web-app-x", "other"),
                            TaskReservations.ContainerNameTaken.class);
            assertThat(taken).isNotNull();
            final var error =
                    catchThrowableOfType(
                            () -> TaskMethods.containerNameRefused(taken), VarlinkException.class);
            assertThat(error).isNotNull();
            assertThat(error.getErrorName())
                    .isEqualTo(PoreloDaemon.INTERFACE + ".ContainerNameTaken");
            assertThat(error.getParameters())
                    .isEqualTo(
                            Map.of(
                                    "container",
                                    "porelo-web-app-x",
                                    "project",
                                    "web-app",
                                    "task",
                                    "x",
                                    "ownerProject",
                                    "web",
                                    "ownerTask",
                                    "app-x"));
        }
    }
}
