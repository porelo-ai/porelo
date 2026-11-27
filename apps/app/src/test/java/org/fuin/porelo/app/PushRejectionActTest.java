package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.gate.GateException;
import org.fuin.porelo.gate.review.ProfileRefused;
import org.fuin.porelo.gate.review.Push;
import org.fuin.porelo.gate.review.PushEnd;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A task's reject on its one path, with the ref and the note as stand-ins: an end kept since is
 * never overwritten, a git failure is said as one, and a pushing task that was not told is said so
 * in the event log and to the rejecting task.
 */
class PushRejectionActTest {

    private static final String PUSHER = "a".repeat(64);

    private static final String HEAD = "b".repeat(40);

    private static final String NEWER = "c".repeat(40);

    private static final Push PINNED = new Push("p", "own", "t", HEAD, null, PUSHER);

    private static final TaskIdentity READER =
            new TaskIdentity(
                    TaskIdentity.Format.ENCODED,
                    "p",
                    "reader-1",
                    "porelo-p_reader-1",
                    "d".repeat(64));

    @TempDir Path dir;

    private PoreloContext context;

    private GateEnds ends;

    private Mailbox readers;

    private final Ref ref = new Ref();

    private PushEnd waiting;

    @BeforeEach
    void aPushWaits() throws IOException {
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                                    default -> null;
                                },
                        dir);
        context =
                new PoreloContext(
                        new org.fuin.porelo.testing.FakeCommandRunner(),
                        new PoreloPaths(xdg, dir.resolve("bin")),
                        arguments -> 0);
        ends = new GateEnds(context);
        waiting = PushEnd.waiting("t", HEAD, Instant.parse("2026-11-25T10:00:00Z"));
        ends.store().keep(PUSHER, waiting);
        readers = new Mailbox(context.paths().messaging().mailbox(READER.container()));
        readers.create();
    }

    @Test
    void anEndKeptAfterTheRejectsOwnSurvivesARefThatMoved() throws Exception {
        // The next push was taken between the reject's end and its delete: its end is the newer.
        final PushEnd newer = PushEnd.waiting("t", NEWER, Instant.parse("2026-11-25T11:00:00Z"));
        ref.before = () -> ends.store().keep(PUSHER, newer);
        ref.now = NEWER;

        final ProfileRefused refused = refused((push, by, said) -> Optional.empty());

        assertThat(refused.rule()).as("the push moved").isEqualTo(TaskReject.MOVED);
        assertThat(ends.store().read(PUSHER))
                .as("the newer end stays, never overwritten by the one before the reject")
                .contains(newer);
    }

    @Test
    void aRefThatMovedPutsTheEndBeforeTheRejectBack() throws Exception {
        ref.now = NEWER;

        assertThat(refused((push, by, said) -> Optional.empty()).rule())
                .as("moved")
                .isEqualTo(TaskReject.MOVED);
        assertThat(ends.store().read(PUSHER))
                .as("no end says rejected for a push that still waits")
                .contains(waiting);
    }

    @Test
    void aGitFailureIsSaidAsOneNeverAsAPushThatMovedAndTheEndIsPutBack() throws Exception {
        // The ref still points at the judged commit: git failed, the push did not move.
        ref.now = HEAD;

        final ProfileRefused refused = refused((push, by, said) -> Optional.empty());

        assertThat(refused.rule()).as("git failed").isEqualTo(TaskReject.GATE_FAILED);
        assertThat(ends.store().read(PUSHER)).as("the end before put back").contains(waiting);

        ref.headFails = true;
        assertThat(refused((push, by, said) -> Optional.empty()).rule())
                .as("git cannot even tell where the ref stands")
                .isEqualTo(TaskReject.GATE_FAILED);
        assertThat(ends.store().read(PUSHER)).as("the end before put back").contains(waiting);
    }

    @Test
    void aDiscardThatToldThePushingTaskSaysSoInTheLogAndToTheRejectingTask() throws Exception {
        ref.deletes = true;

        PushRejection.act(
                context,
                PINNED,
                READER,
                "no",
                ref,
                (push, by, said) -> Optional.empty(),
                ends,
                new ReviewStandIns.Store());

        assertThat(detail()).as("the log names the rejecting task").contains(READER.container());
        assertThat(detail()).as("and that the task was told").doesNotContain("not told");
        assertThat(answer()).as("the rejecting task is told it was told").contains("was told");
    }

    @Test
    void eachWayThePushingTaskWasNotToldIsSaidInTheLogAndToTheRejectingTask() throws Exception {
        ref.deletes = true;
        final List<PushRejection.Note> notes = new ArrayList<>();
        notes.add((push, by, said) -> Optional.of(PersonNote.UNTOLD_NO_TASK));
        notes.add((push, by, said) -> Optional.of(PersonNote.UNTOLD_LATER_TASK));
        notes.add((push, by, said) -> Optional.of(PersonNote.UNTOLD_NO_MAILBOX));
        notes.add(
                (push, by, said) -> {
                    throw new IOException("disk full");
                });
        final List<String> why =
                List.of(
                        PersonNote.UNTOLD_NO_TASK,
                        PersonNote.UNTOLD_LATER_TASK,
                        PersonNote.UNTOLD_NO_MAILBOX,
                        PersonNote.UNTOLD_UNWRITTEN);
        for (int i = 0; i < notes.size(); i++) {
            ends.store().keep(PUSHER, waiting);
            PushRejection.act(
                    context,
                    PINNED,
                    READER,
                    "no",
                    ref,
                    notes.get(i),
                    ends,
                    new ReviewStandIns.Store());

            assertThat(detail())
                    .as("the log says the pushing task was not told, and why: %s", why.get(i))
                    .contains("not told")
                    .contains(why.get(i));
            assertThat(answer())
                    .as("so is the rejecting task: %s", why.get(i))
                    .contains("not told")
                    .contains(why.get(i));
            assertThat(ends.store().read(PUSHER).orElseThrow().end())
                    .as("nothing undone")
                    .isEqualTo(PushEnd.End.REJECTED);
        }
    }

    private ProfileRefused refused(final PushRejection.Note note) {
        final ProfileRefused refused =
                catchThrowableOfType(
                        ProfileRefused.class,
                        () ->
                                PushRejection.act(
                                        context,
                                        PINNED,
                                        READER,
                                        "no",
                                        ref,
                                        note,
                                        ends,
                                        new ReviewStandIns.Store()));
        assertThat(refused).as("refused").isNotNull();
        return refused;
    }

    /** The last event log entry's detail. */
    private String detail() throws IOException {
        final List<Map<String, Object>> entries = EventLog.of(context).entries(null);
        assertThat(entries).as("an entry").isNotEmpty();
        final Map<String, Object> last = entries.get(entries.size() - 1);
        assertThat(last.get("kind")).isEqualTo(EventLog.REJECTED);
        return (String) last.get("detail");
    }

    /** The text of the newest note in the rejecting task's inbox. */
    private String answer() throws IOException {
        final List<Path> notes;
        try (var listed = Files.list(readers.inboxNew())) {
            notes = listed.sorted().toList();
        }
        assertThat(notes).as("a note to the rejecting task").isNotEmpty();
        final StringBuilder all = new StringBuilder();
        for (final Path each : notes) {
            all.append(Json.write(Json.parseNumberTypes(Files.readString(each))));
        }
        final String last = Files.readString(notes.get(notes.size() - 1));
        assertThat(all.toString()).as("never as a person").doesNotContain("\"from\":\"person\"");
        return last;
    }

    /** Deletes as told, or fails, and answers where the ref stands after a failure. */
    private static final class Ref implements PushRejection.Ref {
        boolean deletes;
        boolean headFails;
        @Nullable String now;
        ThrowingRunnable before = () -> {};

        @Override
        public void rejectAt(final String name, final String commit) {
            try {
                before.run();
            } catch (IOException ex) {
                throw new IllegalStateException(ex);
            }
            if (!deletes) {
                throw new GateException("cannot lock ref");
            }
        }

        @Override
        public @Nullable String head(final String name) {
            if (headFails) {
                throw new GateException("git failed");
            }
            return now;
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws IOException;
    }
}
