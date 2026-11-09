package org.fuin.porelo.daemon;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

/** A task's MCP endpoint cannot offer or invoke a human sign-in wait. */
class McpStartSignInTest {
    @Test
    void aTaskCannotSeeOrPassTheSignInWait() {
        // A task must not hold a start call waiting for a person's sign-in.
        final String description = PoreloDaemon.description();
        assertThat(description)
                .as("the person's daemon offers the wait")
                .contains("waitForSignIn: ?bool");
        assertThat(McpTaskSockets.cut(description, Set.of("Start")))
                .as("the description used to list a task's tools must omit the sign-in wait")
                .contains("method Start(", "agent: ?string")
                .doesNotContain("waitForSignIn");
        assertThat(McpTaskSockets.START_PARAMETERS)
                .as("a task cannot smuggle the wait into Start even if it knows its name")
                .doesNotContain("waitForSignIn");
    }
}
