package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.gate.review.Body;
import org.fuin.porelo.gate.review.Direction;
import org.fuin.porelo.gate.review.HoldJournal;
import org.fuin.porelo.gate.review.Pinned;
import org.fuin.porelo.gate.review.Push;
import org.fuin.porelo.gate.review.ReviewReply;
import org.fuin.porelo.gate.review.Role;
import org.fuin.porelo.gate.review.Run;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A task's review holds go with the task, so a new task of the name starts with none. */
class HoldJournalLifetimeTest {

    private static final String TASK = "porelo-uc-shell";

    private final StoppingCommandRunner runner = new StoppingCommandRunner();

    private PoreloContext context(final Path dir) throws IOException {
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                                    case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                    default -> null;
                                },
                        dir);
        final PoreloContext context =
                new PoreloContext(runner, new PoreloPaths(xdg, dir.resolve("bin")), arguments -> 0);
        TaskIdentityFixture.record(context.paths(), TASK, "uc", "shell", "a".repeat(64));
        return context;
    }

    private static void holdOne(final HoldJournal holds) {
        final String place = "uc/shell";
        final int number = holds.startRun(TASK, place);
        final Push push = new Push("uc", "uc", "shell", "1".repeat(40), null, "0123456789ab");
        holds.hold(
                new Run(place, Role.AUTHOR, new Pinned(push, null), number),
                TASK,
                Direction.OUTGOING,
                new ReviewReply("t-1", new Body("why")),
                null,
                null);
    }

    @Test
    void aRemovedTasksHoldsAreGoneForANewTaskOfTheSameName(@TempDir final Path dir)
            throws IOException {
        final PoreloContext context = context(dir);
        final HoldJournal holds = new HoldJournal(context.paths().gate().reviewHolds());
        holdOne(holds);
        assertThat(holds.count(TASK)).as("the item is held before the removal").isEqualTo(1);
        runner.answering(
                "ps",
                LegacyTaskFixture.listed(
                        "porelo-uc-shell\tExited (143)\t1700000000\t1700000100\tuc\tguarded\n",
                        "a".repeat(64)));

        new TaskControl(context).remove(TASK, false, true);

        assertThat(holds.undecided(TASK)).as("no item listed for the same name").isEmpty();
        assertThat(holds.count(TASK)).as("no item counted for the same name").isZero();
    }
}
