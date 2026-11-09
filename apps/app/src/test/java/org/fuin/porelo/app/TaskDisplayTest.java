package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.wire.TaskMode;
import org.fuin.porelo.wire.TaskProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the name a task row is shown by: the one its start was given, else its short name, else
 * its container's - while the container name stays what every call takes.
 */
class TaskDisplayTest {

    @TempDir Path dir;

    private PoreloContext context(final String listed) {
        final CommandRunner runner =
                command ->
                        new CommandResult(
                                command, 0, command.toString().contains(" ps") ? listed : "", "");
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    default -> null;
                                },
                        dir);
        return new PoreloContext(runner, new PoreloPaths(xdg, dir.resolve("bin")), arguments -> 0);
    }

    private void started(final String container, final String named) throws Exception {
        new TaskProfile(
                        TaskProfile.VERSION,
                        "example",
                        TaskMode.AGENT,
                        null,
                        null,
                        "2026-11-08T12:00:00Z",
                        null,
                        null,
                        null,
                        null,
                        null,
                        0,
                        null,
                        named)
                .writeTo(Files.createDirectories(dir.resolve("run/porelo").resolve(container)));
    }

    @Test
    void aRowIsShownByTheNameItsStartWasGivenElseByItsShortNameAndKeepsItsContainer()
            throws Exception {
        // A row shown by its container makes a person read 'porelo-porelo-pi' for the task 'pi'; a
        // row whose name moved would make every call that takes it miss the task.
        started("porelo-uc-fix", "fix");
        started("porelo-uc-pi", null);
        final List<TaskInventory.Task> tasks =
                new TaskInventory(
                                context(
                                        "porelo-uc-fix\tUp 4 minutes\t\t\tuc\n"
                                                + "porelo-uc-pi\tUp 4 minutes\t\t\tuc\n"
                                                + "porelo-gone-x\tUp 4 minutes\n"))
                        .tasks();

        final Map<String, Map<String, Object>> rows = new java.util.HashMap<>();
        tasks.forEach(task -> rows.put(task.name(), task.asMap()));
        assertThat(rows.get("porelo-uc-fix"))
                .as("a task named at its start is shown by that name and recorded as given")
                .containsEntry("display", "fix")
                .containsEntry("named", "fix")
                .containsEntry("short", "fix")
                .containsEntry("name", "porelo-uc-fix");
        assertThat(rows.get("porelo-uc-pi"))
                .as("a task nobody named is shown by its short name, and named by nobody")
                .containsEntry("display", "pi")
                .containsEntry("named", "")
                .containsEntry("name", "porelo-uc-pi");
        assertThat(rows.get("porelo-gone-x"))
                .as("with no project recorded, nothing but its container can name it")
                .containsEntry("display", "porelo-gone-x")
                .containsEntry("short", "");
    }
}
