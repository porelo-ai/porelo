package org.fuin.porelo.daemon;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for reading a task's log from its end: a client asks for the last lines, not the whole
 * file.
 */
class LogFromItsEndTest {

    @Test
    void theLastLinesStartWhereTheyStartAndNotAtTheFilesBeginning(@TempDir Path dir)
            throws IOException {

        // The interface closed while following a log of 53 MB: Tail sent it from its first line.
        final Path log = Files.writeString(dir.resolve("task.log"), "one\ntwo\nthree\nfour\n");

        assertThat(PoreloDaemon.startOfLast(log, 2)).isEqualTo("one\ntwo\n".length());
        assertThat(PoreloDaemon.read(log, PoreloDaemon.startOfLast(log, 2)).lines())
                .containsExactly("three", "four");
        assertThat(PoreloDaemon.startOfLast(log, 10)).isZero();
        assertThat(PoreloDaemon.startOfLast(log, 0)).isEqualTo(Files.size(log));
    }

    @Test
    void aLastLineWithoutItsEndCountsAndLongLinesAreCrossed(@TempDir Path dir) throws IOException {

        // A line of 85 698 characters was measured in a real log; finding where it starts reads
        // back past several blocks.
        final String longLine = "x".repeat(200_000);
        final Path log =
                Files.writeString(
                        dir.resolve("task.log"),
                        "first\n" + longLine + "\nlast",
                        StandardCharsets.UTF_8);

        assertThat(PoreloDaemon.startOfLast(log, 1))
                .isEqualTo(("first\n" + longLine + "\n").length());
        assertThat(PoreloDaemon.startOfLast(log, 2)).isEqualTo("first\n".length());
    }

    @Test
    void aLinkInTheLogsPlaceIsNeverReadThrough(@TempDir Path dir) throws IOException {

        // logOf looked at a regular file, and the reads opened the name again: a link put there
        // in between read a file elsewhere as the log. The read opens once, never through a link.
        final Path elsewhere =
                Files.writeString(dir.resolve("elsewhere.txt"), "not the log\nof any task\n");
        final Path log = dir.resolve("task.log");
        Files.createSymbolicLink(log, elsewhere);

        assertThat(PoreloDaemon.read(log, 0).lines()).as("nothing read through the link").isEmpty();
        assertThat(PoreloDaemon.read(log, 0).position()).isZero();
        assertThat(PoreloDaemon.startOfLast(log, 1))
                .as("no position from the link's file")
                .isZero();
    }

    @Test
    void aReadCutInsideALineWithoutEndNeverSplitsACharacter(@TempDir Path dir) throws IOException {

        // A read of a line longer than a reply was cut at its byte count, inside a character of
        // three bytes: both halves came out as replacement characters.
        final String line = "\u20ac".repeat(100_000);
        final Path log =
                Files.writeString(dir.resolve("task.log"), line + "\n", StandardCharsets.UTF_8);

        final StringBuilder joined = new StringBuilder();
        long position = 0;
        int reads = 0;
        while (position < Files.size(log)) {
            final PoreloDaemon.Chunk chunk = PoreloDaemon.read(log, position);
            chunk.lines().forEach(joined::append);
            assertThat(chunk.position()).as("a read that does not move on").isGreaterThan(position);
            position = chunk.position();
            assertThat(++reads).isLessThan(20);
        }
        assertThat(reads).as("in several reads").isGreaterThan(1);
        assertThat(joined.toString()).doesNotContain("\ufffd").isEqualTo(line);
    }
}
