package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for how {@link ShieldWatchCommand} follows the events file the reader hook appends to:
 * every blocked connection the reader wrote reaches the watcher once.
 */
class ShieldWatchEventsTest {

    @TempDir Path dir;

    @Test
    void aLineCaughtHalfWrittenIsReadWhenItIsWhole() throws IOException {
        // Read while the reader was still writing it, the half line was counted as read and the
        // whole one never handed on: that connection was never asked about, and the reader writes
        // one line per attempt, so nothing asked about it again.
        final Path events = dir.resolve("events.jsonl");
        Files.writeString(
                events, "{\"destination\":\"192.0.2.1\"}\n{\"destination\":\"192.0", UTF8);
        final List<String> handled = new ArrayList<>();

        final ShieldWatchCommand.Read first = ShieldWatchCommand.linesFrom(events, 0);
        handled.addAll(first.lines());
        Files.writeString(events, ".2.2\"}\n", UTF8, StandardOpenOption.APPEND);
        final ShieldWatchCommand.Read second =
                ShieldWatchCommand.linesFrom(events, first.position());
        handled.addAll(second.lines());

        assertThat(handled)
                .as("each event once, and whole")
                .containsExactly(
                        "{\"destination\":\"192.0.2.1\"}", "{\"destination\":\"192.0.2.2\"}");
        assertThat(ShieldWatchCommand.linesFrom(events, second.position()).lines())
                .as("nothing new is read twice")
                .isEmpty();
    }

    private static final java.nio.charset.Charset UTF8 = StandardCharsets.UTF_8;
}
