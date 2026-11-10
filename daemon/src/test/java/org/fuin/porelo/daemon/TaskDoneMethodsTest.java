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
import org.fuin.porelo.wire.varlink.VarlinkClient;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.fuin.porelo.wire.varlink.VarlinkServer;
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
        final var context =
                new PoreloContext(runner, new PoreloPaths(xdg, dir.resolve("bin")), args -> 0);
        try {
            final Path state = context.paths().tasks().taskRecord(TASK);
            org.fuin.porelo.wire.Sidecar.recordId(state, "a".repeat(64));
            new org.fuin.porelo.wire.Sidecar(
                            org.fuin.porelo.wire.Sidecar.VERSION,
                            "uc",
                            "guarded",
                            "/rules",
                            "/dns",
                            "/porelo",
                            context.paths().tasks().containerState(TASK).toString())
                    .writeTo(state.resolve("sidecar.json"));
        } catch (java.io.IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
        runner.answering(
                "ps",
                TASK
                        + "\tExited (0) 1 minute ago\t1788500000\t1788509999\tuc\tguarded\tr\t\t"
                        + "a".repeat(64)
                        + "\n");
        return context;
    }

    @Test
    void anEncodedCallerCanFinishOnlyItsVerifiedContainer() throws Exception {
        // A sibling's id must never authorize a completion at this endpoint.
        final var context = context();
        final String actual = "porelo-uc_work";
        final Path state = context.paths().tasks().taskRecord(actual);
        org.fuin.porelo.wire.Sidecar.recordId(state, "a".repeat(64));
        new org.fuin.porelo.wire.Sidecar(
                        org.fuin.porelo.wire.Sidecar.VERSION,
                        "uc",
                        "guarded",
                        "/rules",
                        "/dns",
                        "/porelo",
                        context.paths().tasks().containerState(actual).toString(),
                        "work",
                        null,
                        null)
                .writeTo(state.resolve("sidecar.json"));
        McpTaskSockets.as(
                actual,
                () -> {
                    try {
                        TaskMethods.done(context, Map.of("task", actual));
                    } catch (java.io.IOException ex) {
                        throw new java.io.UncheckedIOException(ex);
                    }
                });
        assertThat(new org.fuin.porelo.app.TaskDone(context.paths()).read(actual)).isNotNull();
        assertThat(context.paths().tasks().taskRecord(TASK).resolve("done")).doesNotExist();
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
    void theSocketCallerRecordsAPrivateDeclarationAndRefusesOtherText() throws Exception {
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
        final String recorded = Files.readString(record);
        assertThat((Map<?, ?>) org.fuin.porelo.wire.Json.parse(recorded))
                .as("omitting the new parameters still declares DONE without a reason")
                .isNotNull()
                .satisfies(
                        ended -> {
                            assertThat(ended.get("outcome"))
                                    .as("default outcome")
                                    .isEqualTo("DONE");
                            assertThat(ended.get("why")).as("default reason").isEqualTo("");
                        });
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
        assertThat(record).hasContent(recorded);
    }

    @Test
    void aCallWithoutATaskSocketCannotDeclareCompletion() {
        // A caller-supplied name alone is not an agent's own declaration.
        assertThatThrownBy(() -> TaskMethods.done(context(), Map.of("task", TASK)))
                .isInstanceOf(org.fuin.porelo.wire.varlink.VarlinkException.class)
                .hasMessageContaining("NotItsOwn");
    }

    @Test
    void aTaskDeclarationAnswersItsOutcomeAndReasonInList() throws Exception {
        // A declaration that is accepted but lost by List makes a planner repeat blocked work.
        final PoreloContext context = context();
        McpTaskSockets.as(
                TASK,
                () -> {
                    try {
                        TaskMethods.done(
                                context,
                                Map.of(
                                        "task",
                                        TASK,
                                        "outcome",
                                        "BLOCKED",
                                        "why",
                                        "Waiting for review"));
                    } catch (java.io.IOException ex) {
                        throw new java.io.UncheckedIOException(ex);
                    }
                });
        final Map<?, ?> task =
                (Map<?, ?>)
                        ((List<?>)
                                        TaskMethods.list(
                                                        new org.fuin.porelo.app.TaskInventory(
                                                                context))
                                                .get("tasks"))
                                .getFirst();
        assertThat((Map<?, ?>) task.get("ended"))
                .as("a task's reader learns its blocker")
                .isNotNull()
                .satisfies(
                        ended -> {
                            assertThat(ended.get("outcome"))
                                    .as("declared outcome")
                                    .isEqualTo("BLOCKED");
                            assertThat(ended.get("why"))
                                    .as("declared reason")
                                    .isEqualTo("Waiting for review");
                        });
    }

    @Test
    void aBlockedDeclarationOverTheTaskSocketReachesList() throws Exception {
        // A planner must learn a blocker over the same contract used to declare it.
        final PoreloContext context = context();
        final Path project =
                Files.writeString(
                        dir.resolve("project.yml"),
                        "project:\n  name: uc\n  security_class: guarded\nimage:\n  from: ubuntu:24.04\n"
                                + "mcp:\n  methods: [Done, List, Tail]\n  agents:\n    example:\n  tasks: 1\n  minutes: 60\n");
        Files.writeString(
                Files.createDirectories(dir.resolve("data/porelo/projects")).resolve("uc"),
                project + "\n");
        try (McpTaskSockets sockets = new McpTaskSockets(context)) {
            final Path socket = sockets.open(TASK, "uc").orElseThrow();
            try (VarlinkClient client = new VarlinkClient(socket)) {
                client.call(
                        PoreloDaemon.INTERFACE + ".Done",
                        Map.of("task", TASK, "outcome", "BLOCKED", "why", "Waiting for review"));
                final Map<?, ?> task =
                        (Map<?, ?>)
                                ((List<?>)
                                                client.call(
                                                                PoreloDaemon.INTERFACE + ".List",
                                                                Map.of())
                                                        .get("tasks"))
                                        .getFirst();
                assertThat((Map<?, ?>) task.get("ended"))
                        .as("the declared blocker reaches its reader")
                        .isNotNull()
                        .satisfies(
                                ended -> {
                                    assertThat(ended.get("outcome"))
                                            .as("declared outcome")
                                            .isEqualTo("BLOCKED");
                                    assertThat(ended.get("why"))
                                            .as("declared reason")
                                            .isEqualTo("Waiting for review");
                                    assertThat(ended.get("at"))
                                            .as("declaration time")
                                            .isInstanceOf(String.class);
                                });
                assertThat(task.get("activity"))
                        .as("a blocker does not claim completion")
                        .isEqualTo("DEAD");
            }
        }
        try (VarlinkServer server = PoreloDaemon.serving(context, dir.resolve("person.sock"))) {
            final Thread thread = Thread.ofPlatform().daemon().start(server);
            try (VarlinkClient client = new VarlinkClient(server.socketPath())) {
                assertThatThrownBy(
                                () ->
                                        client.call(
                                                PoreloDaemon.INTERFACE + ".Done",
                                                Map.of(
                                                        "task",
                                                        TASK,
                                                        "outcome",
                                                        "BLOCKED",
                                                        "why",
                                                        "Waiting for review")))
                        .as("a name on the person's socket cannot impersonate the task")
                        .isInstanceOfSatisfying(
                                VarlinkException.class,
                                ex ->
                                        assertThat(ex.getErrorName())
                                                .as("typed ownership refusal")
                                                .isEqualTo(PoreloDaemon.INTERFACE + ".NotItsOwn"));
            } finally {
                server.close();
                thread.join(java.time.Duration.ofSeconds(5));
            }
        }
    }

    @Test
    void invalidDeclarationParametersAreTypedRefusalsAndLeaveTheOldValue() throws Exception {
        // Defaults must never turn an invalid blocker or a wrong JSON type into completion.
        final PoreloContext context = context();
        final Path record =
                Files.writeString(
                        Files.createDirectories(context.paths().tasks().taskRecord(TASK))
                                .resolve("done"),
                        "done\n");
        final List<Map<String, Object>> calls = new java.util.ArrayList<>();
        for (final Object outcome : List.of("FAILED", "unknown", 42, true)) {
            calls.add(Map.of("task", TASK, "outcome", outcome));
        }
        for (final Object why : List.of(42, true, "x".repeat(1025), "a\nb", "tab\there")) {
            calls.add(Map.of("task", TASK, "why", why));
        }
        calls.add(Map.of("task", TASK, "outcome", "BLOCKED"));
        calls.add(Map.of("task", TASK, "outcome", "BLOCKED", "why", "   "));
        McpTaskSockets.as(
                TASK,
                () -> {
                    for (final Map<String, Object> parameters : calls) {
                        assertThatThrownBy(() -> TaskMethods.done(context, parameters))
                                .as("invalid declaration parameters are refused")
                                .isInstanceOfSatisfying(
                                        VarlinkException.class,
                                        ex ->
                                                assertThat(ex.getErrorName())
                                                        .as("typed parameter refusal")
                                                        .isEqualTo(
                                                                "org.varlink.service.InvalidParameter"));
                    }
                });
        assertThat(record)
                .as("refused calls preserve the previous declaration")
                .hasContent("done\n");
    }
}
