package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.wire.Waiting;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Completion belongs to the task's durable host record, until another start. */
class TaskDoneTest {
    private static final String TASK = "porelo-uc-work";
    private static final String ID = "a1b2c3d4e5f6";
    private final StoppingCommandRunner runner = new StoppingCommandRunner();
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
        return new PoreloContext(runner, new PoreloPaths(xdg, dir.resolve("bin")), args -> 0)
                .recognizingTasks();
    }

    private Path task(PoreloContext context, boolean running) throws IOException {
        runner.answering(
                "ps",
                TASK
                        + "\t"
                        + (running ? "Up 4 minutes" : "Exited (0) 1 minute ago")
                        + "\t1788500000\t1788509999\tuc\tguarded\t\t\t"
                        + ID
                        + "\n");
        runner.answering("{{.Id}}", ID);
        final Path state = Files.createDirectories(context.paths().tasks().containerState(TASK));
        org.fuin.porelo.wire.Sidecar.recordId(state, ID);
        return state;
    }

    private Path done(PoreloContext context) throws IOException {
        return Files.writeString(
                Files.createDirectories(context.paths().tasks().taskRecord(TASK)).resolve("done"),
                "done\n");
    }

    private TaskInventory.Task only(PoreloContext context) {
        return new TaskInventory(context).tasks().getFirst();
    }

    @Test
    void aLegacyDeclarationReadsAsDoneAtItsModificationTime() throws IOException {
        // A planner must not schedule work again because an older declaration has no JSON fields.
        final PoreloContext context = context();
        task(context, false);
        final Instant at = Instant.parse("2026-11-10T10:00:00Z");
        Files.setLastModifiedTime(done(context), FileTime.from(at));
        assertThat(new TaskDone(context.paths()).read(TASK))
                .as("the old mark is still a declaration")
                .isEqualTo(new TaskDone.Declared(TaskDone.Outcome.DONE, "", at.toString()));
        assertThat(only(context).asMap().get("ended"))
                .as("the declaration reaches the task's reader")
                .isEqualTo(java.util.Map.of("outcome", "DONE", "why", "", "at", at.toString()));
    }

    @Test
    void blockedKeepsItsWholeReasonAcrossAStop() throws IOException {
        // Losing a blocker or trimming its reason makes the next step act on work never finished.
        final PoreloContext context = context();
        task(context, false);
        final TaskDone done = new TaskDone(context.paths());
        final String why = "  " + "\ud83d\ude00".repeat(255) + "  ";
        done.record(TASK, TaskDone.Outcome.BLOCKED, why);
        assertThat(done.read(TASK))
                .as("the host retains the declaration")
                .isNotNull()
                .satisfies(
                        declared -> {
                            assertThat(declared.outcome())
                                    .as("the blocker stays typed")
                                    .isEqualTo(TaskDone.Outcome.BLOCKED);
                            assertThat(declared.why())
                                    .as("accepted text is never trimmed")
                                    .isEqualTo(why);
                            assertThat(declared.at())
                                    .as("the declaration has its own instant")
                                    .satisfies(Instant::parse);
                        });
        assertThat(only(context).activity())
                .as("blocked does not claim completion")
                .isEqualTo(TaskInventory.Activity.DEAD);
        assertThat((java.util.Map<?, ?>) only(context).asMap().get("ended"))
                .as("a client can distinguish the blocker")
                .isNotNull()
                .satisfies(
                        ended -> {
                            assertThat(ended.get("outcome"))
                                    .as("declared outcome")
                                    .isEqualTo("BLOCKED");
                            assertThat(ended.get("why")).as("declared reason").isEqualTo(why);
                        });
    }

    static java.util.stream.Stream<String> invalidReasons() {
        return java.util.stream.Stream.of(
                "x".repeat(1025),
                "\u20ac".repeat(342),
                "first\nsecond",
                "first\rsecond",
                "tab\there",
                "nul\0here",
                "escape\u001bhere",
                "delete\u007fhere",
                "control\u0085here",
                "line\u2028here",
                "paragraph\u2029here");
    }

    @Test
    void anUnreadableDeclarationCannotBreakTheTaskListing() throws IOException {
        // A corrupt durable timestamp must not make every task disappear from List or Watch.
        final PoreloContext context = context();
        task(context, false);
        Files.writeString(
                done(context), "{\"outcome\":\"DONE\",\"why\":\"\",\"at\":\"not-a-time\"}");
        assertThat(new TaskDone(context.paths()).read(TASK))
                .as("the malformed declaration is unreadable")
                .isNull();
        assertThat(only(context).activity())
                .as("the listing still answers for the container")
                .isEqualTo(TaskInventory.Activity.DEAD);
    }

    @ParameterizedTest
    @MethodSource("invalidReasons")
    void anInvalidReasonCannotReplaceADeclaration(String why) throws IOException {
        // A task must not persist unbounded text or inject another line into a reader's output.
        final PoreloContext context = context();
        final Path record = done(context);
        assertThatThrownBy(
                        () ->
                                new TaskDone(context.paths())
                                        .record(TASK, TaskDone.Outcome.DONE, why))
                .as("invalid text is refused before writing")
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(record)
                .as("a refused call preserves the previous declaration")
                .hasContent("done\n");
    }

    @Test
    void blockedNeedsANonBlankReason() throws IOException {
        // An unexplained blocker gives the planner no way to choose its next act.
        final PoreloContext context = context();
        for (final String why : java.util.List.of("", "   ", "\u2003")) {
            assertThatThrownBy(
                            () ->
                                    new TaskDone(context.paths())
                                            .record(TASK, TaskDone.Outcome.BLOCKED, why))
                    .as("BLOCKED needs a reason")
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(context.paths().tasks().doneRecord(TASK))
                .as("no invalid declaration was stored")
                .doesNotExist();
    }

    @Test
    void aStoppedTaskThatSaidDoneListsAsDone() throws IOException {
        // Losing completion across a stop sends a person back to work already finished.
        final PoreloContext context = context();
        task(context, false);
        done(context);
        assertThat(only(context).state()).isEqualTo("done");
        assertThat(only(context).activity().name()).isEqualTo("DONE");
        assertThat(only(context).waitingFor()).isNull();
        final StringWriter out = new StringWriter();
        final var cli = PoreloCli.commandLine(context);
        cli.setOut(new PrintWriter(out));
        assertThat(cli.execute("task", "list")).isZero();
        assertThat(out.toString()).contains("done").doesNotContain("Exited");
        final Path record = context.paths().tasks().doneRecord(TASK);
        Files.writeString(record, "done\n" + "x".repeat(100_000));
        assertThat(only(context).activity()).isEqualTo(TaskInventory.Activity.DEAD);
        Files.delete(record);
        Files.createSymbolicLink(record, Files.writeString(dir.resolve("forged"), "done\n"));
        assertThat(only(context).activity()).isEqualTo(TaskInventory.Activity.DEAD);
    }

    @Test
    void aLaterStartClearsCompletion() throws IOException {
        // A resumed task must not inherit the previous run's completion.
        final PoreloContext context = context();
        task(context, false);
        final Path record = done(context);
        assertThat(new TaskControl(context).resume(TASK).outcome())
                .isEqualTo(TaskControl.Outcome.NO_HELPERS_RECORDED);
        assertThat(record).doesNotExist();
    }

    @Test
    void statusCarriesDoneForAStoppedTask() throws IOException {
        // Automation must not restart completed work because status lost its completion.
        final PoreloContext context = context();
        task(context, false);
        done(context);
        final StringWriter out = new StringWriter();
        final var cli = PoreloCli.commandLine(context);
        cli.setOut(new PrintWriter(out));
        assertThat(cli.execute("task", "status", TASK, "--json")).isZero();
        assertThat((java.util.Map<?, ?>) org.fuin.porelo.wire.Json.parse(out.toString()))
                .extracting(map -> map.get("state"))
                .isEqualTo("done");
        out.getBuffer().setLength(0);
        assertThat(cli.execute("task", "status", TASK)).isZero();
        assertThat(out.toString()).contains("done").doesNotContain("Exited");
    }

    @Test
    void anIdleDoneTaskShowsWorkAgainWithoutLosingItsRecord() throws IOException {
        // A completion note must not hide new work or turn into a request for attention.
        final PoreloContext context = context();
        final Path state = task(context, true);
        final Path log = Files.writeString(state.resolve("task.log"), "finished\n");
        Files.setLastModifiedTime(log, FileTime.from(Instant.now().minusSeconds(3600)));
        final Path record = done(context);
        assertThat(only(context).activity().name()).isEqualTo("DONE");
        assertThat(only(context).state()).isEqualTo("done");
        assertThat(only(context).waitingFor()).isNull();
        Files.setLastModifiedTime(log, FileTime.from(Instant.now().plusSeconds(3600)));
        assertThat(only(context).activity()).isEqualTo(TaskInventory.Activity.WORKING);
        assertThat(only(context).state()).isEqualTo("Up 4 minutes");
        assertThat(record).hasContent("done\n");
        Files.setLastModifiedTime(log, FileTime.from(Instant.now().minusSeconds(3600)));
        assertThat(only(context).activity().name()).isEqualTo("DONE");
        assertThat(new TaskControl(context).resume(TASK).outcome())
                .isEqualTo(TaskControl.Outcome.ALREADY_RUNNING);
        assertThat(record).hasContent("done\n");
        new org.fuin.porelo.wire.TaskProfile(
                        org.fuin.porelo.wire.TaskProfile.VERSION,
                        "example",
                        org.fuin.porelo.wire.TaskMode.UNATTENDED,
                        "work",
                        null,
                        "2026-10-01T00:00:00Z",
                        "deny")
                .writeTo(state);
        final AgentEnding ending =
                new AgentEnding(
                        runner,
                        java.time.Clock.systemUTC(),
                        (agent, lines) ->
                                new org.fuin.porelo.agent.api.AgentEnd(true, "", "agent", null));
        assertThat(new TaskInventory(context, null, ending).tasks().getFirst().activity())
                .isEqualTo(TaskInventory.Activity.ENDED);
        Files.delete(log);
        assertThat(only(context).activity()).isEqualTo(TaskInventory.Activity.UNKNOWN);
        new Waiting(state).asking("api.example.test:443");
        assertThat(only(context).activity()).isEqualTo(TaskInventory.Activity.WAITING);
        assertThat(only(context).waitingFor()).isEqualTo("api.example.test:443");
    }

    @Test
    void aDoneRecordSurvivesSavingAndLosingRuntimeState() throws IOException {
        // A reboot must not erase the agent's completion declaration.
        final PoreloContext context = context();
        final Path state = task(context, false);
        done(context);
        new TaskState(context).save(TASK);
        Files.delete(state.resolve("container.id"));
        Files.delete(state);
        assertThat(only(context).state()).isEqualTo("done");
    }
}
