package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.wire.Sidecar;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A task is what Porelo recorded, never what is named like one (2026-10-04). */
class RecognizedTasksTest {

    @TempDir private Path dir;

    @Test
    void everyContextACommandOrTheDaemonBuildsRecognizesTasksByTheirRecord() {
        assertThat(PoreloContext.real().recognizesTasks()).isTrue();
    }

    @Test
    void aForeignContainerNamedAndLabelledLikeATaskIsNoTaskAndGetsNothing() throws IOException {
        final FakeCommandRunner runner = new FakeCommandRunner();
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                                    case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                    default -> null;
                                },
                        dir);
        final PoreloContext context =
                new PoreloContext(runner, new PoreloPaths(xdg, dir.resolve("bin")), arguments -> 0)
                        .recognizingTasks();
        Sidecar.recordId(context.paths().tasks().containerState("porelo-p-real"), "a1b2c3d4e5f6");
        runner.answering(
                "ps",
                "porelo-p-real\tUp 1 minute\t\t\tp\tguarded\t\t\ta1b2c3d4e5f6\n"
                        + "porelo-p-forged\tUp 1 minute\t\t\tp\tguarded\t\t\t0f0f0f0f0f0f\n");

        assertThat(new TaskInventory(context).tasks())
                .extracting(TaskInventory.Task::name)
                .containsExactly("porelo-p-real");
        assertThat(new AgentWake(context).wake("porelo-p-forged")).isFalse();
        assertThat(context.podman().screen("porelo-p-forged", 5, false).live()).isFalse();
        assertThat(runner.invocations())
                .noneMatch(command -> command.arguments().contains("porelo-p-forged"));
    }

    @Test
    void stoppingOrRemovingANameWhoseRecordIsGoneAnswersThatItIsNoTaskRatherThanFailing()
            throws IOException {

        // A task removed, then stopped or removed again by a client that still showed it: the
        // daemon answered Failed and the command wrote a stack trace, where the outcome promises
        // NOT_A_TASK.
        final FakeCommandRunner runner = new FakeCommandRunner();
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                                    case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                    default -> null;
                                },
                        dir);
        final PoreloContext context =
                new PoreloContext(runner, new PoreloPaths(xdg, dir.resolve("bin")), arguments -> 0)
                        .recognizingTasks();
        final TaskControl control = new TaskControl(context);

        assertThat(control.stop("porelo-p-gone").outcome())
                .isEqualTo(TaskControl.Outcome.NOT_A_TASK);
        assertThat(control.remove("porelo-p-gone", false, true).outcome())
                .isEqualTo(TaskControl.Outcome.NOT_A_TASK);
        assertThat(runner.invocations())
                .as("and nothing is done to a container of that name")
                .noneMatch(command -> command.arguments().contains("porelo-p-gone"));
    }

    @Test
    void aTaskCreatedAndNeverStartedCanStillBeRemovedAfterARebootEmptiedItsRuntimeDirectory()
            throws IOException {

        // A start the hook refused leaves the container created and never started, and saves
        // nothing: with the runtime directory gone, it was no task, and 'task remove' answered
        // NOT_A_TASK for a container that kept the name taken.
        final FakeCommandRunner runner = new FakeCommandRunner();
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                                    case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                    default -> null;
                                },
                        dir);
        final PoreloContext context =
                new PoreloContext(runner, new PoreloPaths(xdg, dir.resolve("bin")), arguments -> 0)
                        .recognizingTasks();
        new TaskRunner(runner, context.paths()).recordCreated("porelo-p-new", "a1b2c3d4e5f6");
        final Path runtime = context.paths().tasks().containerState("porelo-p-new");
        java.nio.file.Files.delete(runtime.resolve(Sidecar.RECORDED_ID));
        java.nio.file.Files.delete(runtime);
        runner.answering(
                "ps",
                "porelo-p-new\tCreated\t-62135596800\t-62135596800\tp\tguarded\t\t\ta1b2c3d4e5f6\n");

        assertThat(new TaskInventory(context).tasks())
                .extracting(TaskInventory.Task::name)
                .containsExactly("porelo-p-new");
        assertThat(new TaskControl(context).remove("porelo-p-new", false, true).outcome())
                .isNotEqualTo(TaskControl.Outcome.NOT_A_TASK);
        assertThat(runner.lines())
                .as("removed by the id it was created with")
                .anyMatch(line -> line.contains(" rm ") && line.contains("a1b2c3d4e5f6"));
    }

    @Test
    void aTaskARebootStoppedIsStillRecognizedByItsSavedRecord() throws IOException {

        // A reboot empties the runtime directory, where the id was first written: found by the
        // suite on 2026-10-04, a restarted task was no task any more.
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                                    case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                    default -> null;
                                },
                        dir);
        final PoreloContext context =
                new PoreloContext(
                                new FakeCommandRunner(),
                                new PoreloPaths(xdg, dir.resolve("bin")),
                                arguments -> 0)
                        .recognizingTasks();
        final Path runtime = context.paths().tasks().containerState("porelo-p-kept");
        Sidecar.recordId(runtime, "a1b2c3d4e5f6");
        new TaskState(context).save("porelo-p-kept");
        java.nio.file.Files.delete(runtime.resolve(Sidecar.RECORDED_ID));

        assertThat(context.recordedId("porelo-p-kept")).isEqualTo("a1b2c3d4e5f6");
    }
}
