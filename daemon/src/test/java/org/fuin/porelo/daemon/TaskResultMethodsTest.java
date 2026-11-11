package org.fuin.porelo.daemon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.app.PoreloContext;
import org.fuin.porelo.app.PoreloPaths;
import org.fuin.porelo.app.TaskResult;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The window reads the file a task handed back through the daemon, or why there is none. */
class TaskResultMethodsTest {
    private static final String TASK = "porelo-uc-work";
    @TempDir Path dir;

    private PoreloContext context() throws java.io.IOException {
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
        final var runner = new FakeCommandRunner();
        final var context =
                new PoreloContext(runner, new PoreloPaths(xdg, dir.resolve("bin")), args -> 0);
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
        runner.answering(
                "ps",
                TASK
                        + "\tExited (0) 1 minute ago\t1788500000\t1788509999\tuc\tguarded\tr\t\t"
                        + "a".repeat(64)
                        + "\n");
        return context;
    }

    @Test
    void resultAnswersTheTextAndTheTaskDeclaringItselfDoneIsWhatTakesIt() throws Exception {
        // The window shows this text; a Done that took nothing would leave it missing.
        final PoreloContext context = context();
        final TaskResult result = new TaskResult(context.paths());
        result.prepare(TASK, "idea.json");
        Files.writeString(result.box(TASK).resolve("idea.json"), "{\"idea\":1}");

        assertThat(TaskMethods.result(context, TASK))
                .as("nothing is taken before the task's end")
                .isEqualTo(Map.of("name", "idea.json", "state", "missing", "bytes", 0L));
        McpTaskSockets.as(
                TASK,
                () -> {
                    try {
                        TaskMethods.done(context, Map.of("task", TASK));
                    } catch (java.io.IOException ex) {
                        throw new java.io.UncheckedIOException(ex);
                    }
                });

        assertThat(TaskMethods.result(context, TASK))
                .as("the reply carries the content when it was handed back")
                .isEqualTo(
                        Map.of(
                                "name",
                                "idea.json",
                                "state",
                                "handed",
                                "bytes",
                                10L,
                                "content",
                                "{\"idea\":1}"));
        final Map<?, ?> listed =
                (Map<?, ?>)
                        ((List<?>)
                                        TaskMethods.list(
                                                        new org.fuin.porelo.app.TaskInventory(
                                                                context))
                                                .get("tasks"))
                                .getFirst();
        assertThat(listed.get("result"))
                .as("the listing says what was taken")
                .isEqualTo(Map.of("name", "idea.json", "state", "handed", "bytes", 10L));
    }

    @Test
    void aTaskStartedWithoutAResultAndANameNoTaskHoldsAreTypedRefusals() throws Exception {
        // A client must tell "this task hands nothing back" from "no such task".
        final PoreloContext context = context();
        assertThatThrownBy(() -> TaskMethods.result(context, TASK))
                .isInstanceOfSatisfying(
                        VarlinkException.class,
                        ex ->
                                assertThat(ex.getErrorName())
                                        .isEqualTo(PoreloDaemon.INTERFACE + ".NoResultNamed"));
        assertThatThrownBy(() -> TaskMethods.result(context, "porelo-uc-other"))
                .isInstanceOfSatisfying(
                        VarlinkException.class,
                        ex ->
                                assertThat(ex.getErrorName())
                                        .isEqualTo(PoreloDaemon.INTERFACE + ".NoSuchTask"));
        assertThatThrownBy(() -> TaskMethods.result(context, "../" + TASK))
                .isInstanceOfSatisfying(
                        VarlinkException.class,
                        ex ->
                                assertThat(ex.getErrorName())
                                        .isEqualTo(PoreloDaemon.INTERFACE + ".NoSuchTask"));
    }

    @Test
    void aTaskNameWithADirectoryPartReadsNothingOutsideTheResults() throws Exception {
        // Named like a task by its prefix alone, it would reach a directory beside the results.
        final PoreloContext context = context();
        new TaskResult(context.paths()).prepare(TASK, "idea.json");

        assertThatThrownBy(() -> TaskMethods.result(context, TASK + "/../" + TASK))
                .isInstanceOfSatisfying(
                        VarlinkException.class,
                        ex ->
                                assertThat(ex.getErrorName())
                                        .isEqualTo(PoreloDaemon.INTERFACE + ".NoSuchTask"));
        assertThat(new TaskResult(context.paths()).answer(TASK + "/../" + TASK))
                .as("the box's own reader refuses such a name too")
                .isNull();
    }

    @Test
    void aStartsResultIsCarriedToTheLaunchAndOneThatIsNoFilesNameIsRefused() throws Exception {
        // A name with a directory part must never reach the box; a number must not pass as none.
        final PoreloContext context = context();
        final Path project =
                Files.writeString(
                        dir.resolve("project.yml"),
                        "project:\n  name: \"uc\"\n  security_class: \"guarded\"\nimage:\n"
                                + "  from: \"ubuntu:24.04\"\n",
                        StandardCharsets.UTF_8);
        Files.createDirectories(dir.resolve("data/porelo/projects"));
        Files.writeString(dir.resolve("data/porelo/projects/uc"), project + "\n");
        assertThat(
                        PoreloDaemon.request(
                                        Map.of("project", "uc", "task", "x", "result", "idea.json"),
                                        context)
                                .resultRefusal())
                .isNull();
        assertThat(
                        PoreloDaemon.request(
                                        Map.of("project", "uc", "task", "x", "result", "idea.json"),
                                        context)
                                .result())
                .isEqualTo("idea.json");
        assertThat(
                        PoreloDaemon.request(
                                        Map.of(
                                                "project",
                                                "uc",
                                                "task",
                                                "x",
                                                "result",
                                                "../idea.json"),
                                        context)
                                .resultRefusal())
                .as("refused by the launch with exit 64")
                .contains("--result");
        assertThatThrownBy(
                        () ->
                                PoreloDaemon.request(
                                        Map.of("project", "uc", "task", "x", "result", 7), context))
                .isInstanceOfSatisfying(
                        VarlinkException.class,
                        ex ->
                                assertThat(ex.getErrorName())
                                        .isEqualTo("org.varlink.service.InvalidParameter"));
    }
}
