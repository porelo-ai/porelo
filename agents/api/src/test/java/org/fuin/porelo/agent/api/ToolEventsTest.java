package org.fuin.porelo.agent.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.fuin.porelo.wire.varlink.VarlinkClient;
import org.fuin.porelo.wire.varlink.VarlinkServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for the tool calls an agent reads out of lines of its output, over the agent protocol. */
class ToolEventsTest {

    /** A sub-agent named by an issue id. */
    private static final String ISSUE = "B622"; // not-a-citation

    /** A stand-in adapter: {@code run <id> <command>}, {@code bg <id> <job> <command>}, ... */
    private static final Agent STUB =
            new Agent() {
                @Override
                public AgentDefinition definition() {
                    throw new UnsupportedOperationException();
                }

                @Override
                public String name() {
                    return "tools";
                }

                @Override
                public List<ToolEvent> toolEvents(final String line) {
                    final String[] word = line.split(" ", 4);
                    return switch (word[0]) {
                        case "run" ->
                                List.of(
                                        new ToolEvent(
                                                0,
                                                word[1],
                                                ToolEvent.Phase.START,
                                                "Bash",
                                                ToolEvent.Kind.SHELL,
                                                word[2] + (word.length > 3 ? " " + word[3] : ""),
                                                false,
                                                null,
                                                "2026-11-11T10:00:00Z"));
                        case "bg" ->
                                List.of(
                                        new ToolEvent(
                                                0,
                                                word[1],
                                                ToolEvent.Phase.START,
                                                "Bash",
                                                ToolEvent.Kind.SHELL,
                                                word[3],
                                                true,
                                                word[2],
                                                null),
                                        new ToolEvent(
                                                0,
                                                word[1],
                                                ToolEvent.Phase.END,
                                                "Bash",
                                                ToolEvent.Kind.SHELL,
                                                null,
                                                true,
                                                word[2],
                                                null));
                        case "done" ->
                                List.of(
                                        new ToolEvent(
                                                0,
                                                word[1],
                                                ToolEvent.Phase.END,
                                                "Bash",
                                                ToolEvent.Kind.SHELL,
                                                null,
                                                false,
                                                null,
                                                null));
                        default -> List.of();
                    };
                }
            };

    @Test
    void theServerAnswersEachEventWithTheIndexOfItsLineInTheBatch(@TempDir final Path dir)
            throws Exception {

        final Path socket = dir.resolve("agent.sock");
        try (AgentServer server = new AgentServer(STUB, socket)) {
            Thread.ofPlatform().daemon().start(server::serve);
            try (VarlinkClient client = new VarlinkClient(socket)) {
                final Optional<List<ToolEvent>> events =
                        InstalledAgent.toolEvents(
                                client,
                                List.of(
                                        "thinking",
                                        "run 1 mvn -q test",
                                        "bg 2 job7 sleep 60",
                                        "done 1"));

                assertThat(events)
                        .as("an agent that has the method answers")
                        .contains(
                                List.of(
                                        new ToolEvent(
                                                1,
                                                "1",
                                                ToolEvent.Phase.START,
                                                "Bash",
                                                ToolEvent.Kind.SHELL,
                                                "mvn -q test",
                                                false,
                                                null,
                                                "2026-11-11T10:00:00Z"),
                                        new ToolEvent(
                                                2,
                                                "2",
                                                ToolEvent.Phase.START,
                                                "Bash",
                                                ToolEvent.Kind.SHELL,
                                                "sleep 60",
                                                true,
                                                "job7",
                                                null),
                                        new ToolEvent(
                                                2,
                                                "2",
                                                ToolEvent.Phase.END,
                                                "Bash",
                                                ToolEvent.Kind.SHELL,
                                                null,
                                                true,
                                                "job7",
                                                null),
                                        new ToolEvent(
                                                3,
                                                "1",
                                                ToolEvent.Phase.END,
                                                "Bash",
                                                ToolEvent.Kind.SHELL,
                                                null,
                                                false,
                                                null,
                                                null)));
            }
        }
    }

    @Test
    void anAgentThatReadsNoToolCallsAnswersNoneAndIsNotCannotSay(@TempDir final Path dir)
            throws Exception {

        final Agent plain =
                new Agent() {
                    @Override
                    public AgentDefinition definition() {
                        throw new UnsupportedOperationException();
                    }
                };
        assertThat(plain.toolEvents("run 1 ls")).as("the default reads nothing").isEmpty();

        final Path socket = dir.resolve("agent.sock");
        try (AgentServer server = new AgentServer(plain, socket)) {
            Thread.ofPlatform().daemon().start(server::serve);
            try (VarlinkClient client = new VarlinkClient(socket)) {
                assertThat(InstalledAgent.toolEvents(client, List.of("run 1 ls")))
                        .as("an agent built at version 5 answers, with no events")
                        .contains(List.of());
            }
        }
    }

    @Test
    void anAgentWithoutTheMethodCannotSayAndIsNeverAnEmptyList(@TempDir final Path dir)
            throws Exception {

        final Path socket = dir.resolve("old.sock");
        final VarlinkServer old = new VarlinkServer(socket, AgentProtocol.INTERFACE);
        old.method("Describe", (parameters, replies) -> replies.last(Map.of()));
        Thread.ofPlatform().daemon().start(old);
        try (old;
                VarlinkClient client = new VarlinkClient(socket)) {
            assertThat(InstalledAgent.toolEvents(client, List.of("run 1 ls")))
                    .as("an adapter built before version 5 cannot say, never 'no tool ran'")
                    .isEmpty();
        }
    }

    @Test
    void everyKindCrossesTheWireInItsContractSpelling() {

        assertThat(Arrays.stream(ToolEvent.Kind.values()).map(ToolEvent.Kind::wire))
                .as("the closed set of the contract")
                .containsExactly(
                        "shell", "edit", "read", "search", "web", "sub-agent", "mcp", "other");
        for (final ToolEvent.Kind kind : ToolEvent.Kind.values()) {
            assertThat(ToolEvent.Kind.of(kind.wire())).as(kind.wire()).isEqualTo(kind);
        }
        assertThat(ToolEvent.Kind.of("teleport"))
                .as("a kind this Porelo does not know is other, never left out")
                .isEqualTo(ToolEvent.Kind.OTHER);
    }

    @Test
    void anEventCrossesTheWireAndComesBackTheSame() {

        final ToolEvent full =
                new ToolEvent(
                        4,
                        "toolu_1",
                        ToolEvent.Phase.START,
                        "Task",
                        ToolEvent.Kind.SUB_AGENT,
                        "make all",
                        true,
                        "b1",
                        "2026-11-11T10:00:00Z");
        final ToolEvent bare =
                new ToolEvent(
                        0,
                        "toolu_2",
                        ToolEvent.Phase.END,
                        "Read",
                        ToolEvent.Kind.READ,
                        null,
                        false,
                        null,
                        null);

        assertThat(full.asMap())
                .as("the wire's spellings")
                .containsEntry("phase", "START")
                .containsEntry("kind", "sub-agent");
        assertThat(ToolEvent.of(full.asMap())).isEqualTo(full);
        assertThat(ToolEvent.of(bare.asMap())).isEqualTo(bare);
        assertThat(bare.asMap())
                .as("an absent value is left out")
                .doesNotContainKeys("command", "jobId", "at");
        assertThat(ToolEvent.of(Map.of("line", 0, "id", "x", "phase", "LATER")))
                .as("an event without a phase this Porelo knows is no event")
                .isNull();
    }

    @Test
    void aSubAgentsNameAndTheCallItRanUnderCrossTheWire(@TempDir final Path dir) throws Exception {

        final ToolEvent subAgent =
                new ToolEvent(
                        0,
                        "toolu_sub",
                        ToolEvent.Phase.START,
                        "Task",
                        ToolEvent.Kind.SUB_AGENT,
                        null,
                        false,
                        null,
                        null,
                        ISSUE,
                        null);
        final ToolEvent underIt =
                new ToolEvent(
                        1,
                        "toolu_shell",
                        ToolEvent.Phase.START,
                        "Bash",
                        ToolEvent.Kind.SHELL,
                        "mvn test",
                        false,
                        null,
                        null,
                        null,
                        "toolu_sub");

        assertThat(subAgent.asMap()).as("the name it was given").containsEntry("name", ISSUE);
        assertThat(underIt.asMap())
                .as("the sub-agent call it ran under")
                .containsEntry("parent", "toolu_sub");
        assertThat(ToolEvent.of(subAgent.asMap())).isEqualTo(subAgent);
        assertThat(ToolEvent.of(underIt.asMap())).isEqualTo(underIt);

        final Agent nesting =
                new Agent() {
                    @Override
                    public AgentDefinition definition() {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public List<ToolEvent> toolEvents(final String line) {
                        return List.of(line.equals("sub") ? subAgent : underIt);
                    }
                };
        final Path socket = dir.resolve("agent.sock");
        try (AgentServer server = new AgentServer(nesting, socket)) {
            Thread.ofPlatform().daemon().start(server::serve);
            try (VarlinkClient client = new VarlinkClient(socket)) {
                assertThat(InstalledAgent.toolEvents(client, List.of("sub", "shell")))
                        .as("both reach Porelo over the agent protocol")
                        .contains(List.of(subAgent, underIt));
            }
        }
    }

    @Test
    void anEndsDurationCrossesTheWire() {

        final ToolEvent ended =
                new ToolEvent(
                        2,
                        "toolu_3",
                        ToolEvent.Phase.END,
                        "bash",
                        ToolEvent.Kind.SHELL,
                        null,
                        false,
                        null,
                        null,
                        null,
                        null,
                        Long.valueOf(4_250));

        assertThat(ended.asMap()).as("milliseconds").containsEntry("durationMs", 4_250L);
        assertThat(ToolEvent.of(ended.asMap())).isEqualTo(ended);
        assertThat(ToolEvent.of(Map.of("line", 0, "id", "x", "phase", "END", "durationMs", 7)))
                .as("a whole number as a smaller type reads the same")
                .extracting(ToolEvent::durationMs)
                .isEqualTo(7L);
        assertThat(
                        new ToolEvent(
                                        0,
                                        "x",
                                        ToolEvent.Phase.END,
                                        "t",
                                        ToolEvent.Kind.OTHER,
                                        null,
                                        false,
                                        null,
                                        null)
                                .asMap())
                .as("absent, left out")
                .doesNotContainKey("durationMs");
    }
}
