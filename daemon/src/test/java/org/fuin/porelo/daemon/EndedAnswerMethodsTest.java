package org.fuin.porelo.daemon;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.agent.api.AgentEnd;
import org.fuin.porelo.agent.api.Waiting;
import org.fuin.porelo.app.AgentEnding;
import org.fuin.porelo.app.PoreloContext;
import org.fuin.porelo.app.PoreloPaths;
import org.fuin.porelo.app.TaskInventory;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.wire.Json;
import org.fuin.porelo.wire.Sidecar;
import org.fuin.porelo.wire.TaskMode;
import org.fuin.porelo.wire.TaskProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The daemon's listing says what an ended run's agent answered. */
class EndedAnswerMethodsTest {

    private static final String TASK = "porelo-uc-work";

    private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");

    @TempDir Path dir;

    @Test
    void theDaemonsListCarriesTheAnswerOfAnEndedUnattendedRun() throws Exception {

        // A client that cannot read the answer from the listing reads the whole log for its last
        // line, or schedules the next step on an outcome it never saw.
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                    default -> null;
                                },
                        dir);
        final FakeCommandRunner runner =
                new FakeCommandRunner()
                        .answering("ps", TASK + "\tUp 4 minutes\t1788500000\t0\n")
                        .answering("git status", "0 0");
        final PoreloContext context =
                new PoreloContext(runner, new PoreloPaths(xdg, dir.resolve("bin")), args -> 0);
        final Path state = Files.createDirectories(context.paths().tasks().containerState(TASK));
        new Sidecar(
                        Sidecar.VERSION,
                        "uc",
                        "guarded",
                        state.resolve("r.nft").toString(),
                        state.resolve("dns.conf").toString(),
                        "/usr/bin/porelo",
                        state.toString())
                .writeTo(state.resolve("sidecar.json"));
        new TaskProfile(
                        TaskProfile.VERSION,
                        "example",
                        TaskMode.UNATTENDED,
                        "work",
                        null,
                        "2026-11-10T11:50:00Z",
                        "prompt")
                .writeTo(state);
        final Path log =
                Files.writeString(
                        state.resolve("task.log"),
                        Json.write(Map.of("type", "result", "result", "Fixed it.\nOutcome: done"))
                                + "\n{\"type\":\"agent_end\"}\n");
        Files.setLastModifiedTime(log, FileTime.from(NOW.minusSeconds(120)));
        final AgentEnding ending =
                new AgentEnding(
                        runner,
                        Clock.fixed(NOW, ZoneOffset.UTC),
                        (agent, lines) -> new AgentEnd(true, "", AgentEnd.AGENT, null),
                        agent ->
                                "example".equals(agent)
                                        ? new Waiting.LastMessage(
                                                Map.of("type", "result"), "result")
                                        : null);

        final Map<?, ?> task =
                (Map<?, ?>)
                        ((List<?>)
                                        TaskMethods.list(new TaskInventory(context, null, ending))
                                                .get("tasks"))
                                .getFirst();

        assertThat(task.get("activity")).isEqualTo("ENDED");
        final Map<?, ?> ended = (Map<?, ?>) task.get("agentEnded");
        assertThat(ended.get("answer")).isEqualTo("Fixed it.\nOutcome: done");
        assertThat(ended.get("answerCut")).isEqualTo(false);
    }
}
