package org.fuin.porelo.gate.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** How a task's last push ended is kept per task container, whole, and refused when malformed. */
class PushEndsTest {

    private static final String TASK = "a".repeat(64);

    private static final String LATER_TASK = "b".repeat(64);

    private static final String WAITED = "1".repeat(40);

    private static final String FORWARDED = "2".repeat(40);

    private static final Instant AT = Instant.parse("2026-11-25T10:15:30.789Z");

    @TempDir Path dir;

    @Test
    void aRejectIsReadBackByAnotherInstanceWithItsOpenThreadsAndItsTimeInWholeSeconds()
            throws Exception {
        new PushEnds(dir.resolve("review")).keep(TASK, PushEnd.rejected("t", WAITED, 2, AT));

        final PushEnd read = new PushEnds(dir.resolve("review")).read(TASK).orElseThrow();

        assertThat(read)
                .as("the reject, its commit and the open threads it carried")
                .isEqualTo(
                        new PushEnd(
                                "t",
                                WAITED,
                                PushEnd.End.REJECTED,
                                2,
                                null,
                                null,
                                Instant.parse("2026-11-25T10:15:30Z")));
        assertThat(Files.readString(dir.resolve("review").resolve(TASK).resolve(PushEnds.ENTRY)))
                .as("the time is written in UTC, RFC 3339, whole seconds")
                .contains("\"at\":\"2026-11-25T10:15:30Z\"");
    }

    @Test
    void anApprovalKeepsTheCommitItForwardedAndTheBranch() throws Exception {
        final PushEnds ends = new PushEnds(dir.resolve("review"));

        ends.keep(TASK, PushEnd.approved("t", WAITED, FORWARDED, "main", AT));

        final PushEnd read = ends.read(TASK).orElseThrow();
        assertThat(read.end()).as("approved").isEqualTo(PushEnd.End.APPROVED);
        assertThat(read.mergedCommit()).as("the forwarded commit").isEqualTo(FORWARDED);
        assertThat(read.branch()).as("the branch it went onto").isEqualTo("main");
        assertThat(read.openThreads()).as("an approval carries no thread").isZero();
    }

    @Test
    void aLandingKeepsTheCommitFoundOnTheBranch() throws Exception {
        final PushEnds ends = new PushEnds(dir.resolve("review"));

        ends.keep(TASK, PushEnd.landed("t", WAITED, "main", AT));

        final PushEnd read = ends.read(TASK).orElseThrow();
        assertThat(read.end()).as("landed").isEqualTo(PushEnd.End.LANDED);
        assertThat(read.mergedCommit()).as("the commit found merged").isEqualTo(WAITED);
    }

    @Test
    void aLaterEndReplacesTheEarlierOneAndClearingLeavesNone() throws Exception {
        final PushEnds ends = new PushEnds(dir.resolve("review"));
        ends.keep(TASK, PushEnd.rejected("t", WAITED, 0, AT));
        ends.keep(TASK, PushEnd.approved("t", FORWARDED, FORWARDED, "main", AT));
        assertThat(ends.read(TASK).orElseThrow().end())
                .as("the last end stands")
                .isEqualTo(PushEnd.End.APPROVED);

        ends.clear(TASK);

        assertThat(ends.read(TASK)).as("nothing kept once the next push waits").isEmpty();
        assertThat(Files.list(dir.resolve("review").resolve(TASK)))
                .as("no temporary file is left beside the entry")
                .isEmpty();
    }

    @Test
    void aPushThatWaitsIsKeptWithTheTimeItWasTakenAndReplacesTheEarlierEnd() throws Exception {
        final PushEnds ends = new PushEnds(dir.resolve("review"));
        ends.keep(TASK, PushEnd.rejected("t", WAITED, 2, AT));

        ends.keep(TASK, PushEnd.waiting("t", FORWARDED, AT));

        final PushEnd read = new PushEnds(dir.resolve("review")).read(TASK).orElseThrow();
        assertThat(read.end()).as("the push waits").isEqualTo(PushEnd.End.WAITING);
        assertThat(read.commit()).as("the commit taken").isEqualTo(FORWARDED);
        assertThat(read.waitingSince())
                .as("when it was taken, in whole seconds")
                .isEqualTo(Instant.parse("2026-11-25T10:15:30Z"));
        assertThat(read.mergedCommit()).as("nothing went").isNull();
        assertThat(Files.readString(dir.resolve("review").resolve(TASK).resolve(PushEnds.ENTRY)))
                .as("the earlier end is gone")
                .contains("\"end\":\"WAITING\"")
                .doesNotContain("REJECTED");
    }

    @Test
    void anEndCarriesTheTimeItsPushStartedWaiting() throws Exception {
        final PushEnds ends = new PushEnds(dir.resolve("review"));
        final Instant later = Instant.parse("2026-11-25T12:00:00Z");

        ends.keep(TASK, PushEnd.rejected("t", WAITED, 1, later).waitedSince(AT));

        final PushEnd read = ends.read(TASK).orElseThrow();
        assertThat(read.waitingSince())
                .as("the waiting time carried along, in whole seconds")
                .isEqualTo(Instant.parse("2026-11-25T10:15:30Z"));
        assertThat(read.at()).as("the reject's own time").isEqualTo(later);
        assertThat(PushEnd.rejected("t", WAITED, 1, later).waitingSince())
                .as("an end of a push taken before waiting times were kept has none")
                .isNull();
    }

    @Test
    void aTaskInAnotherContainerUnderTheSameNameFindsNone() throws Exception {
        final PushEnds ends = new PushEnds(dir.resolve("review"));
        ends.keep(TASK, PushEnd.rejected("t", WAITED, 2, AT));

        assertThat(ends.read(LATER_TASK))
                .as("the later container inherits no end of the earlier one")
                .isEmpty();
        ends.clear(LATER_TASK);
        assertThat(ends.read(TASK)).as("clearing another container's end clears none").isPresent();
    }

    @Test
    void theEntryIsOwnerOnly() throws Exception {
        new PushEnds(dir.resolve("review")).keep(TASK, PushEnd.rejected("t", WAITED, 0, AT));

        final Path container = dir.resolve("review").resolve(TASK);
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(container)))
                .as("the container's directory")
                .isEqualTo("rwx------");
        assertThat(
                        PosixFilePermissions.toString(
                                Files.getPosixFilePermissions(container.resolve(PushEnds.ENTRY))))
                .as("the entry")
                .isEqualTo("rw-------");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "{\"version\":1,\"name\":\"t\",\"commit\":\"",
                "[]",
                "{\"version\":2,\"name\":\"t\",\"commit\":\"1111111111111111111111111111111111111111\",\"end\":\"REJECTED\",\"openThreads\":0,\"at\":\"2026-11-25T10:15:30Z\"}",
                "{\"version\":1,\"name\":\"t\",\"commit\":\"1111111111111111111111111111111111111111\",\"end\":\"MERGED\",\"openThreads\":0,\"at\":\"2026-11-25T10:15:30Z\"}",
                "{\"version\":1,\"name\":\"t\",\"commit\":\"1111111111111111111111111111111111111111\",\"end\":\"REJECTED\",\"at\":\"2026-11-25T10:15:30Z\"}",
                "{\"version\":1,\"name\":\"t\",\"commit\":\"1111111111111111111111111111111111111111\",\"end\":\"APPROVED\",\"openThreads\":0,\"at\":\"2026-11-25T10:15:30Z\"}",
                "{\"version\":1,\"name\":\"t\",\"commit\":\"1111111111111111111111111111111111111111\",\"end\":\"REJECTED\",\"openThreads\":0,\"at\":\"yesterday\"}",
                "{\"version\":1,\"name\":\"t\",\"commit\":\"1111111111111111111111111111111111111111\",\"end\":\"REJECTED\",\"openThreads\":0,\"at\":\"2026-11-25T10:15:30Z\",\"waitingSince\":\"yesterday\"}",
                "{\"version\":1,\"name\":\"t\",\"commit\":\"1111111111111111111111111111111111111111\",\"end\":\"WAITING\",\"openThreads\":0,\"mergedCommit\":\"1111111111111111111111111111111111111111\",\"branch\":\"main\",\"at\":\"2026-11-25T10:15:30Z\",\"waitingSince\":\"2026-11-25T10:15:30Z\"}",
                "{\"version\":1,\"name\":\"t\",\"commit\":\"1111111111111111111111111111111111111111\",\"end\":\"WAITING\",\"openThreads\":0,\"at\":\"2026-11-25T10:15:30Z\",\"waitingSince\":\"2026-11-25T09:00:00Z\"}"
            })
    void aMalformedEntryIsRefusedNeverReadAsNoEnd(final String text) throws Exception {
        final PushEnds ends = new PushEnds(dir.resolve("review"));
        ends.keep(TASK, PushEnd.rejected("t", WAITED, 0, AT));
        Files.writeString(
                dir.resolve("review").resolve(TASK).resolve(PushEnds.ENTRY),
                text,
                StandardCharsets.UTF_8);

        assertThatThrownBy(() -> ends.read(TASK))
                .as("refused loud: " + text)
                .isInstanceOf(IOException.class)
                .hasMessageContaining("cannot be read");
    }

    @Test
    void anEntryThatIsALinkIsRefused() throws Exception {
        final PushEnds ends = new PushEnds(dir.resolve("review"));
        ends.keep(TASK, PushEnd.rejected("t", WAITED, 0, AT));
        final Path entry = dir.resolve("review").resolve(TASK).resolve(PushEnds.ENTRY);
        Files.move(entry, dir.resolve("elsewhere"));
        Files.createSymbolicLink(entry, dir.resolve("elsewhere"));

        assertThatThrownBy(() -> ends.read(TASK))
                .as("nothing is followed through a link")
                .isInstanceOf(IOException.class)
                .hasMessageContaining("no plain file");
    }

    @Test
    void aNameThatIsNoRecordedContainerIdIsRefused() {
        final PushEnds ends = new PushEnds(dir.resolve("review"));

        assertThatThrownBy(() -> ends.read("../t"))
                .as("a reusable name or a path is no key")
                .isInstanceOf(IllegalArgumentException.class);
    }
}
