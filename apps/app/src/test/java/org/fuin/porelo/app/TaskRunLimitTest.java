package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.time.Clock;
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
