package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.fuin.porelo.core.config.XdgPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The values a task was started with mask a later run's command lines too: that run is not given
 * them again, its container still holds them.
 */
class TaskStartValuesTest {
    private static final String TASK = "porelo-uc-work";
    @TempDir Path dir;

    private PoreloPaths paths() {
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                                    case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                    default -> null;
                                },
                        dir);
        return new PoreloPaths(xdg, dir.resolve("bin"));
    }

    @Test
    void aLaterRunIsMaskedByTheValuesItsTaskWasStartedWith() throws IOException {
        final PoreloPaths paths = paths();
        final Path state = paths.tasks().containerState(TASK);
        Files.createDirectories(state);
        Files.writeString(
                state.resolve(TaskInventory.PARAMETERS_FILE),
                "{\"DEPLOY_ID\":{\"value\":\"FAKEdeploy1234\",\"origin\":\"command line\"}}");

        final CommandMask mask = new CommandMask(TaskLaunch.startValues(paths, TASK));

        assertThat(mask.mask("echo FAKEdeploy1234")).isEqualTo("echo ***");
    }

    @Test
    void aTaskWithoutARecordOfItsValuesHasNone() {
        assertThat(TaskLaunch.startValues(paths(), TASK)).isEmpty();
    }
}
