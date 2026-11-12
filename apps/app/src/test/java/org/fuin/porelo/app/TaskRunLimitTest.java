package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import org.fuin.porelo.agent.api.AgentEnd;
import org.fuin.porelo.core.config.XdgPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A run past its minutes is ended in its container, not only its podman exec client: run 26's
 * reader went on past its 30 minutes until a person stopped it.
 */
class TaskRunLimitTest {

    private static final String ID = "c0ffee".repeat(10) + "abcd";

    private static final String TASK = "porelo-uc-all";

    private final StoppingCommandRunner runner = new StoppingCommandRunner();

    private PoreloContext context(final Path dir) {
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
        return new PoreloContext(runner, new PoreloPaths(xdg, dir.resolve("bin")), arguments -> 0);
    }

    @Test
    void aRunPastItsMinutesHasItsTaskStoppedAndSaysItWasItsLimit(@TempDir final Path dir)
            throws IOException {
        final PoreloContext context = context(dir);
        TaskIdentityFixture.record(context.paths(), TASK, "uc", "all", ID);
        final Path state = context.paths().tasks().containerState(TASK);
        RunLimit.started(state, 1);
        runner.answering(
                "ps", TASK + "\tUp 4 minutes\t1700000000\t0\tuc\tguarded\t\t\t" + ID + "\tall\n");
        final StringWriter said = new StringWriter();

        TaskLaunch.endAtLimit(context, TASK, 1, new PrintWriter(said, true));

        assertThat(runner.invocations())
                .as("its container is stopped, which ends the agent inside it")
                .anyMatch(command -> command.describe().startsWith("podman stop "));
        final AgentEnding.Ended ended =
                new AgentEnding(runner, Clock.systemUTC(), (agent, lines) -> null)
                        .about(TASK, false, "an-agent", state);
        assertThat(ended).as("List's agentEnded, before any agent is asked").isNotNull();
        assertThat(ended.asMap())
                .containsEntry("limit", true)
                .containsEntry("finished", false)
                .containsEntry("source", "porelo")
                .containsEntry("error", "its time limit of 1 minutes passed");
        assertThat(said.toString()).as("nothing failed").isEmpty();
    }

    @Test
    void aStopAtTheLimitThatLeftSomethingRunningSaysSoAsAStopDoes(@TempDir final Path dir)
            throws IOException {
        final PoreloContext context = context(dir);
        TaskIdentityFixture.record(context.paths(), TASK, "uc", "all", ID);
        RunLimit.started(context.paths().tasks().containerState(TASK), 1);
        runner.answering(
                "ps", TASK + "\tUp 4 minutes\t1700000000\t0\tuc\tguarded\t\t\t" + ID + "\tall\n");
        runner.afterStop("running");
        final StringWriter said = new StringWriter();

        TaskLaunch.endAtLimit(context, TASK, 1, new PrintWriter(said, true));

        assertThat(said.toString())
                .as("what the stop could not end is named, as 'porelo task stop' names it")
                .contains("porelo: still running: container " + TASK + " stop unconfirmed");
    }

    @Test
    void aTaskSomebodyStoppedJustBeforeItsLimitIsNotRecordedAsEndedByIt(@TempDir final Path dir)
            throws IOException {
        final PoreloContext context = context(dir);
        TaskIdentityFixture.record(context.paths(), TASK, "uc", "all", ID);
        final Path state = context.paths().tasks().containerState(TASK);
        RunLimit.started(state, 1);
        // An operator's stop came first: the deadline finds the task stopped already.
        runner.answering(
                "ps",
                TASK
                        + "\tExited (143) 2 seconds ago\t1700000000\t0\tuc\tguarded\t\t\t"
                        + ID
                        + "\tall\n");

        TaskLaunch.endAtLimit(context, TASK, 1, new PrintWriter(new StringWriter(), true));

        assertThat(RunLimit.endedBy(state))
                .as("the stop that ended it was the operator's, not the limit's")
                .isNull();
    }

    @Test
    void anAgentThatEndedItsRunAsTheLimitPassedFinished(@TempDir final Path dir)
            throws IOException {
        final Path state = Files.createDirectories(dir.resolve("state"));
        Files.writeString(state.resolve("task.log"), "{\"type\":\"end\"}\n");
        RunLimit.ended(state, 1, java.time.Instant.parse("2026-11-12T17:00:00Z"));

        final AgentEnding.Ended ended =
                new AgentEnding(
                                runner,
                                Clock.systemUTC(),
                                (agent, lines) ->
                                        lines.isEmpty()
                                                ? null
                                                : new AgentEnd(true, "", "agent", null))
                        .about(TASK, false, "an-agent", state);

        assertThat(ended).isNotNull();
        assertThat(ended.asMap())
                .as("its own end is in its log: the limit ended nothing that still ran")
                .containsEntry("finished", true)
                .containsEntry("source", "agent")
                .containsEntry("limit", false);
    }

    @Test
    void aTaskThatReadItsProjectStartedToBeRemovedIsKeptWhenItsLimitEndedIt(@TempDir final Path dir)
            throws IOException {
        final PoreloContext context = context(dir);
        TaskIdentityFixture.record(context.paths(), TASK, "uc", "all", ID);
        final Path state = context.paths().tasks().containerState(TASK);
        RunLimit.started(state, 1);
        Files.writeString(state.resolve("task.log"), "the agent polled, and polled\n");
        RunLimit.ended(state, 1, java.time.Instant.parse("2026-11-12T17:00:00Z"));
        runner.answering(
                "ps",
                TASK
                        + "\tExited (143) 2 seconds ago\t1700000000\t0\tuc\tguarded\t\t\t"
                        + ID
                        + "\tall\n");
        final TaskLaunch launch = new TaskLaunch(context, readingAll(dir));
        final StringWriter out = new StringWriter();
        final StringWriter err = new StringWriter();

        final int code =
                launch.promptEnded(
                        TASK, 124, new PrintWriter(out, true), new PrintWriter(err, true));

        assertThat(code).as("the run's own code").isEqualTo(124);
        assertThat(runner.invocations())
                .as("kept as a failed run is, though its start asked for --rm: %s", err)
                .noneMatch(command -> command.describe().startsWith("podman rm "));
        assertThat(state.resolve("task.log")).as("its log, which says why").exists();
        assertThat(out.toString()).contains("kept      " + TASK);
    }

    private static TaskLaunch.Request readingAll(final Path dir) {
        return new TaskLaunch.Request(
                "all",
                dir.resolve("project.yml"),
                "an-agent",
                null,
                null,
                8,
                null,
                false,
                false,
                "deny",
                false,
                org.fuin.porelo.wire.TaskMode.UNATTENDED,
                "read it all",
                null,
                null,
                1,
                null,
                java.util.Map.of(),
                java.util.Map.of(),
                null,
                null,
                null,
                null,
                null,
                null,
                true);
    }

    @Test
    void theNextRunForgetsThatTheLastOneWasEndedByItsLimit(@TempDir final Path dir)
            throws IOException {
        final Path state = dir.resolve("state");
        RunLimit.ended(state, 1, java.time.Instant.parse("2026-11-12T17:00:00Z"));

        RunLimit.started(state, 45);

        assertThat(RunLimit.endedBy(state)).isNull();
        assertThat(RunLimit.minutes(state)).as("the row's minutes").isEqualTo(45);
    }

    @Test
    void aTaskWhoseRunRecordedNoLimitHasNone(@TempDir final Path dir) {
        assertThat(RunLimit.minutes(dir)).as("an attended session, or one from before").isZero();
        assertThat(RunLimit.endedBy(dir)).isNull();
    }
}
