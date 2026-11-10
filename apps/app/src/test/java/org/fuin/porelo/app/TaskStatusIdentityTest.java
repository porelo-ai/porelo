package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.wire.Json;
import org.fuin.porelo.wire.TaskMode;
import org.fuin.porelo.wire.TaskProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Programs need the task's recorded model and both forms of its name. */
class TaskStatusIdentityTest {
    @Test
    void statusJsonNamesTheRecordedModelAndTheShortTaskBesideItsContainer(@TempDir Path dir)
            throws Exception {
        // Missing identity and model fields make a client guess which task and model it is showing.
        final var runner = new FakeCommandRunner();
        runner.answering("ps", "porelo-uc-shell-1\tExited (0)\t\t\tuc\n");
        final var xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                    case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    default -> null;
                                },
                        dir);
        final var context =
                new PoreloContext(runner, new PoreloPaths(xdg, dir.resolve("bin")), args -> 0);
        new TaskProfile(
                        TaskProfile.VERSION,
                        "example",
                        TaskMode.UNATTENDED,
                        "fix it",
                        null,
                        "2026-09-07T10:00:00Z",
                        "prompt",
                        null,
                        "provider-a",
                        "model-a",
                        null,
                        0,
                        null,
                        "project",
                        "Model A",
                        "shell")
                .writeTo(
                        Files.createDirectories(
                                context.paths().tasks().containerState("porelo-uc-shell-1")));
        final var output = new StringWriter();
        final var cli = PoreloCli.commandLine(context);
        cli.setOut(new PrintWriter(output));

        assertThat(cli.execute("task", "status", "porelo-uc-shell-1", "--json"))
                .as("status is readable without an installed agent")
                .isZero();
        final var report = (Map<?, ?>) Json.parse(output.toString());
        assertThat(report.get("model")).as("the saved model").isEqualTo("model-a");
        assertThat(report.get("modelLabel")).as("the saved label").isEqualTo("Model A");
        assertThat(report.get("modelFrom")).as("the saved source").isEqualTo("project");
        assertThat(report.get("task"))
                .as("the inventory's short name, including its run")
                .isEqualTo("shell-1");
        assertThat(report.get("container")).as("the container name").isEqualTo("porelo-uc-shell-1");
        assertThat(report.get("name"))
                .as("the compatible existing name")
                .isEqualTo("porelo-uc-shell-1");
    }
}
