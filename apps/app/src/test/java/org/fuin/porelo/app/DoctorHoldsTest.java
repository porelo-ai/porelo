package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.fuin.porelo.gate.review.Body;
import org.fuin.porelo.gate.review.Direction;
import org.fuin.porelo.gate.review.HoldJournal;
import org.fuin.porelo.gate.review.Pinned;
import org.fuin.porelo.gate.review.Push;
import org.fuin.porelo.gate.review.ReviewReply;
import org.fuin.porelo.gate.review.Run;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Doctor's holds line names each task whose hold journal cannot be read. */
class DoctorHoldsTest {

    @TempDir Path dir;

    @Test
    void noHoldStoreIsNothingHeldAndFine() {
        assertThat(DoctorCommand.holds(dir.resolve("holds")).state())
                .as("no store at all")
                .isEqualTo(Probe.State.OK);
    }

    @Test
    void readableJournalsAreFine() {
        final Path root = dir.resolve("holds");
        holdOne(new HoldJournal(root), "porelo-demo-fix");

        assertThat(DoctorCommand.holds(root).state())
                .as("a journal that reads")
                .isEqualTo(Probe.State.OK);
    }

    @Test
    void aJournalThatCannotBeReadIsDegradedNamingItsTaskAlone() throws Exception {
        final Path root = dir.resolve("holds");
        final HoldJournal holds = new HoldJournal(root);
        holdOne(holds, "porelo-demo-fix");
        holdOne(holds, "porelo-demo-other");
        Files.writeString(root.resolve("porelo-demo-fix").resolve("holds"), "not json\n");

        final Probe probe = DoctorCommand.holds(root);

        assertThat(probe.state()).as("state").isEqualTo(Probe.State.DEGRADED);
        assertThat(probe.name()).as("name").isEqualTo("holds");
        assertThat(probe.detail())
                .as("names the task whose journal cannot be read, and no other")
                .contains("porelo-demo-fix")
                .doesNotContain("porelo-demo-other");
        assertThat(probe.action()).as("where to look").contains("porelo-demo-fix");
    }

    /** Holds one outgoing item of the task's first run at its own push. */
    private static void holdOne(final HoldJournal holds, final String task) {
        final String place = "demo/fix";
        final int number = holds.startRun(task, place);
        final Push push = new Push("demo", "demo", "fix", "1".repeat(40), null, "0123456789ab");
        holds.hold(
                new Run(place, new Pinned(push, null), number),
                task,
                Direction.OUTGOING,
                new ReviewReply("t-1", new Body("why")),
                null,
                null);
    }
}
