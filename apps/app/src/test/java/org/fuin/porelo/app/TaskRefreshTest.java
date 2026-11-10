package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.gate.GitGate;
import org.junit.jupiter.api.io.TempDir;

/**
 * Test for {@link TaskRefresh}: a task's repository brought up to its source, and its agent told
 * what moved.
 */
class TaskRefreshTest {

    @TempDir Path dir;

    private final CommandRunner real =
            command ->
                    new ProcessCommandRunner()
                            .run(
                                    command.withEnvironment(
                                            java.util.Map.of(
                                                    "GIT_CONFIG_GLOBAL",
                                                    "/dev/null",
                                                    "GIT_CONFIG_NOSYSTEM",
                                                    "1")));

    private String git(final Path in, final String... arguments) {
        final List<String> command =
                new java.util.ArrayList<>(
                        List.of(
                                "git",
                                "-C",
                                in.toString(),
                                "-c",
                                "user.email=t@example.com",
                                "-c",
                                "user.name=T"));
        command.addAll(List.of(arguments));
        final CommandResult result = real.run(Command.of(command));
        assertThat(result.successful()).as(result.standardError()).isTrue();
        return result.standardOutput().strip();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {"porelo-default-app-writer", "porelo-default_app-writer"})
    void aCheckoutThatMovedOnReachesTheGateAndTheAgentIsToldToFetchIt(String task)
            throws Exception {

        // Refreshing the wrong identity can send source to another task's gate or mailbox.
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
        // The task as podman lists it; every other command is real.
        final CommandRunner runner =
                command ->
                        command.arguments().size() > 1
                                        && command.arguments().get(0).endsWith("podman")
                                        && command.arguments().get(1).equals("ps")
                                ? new CommandResult(
                                        command,
                                        0,
                                        task
                                                + "\tUp 2 minutes\t\t\tdefault\tguarded\tapp\t\t"
                                                + "a".repeat(64)
                                                + (task.contains("_") ? "\tapp-writer" : "")
                                                + "\n",
                                        "")
                                : real.run(command);
        final PoreloContext context =
                new PoreloContext(runner, new PoreloPaths(xdg, dir.resolve("bin")), arguments -> 0);
        org.fuin.porelo.wire.Sidecar.recordId(
                context.paths().tasks().taskRecord(task), "a".repeat(64));
        final Path checkout = Files.createDirectories(dir.resolve("app"));
        git(checkout, "init", "-q", "-b", "main");
        Files.writeString(checkout.resolve("README.md"), "app\n");
        git(checkout, "add", ".");
        git(checkout, "commit", "-q", "-m", "start");
        WorkOrigin.fromCheckout(context, checkout);
        final Project project = GateSupport.byName(context, DefaultProject.NAME);
        final GitGate gate =
                GateSupport.gate(context, project, project.repository("app"), null, null);
        gate.initialize();
        final Mailbox mailbox = new Mailbox(context.paths().messaging().mailbox(task));
        mailbox.create();

        assertThat(new TaskRefresh(context).refresh(task).outcome())
                .as("nothing moved yet")
                .isEqualTo(TaskRefresh.Outcome.UNCHANGED);

        Files.writeString(checkout.resolve("more.txt"), "the person goes on\n");
        git(checkout, "add", ".");
        git(checkout, "commit", "-q", "-m", "more");
        final String moved = git(checkout, "rev-parse", "HEAD");
        final TaskRefresh.Result result = new TaskRefresh(context).refresh(task);

        assertThat(result.outcome()).isEqualTo(TaskRefresh.Outcome.MOVED);
        assertThat(result.moved()).containsEntry("main", moved);
        assertThat(gate.branches()).containsEntry("main", moved);
        assertThat(result.told()).isTrue();
        try (var told = Files.list(mailbox.inboxNew())) {
            assertThat(
                            told.map(
                                            file -> {
                                                try {
                                                    return Files.readString(file);
                                                } catch (java.io.IOException ex) {
                                                    throw new java.io.UncheckedIOException(ex);
                                                }
                                            })
                                    .toList())
                    .singleElement()
                    .asString()
                    .contains("git fetch porelo")
                    .contains(moved.substring(0, 12));
        }

        // The short name 'porelo approve' takes is taken too.
        assertThat(new TaskRefresh(context).refresh("app-writer").outcome())
                .isEqualTo(TaskRefresh.Outcome.UNCHANGED);
        assertThat(new TaskRefresh(context).refresh("porelo-default-app-nobody").outcome())
                .isEqualTo(TaskRefresh.Outcome.NO_SUCH_TASK);
    }
}
