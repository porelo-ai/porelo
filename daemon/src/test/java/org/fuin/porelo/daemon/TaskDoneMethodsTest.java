package org.fuin.porelo.daemon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.app.PoreloContext;
import org.fuin.porelo.app.PoreloPaths;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The daemon carries completion and the task socket cannot declare another task finished. */
class TaskDoneMethodsTest {
    private static final String TASK = "porelo-uc-work";
    @TempDir Path dir;

    private PoreloContext context() {
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
        final var runner =
                new FakeCommandRunner()
                        .answering(
                                "ps",
                                TASK
                                        + "\tExited (0) 1 minute ago\t1788500000\t1788509999\tuc\tguarded\n");
        return new PoreloContext(runner, new PoreloPaths(xdg, dir.resolve("bin")), args -> 0);
    }

    @Test
    void theDaemonsListCarriesDone() throws Exception {
        // A daemon client must not schedule finished work again after its container stops.
        final PoreloContext context = context();
        Files.writeString(
                Files.createDirectories(context.paths().tasks().taskRecord(TASK)).resolve("done"),
                "done\n");
        final Map<?, ?> task =
                (Map<?, ?>)
                        ((List<?>)
                                        TaskMethods.list(
                                                        new org.fuin.porelo.app.TaskInventory(
                                                                context))
                                                .get("tasks"))
                                .getFirst();
        assertThat(task.get("state")).isEqualTo("done");
        assertThat(task.get("activity")).isEqualTo("DONE");
        assertThat(task.get("waitingFor")).isEqualTo("");
    }

    @Test
    void theTaskEndpointMayCarryDone() {
        // Completion must be offered only as a permitted writing tool, never as a read.
        assertThat(McpTaskSockets.refusals(List.of("Done"))).isEmpty();
        assertThat(McpMethods.READING).doesNotContain("Done");
    }

    @Test
    void aTaskCannotMarkAnotherTaskDone() throws Exception {
        // A false completion would hide a sibling's, child's or another project's unfinished work.
        final PoreloContext context = context();
        for (final String other :
                List.of("porelo-uc-other", "porelo-uc-child", "porelo-other-work", "../escape")) {
            McpTaskSockets.as(
                    TASK,
                    () ->
                            assertThatThrownBy(
                                            () -> TaskMethods.done(context, Map.of("task", other)))
                                    .isInstanceOf(
                                            org.fuin.porelo.wire.varlink.VarlinkException.class)
                                    .hasMessageContaining("NotItsOwn"));
            assertThat(context.paths().tasks().taskRecord(other).resolve("done")).doesNotExist();
        }
    }

    @Test
    void theSocketCallerRecordsOnlyAFixedPrivateValue() throws Exception {
        // Unbounded task text or a task-writable completion record could falsify the host's state.
        final PoreloContext context = context();
        McpTaskSockets.as(
                TASK,
                () -> {
                    try {
                        TaskMethods.done(context, Map.of("task", TASK));
                    } catch (java.io.IOException ex) {
                        throw new java.io.UncheckedIOException(ex);
                    }
                });
        final Path record = context.paths().tasks().taskRecord(TASK).resolve("done");
        assertThat(record).hasContent("done\n");
        assertThat(Files.getPosixFilePermissions(record))
                .isEqualTo(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        assertThat(record.startsWith(context.paths().messaging().mailbox(TASK))).isFalse();
        McpTaskSockets.as(
                TASK,
                () ->
                        assertThatThrownBy(
                                        () ->
                                                TaskMethods.done(
                                                        context,
                                                        Map.of(
                                                                "task",
                                                                TASK,
                                                                "note",
                                                                "x".repeat(100_000))))
                                .isInstanceOf(org.fuin.porelo.wire.varlink.VarlinkException.class)
                                .hasMessageContaining("InvalidParameter"));
        assertThat(record).hasContent("done\n");
    }

    @Test
    void aCallWithoutATaskSocketCannotDeclareCompletion() {
        // A caller-supplied name alone is not an agent's own declaration.
        assertThatThrownBy(() -> TaskMethods.done(context(), Map.of("task", TASK)))
                .isInstanceOf(org.fuin.porelo.wire.varlink.VarlinkException.class)
                .hasMessageContaining("NotItsOwn");
    }
}
