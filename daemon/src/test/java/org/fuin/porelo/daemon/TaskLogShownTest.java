package org.fuin.porelo.daemon;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for the lines {@code Tail} sends when a client asks for them formatted. */
class TaskLogShownTest {

    @TempDir Path dir;

    @Test
    void aFormatterThatFailsLeavesTheLinesAsTheyAre() throws Exception {

        // The failure ended the person's log stream, and sent the agent's own error name to the
        // client; a log is still worth reading unformatted.
        final Path log = Files.writeString(dir.resolve("task.log"), "one\ntwo\n");
        for (final VarlinkException failure :
                List.of(
                        new VarlinkException("connection lost"),
                        new VarlinkException("org.fuin.porelo.Agent1.Failed", Map.of()))) {
            assertThat(
                            TaskMethods.tail(
                                            log,
                                            0,
                                            lines -> {
                                                throw failure;
                                            })
                                    .lines())
                    .as("after %s", failure.getMessage())
                    .containsExactly("one", "two");
        }
    }

    @Test
    void aLineTheAgentHidesIsLeftOut() throws Exception {
        final Path log = Files.writeString(dir.resolve("task.log"), "one\ntwo\n");

        final TaskMethods.Tailed tailed =
                TaskMethods.tail(
                        log,
                        0,
                        lines -> {
                            final List<String> shown = new java.util.ArrayList<>();
                            shown.add("one");
                            shown.add(null);
                            return shown;
                        });

        assertThat(tailed.lines()).containsExactly("one");
        assertThat(tailed.next()).as("past the hidden line too").isEqualTo(8);
    }
}
