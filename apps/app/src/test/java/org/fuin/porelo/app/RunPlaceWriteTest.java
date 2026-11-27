package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A run's record is replaced whole: a write that does not finish leaves the record before it, never
 * a truncated one.
 */
class RunPlaceWriteTest {

    private static final RunPlace.Pin PIN =
            new RunPlace.Pin("a".repeat(40), null, null, "a".repeat(12));

    @TempDir Path state;

    @Test
    void theRecordIsReplacedByAnotherFileNeverRewrittenInPlace() throws IOException {
        reviewer().writeTo(state);
        final Path earlier = state.resolve("earlier");
        Files.createLink(earlier, state.resolve(RunPlace.FILE));
        final String before = Files.readString(earlier, StandardCharsets.UTF_8);
        reviewer().pinnedTo(PIN).writeTo(state);
        assertThat(Files.readString(earlier, StandardCharsets.UTF_8))
                .as("the file the earlier record was written to")
                .isEqualTo(before);
        assertThat(RunPlace.readFrom(state).pinned()).as("the record now").isEqualTo(PIN);
    }

    @Test
    void aWriteThatCannotFinishKeepsTheEarlierRecord() throws IOException {
        reviewer().writeTo(state);
        Files.setPosixFilePermissions(state, PosixFilePermissions.fromString("r-x------"));
        try {
            assertThatThrownBy(() -> reviewer().pinnedTo(PIN).writeTo(state))
                    .as("a record written where nothing new can be made")
                    .isInstanceOf(IOException.class);
        } finally {
            Files.setPosixFilePermissions(state, PosixFilePermissions.fromString("rwx------"));
        }
        assertThat(RunPlace.readFrom(state))
                .as("the record after the failed write")
                .isEqualTo(reviewer());
        try (var left = Files.list(state)) {
            assertThat(left.map(each -> each.getFileName().toString()).toList())
                    .as("what the state holds")
                    .containsExactly(RunPlace.FILE);
        }
    }

    @Test
    void theReplacedRecordIsReadableByItsOwnerAlone() throws IOException {
        reviewer().writeTo(state);
        reviewer().pinnedTo(PIN).writeTo(state);
        assertThat(
                        PosixFilePermissions.toString(
                                Files.getPosixFilePermissions(state.resolve(RunPlace.FILE))))
                .as("the record's permissions")
                .isEqualTo("rw-------");
    }

    private static RunPlace reviewer() {
        return new RunPlace(
                org.fuin.porelo.gate.review.TaskParts.anothersWork(
                        java.util.List.of(
                                org.fuin.porelo.gate.review.Right.REPLY,
                                org.fuin.porelo.gate.review.Right.OPEN_THREADS)),
                null,
                "shop-2");
    }
}
