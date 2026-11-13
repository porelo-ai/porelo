package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.wire.Json;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link TaskRows}, which reads a task back from the row the daemon sends. Harm: a
 * watching view that renders a task differently from the listing because a field was dropped or
 * misread on the way back - a project lost puts a task under no project, a waiting state lost hides
 * the task that needs somebody.
 */
class TaskRowsTest {

    private static TaskInventory.Task everythingSet() {
        return new TaskInventory.Task(
                "porelo-uc_build",
                "uc",
                "guarded",
                "Up 4 minutes",
                true,
                3,
                "an-agent",
                "AGENT",
                "do the work",
                "porelo/build",
                "2026-11-12T09:58:00Z",
                TaskInventory.Activity.WAITING,
                "an answer",
                "ask",
                "the build",
                1,
                "RUNNING",
                "",
                "api",
                "0123456789abcdef",
                new AgentWaiting.Derived(
                        AgentWaiting.Screen.WAITING,
                        "trust",
                        true,
                        "Trust this folder?",
                        AgentWaiting.Asked.ASKED,
                        "declared",
                        "session-1",
                        AgentWaiting.Turn.CANNOT_SAY),
                Map.of("forge", "example.org"),
                Map.of(
                        "forge",
                        Map.of("grantedBy", "someone", "grantedAt", "2026-11-12T09:00:00Z")),
                "anthropic",
                null,
                HandIns.Summary.none(),
                TaskBuilds.View.none(),
                Map.of("depth", Map.of("value", "2", "from", "start")),
                "Starting the agent",
                "2026-11-12T09:59:00Z",
                2,
                "feature/build",
                true,
                "2026-11-13T09:00:00Z",
                "a-model",
                "A Model",
                "start",
                "named-build",
                "build",
                new TaskInventory.Ended(
                        TaskInventory.EndOutcome.BLOCKED, "needs a key", "2026-11-12T10:00:00Z"));
    }

    @Test
    void aTaskReadBackFromItsRowIsSentAsTheSameRow() {
        final TaskInventory.Task sent = everythingSet();
        // Through the wire's own encoding, so numbers arrive as the client receives them.
        final Object received = Json.parse(Json.write(List.of(sent.asMap())));

        final List<TaskInventory.Task> read = TaskRows.read((List<?>) received);

        assertThat(read).hasSize(1);
        assertThat(read.getFirst().asMap())
                .as("every field the row carries comes back as it went")
                .isEqualTo(sent.asMap());
        assertThat(TaskListing.row(read.getFirst(), Instant.parse("2026-11-12T10:00:30Z")))
                .as("and renders as the listing renders the task it came from")
                .isEqualTo(TaskListing.row(sent, Instant.parse("2026-11-12T10:00:30Z")));
    }

    @Test
    void aValueThisClientDoesNotKnowReadsAsTheOneThatSaysNothing() {
        final Map<String, Object> row = new java.util.HashMap<>(everythingSet().asMap());
        row.put("activity", "SOMETHING_LATER");
        row.put("screen", "SOMETHING_LATER");

        final TaskInventory.Task read = TaskRows.read(row);

        assertThat(read.activity()).isEqualTo(TaskInventory.Activity.UNKNOWN);
        assertThat(read.derived().screen()).isEqualTo(AgentWaiting.Screen.UNSEEN);
    }
}
