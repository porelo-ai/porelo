package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.fuin.porelo.agent.api.AgentEnd;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.wire.TaskMode;
import org.fuin.porelo.wire.TaskProfile;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** A run's failure is an outcome; a finished run alone declares no completion. */
class TaskInventoryEndedTest {
    private static final String TASK = "porelo-uc-work";
    private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");
    @TempDir Path dir;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void onlyAnUnfinishedRunDerivesAnOutcomeWhenNothingWasDeclared(boolean finished)
            throws Exception {
        // Treating a failed run as finished, or a successful run as declared done, misdirects a
        // planner.
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                                    case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                    default -> null;
                                },
                        dir);
        final FakeCommandRunner runner =
                new FakeCommandRunner()
                        .answering("ps", TASK + "\tUp 4 minutes\t1788500000\t0\tuc\tguarded\n")
                        .answering("git status", "0 0");
        final PoreloContext context =
                new PoreloContext(runner, new PoreloPaths(xdg, dir.resolve("bin")), args -> 0);
        final Path state = Files.createDirectories(context.paths().tasks().containerState(TASK));
        new TaskProfile(
                        TaskProfile.VERSION,
                        "example",
                        TaskMode.UNATTENDED,
                        "work",
                        null,
                        "2026-11-10T11:50:00Z",
                        "prompt")
                .writeTo(state);
        final Instant at = NOW.minusSeconds(120);
        Files.setLastModifiedTime(
                Files.writeString(state.resolve("task.log"), "ended\n"), FileTime.from(at));
        final String error = finished ? "" : "Provider refused the run";
        final AgentEnding ending =
                new AgentEnding(
                        runner,
                        Clock.fixed(NOW, ZoneOffset.UTC),
                        (agent, lines) -> new AgentEnd(finished, error, AgentEnd.AGENT, null));
        final TaskInventory inventory = new TaskInventory(context, null, ending);
        final Map<String, Object> task = inventory.tasks().getFirst().asMap();
        if (finished) {
            assertThat(task)
                    .as("a finished run did not declare its work done")
                    .doesNotContainKey("ended");
        } else {
            assertThat(task.get("ended"))
                    .as("the planner reads the run's failure without parsing its answer")
                    .isEqualTo(Map.of("outcome", "FAILED", "why", error, "at", at.toString()));
        }
        new TaskDone(context.paths()).record(TASK, TaskDone.Outcome.BLOCKED, "Waiting for review");
        assertThat((Map<?, ?>) inventory.tasks().getFirst().asMap().get("ended"))
                .as("the task's own declaration takes precedence over its run")
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
}
