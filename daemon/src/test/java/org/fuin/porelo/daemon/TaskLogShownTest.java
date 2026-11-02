package org.fuin.porelo.daemon;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.junit.jupiter.api.Test;

/** Tests for the lines {@code Tail} sends when a client asks for them formatted. */
class TaskLogShownTest {

    @Test
    void aFormatterThatFailsLeavesTheLinesAsTheyAre() {

        // The failure ended the person's log stream, and sent the agent's own error name to the
        // client; a log is still worth reading unformatted.
        for (final VarlinkException failure :
                List.of(
                        new VarlinkException("connection lost"),
                        new VarlinkException("org.fuin.porelo.Agent1.Failed", Map.of()))) {
            assertThat(
                            TaskMethods.shown(
                                    lines -> {
                                        throw failure;
                                    },
                                    List.of("one", "two")))
                    .as("after %s", failure.getMessage())
                    .containsExactly("one", "two");
        }
    }

    @Test
    void aLineTheAgentHidesIsLeftOut() {
        assertThat(
                        TaskMethods.shown(
                                lines -> {
                                    final List<String> shown = new java.util.ArrayList<>();
                                    shown.add("one");
                                    shown.add(null);
                                    return shown;
                                },
                                List.of("one", "two")))
                .containsExactly("one");
    }
}
