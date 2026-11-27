package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.fuin.porelo.gate.review.Body;
import org.fuin.porelo.gate.review.Completeness;
import org.fuin.porelo.gate.review.CurrentRuns;
import org.fuin.porelo.gate.review.Decision;
import org.fuin.porelo.gate.review.ForgeCredential;
import org.fuin.porelo.gate.review.Held;
import org.fuin.porelo.gate.review.HeldState;
import org.fuin.porelo.gate.review.HoldJournal;
import org.fuin.porelo.gate.review.Kept;
import org.fuin.porelo.gate.review.LineAnchor;
import org.fuin.porelo.gate.review.Origin;
import org.fuin.porelo.gate.review.Pinned;
import org.fuin.porelo.gate.review.Producer;
import org.fuin.porelo.gate.review.ProfileChannel;
import org.fuin.porelo.gate.review.ProfileItem;
import org.fuin.porelo.gate.review.ProfileRead;
import org.fuin.porelo.gate.review.ProfileReading;
import org.fuin.porelo.gate.review.ProfileRefused;
import org.fuin.porelo.gate.review.Push;
import org.fuin.porelo.gate.review.ReviewComment;
import org.fuin.porelo.gate.review.ReviewCounts;
import org.fuin.porelo.gate.review.ReviewItem;
import org.fuin.porelo.gate.review.ReviewPlace;
import org.fuin.porelo.gate.review.ReviewPlaces;
import org.fuin.porelo.gate.review.Right;
import org.fuin.porelo.gate.review.Run;
import org.fuin.porelo.gate.review.Side;
import org.fuin.porelo.gate.review.Task;
import org.fuin.porelo.gate.review.TaskParts;
import org.fuin.porelo.gate.review.TaskRights;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A task that reads another task's waiting push, through the whole intake: its recorded rights and
 * place judge it, its items go into that push's threads as the task itself, and an unsigned one
 * waits for the owner account and is then written with no account and no vault.
 */
class RunIntakeReaderTest {

    private static final String READER = "porelo-uc_reader-1";

    /** Another task's push of the project's repository shop, waiting at the gate. */
    private static final Push WAITING =
            new Push("uc", "shop", "shop-3", "d".repeat(40), "b".repeat(40), "e".repeat(64));

    private static final String PLACE = "uc/shop/shop-3";

    private static final byte[] DOCUMENT = "{}".getBytes(StandardCharsets.UTF_8);

    private static final ReviewComment COMMENT =
            new ReviewComment(
                    new LineAnchor("src/Shop.java", Side.NEW, 3, 5),
                    new Body("This loses the order."),
                    null);

    /** A reader that may open threads and reply, as its start recorded it. */
    private static final TaskParts READS =
            new TaskParts(
                    TaskParts.Workspace.FOREIGN,
                    List.of(),
                    false,
                    TaskParts.Account.REVIEW,
                    Set.of(Right.OPEN_THREADS, Right.REPLY));

    @TempDir Path dir;

    private HoldJournal holds;

    private final ReviewStandIns.Check check = new ReviewStandIns.Check();
    private final ReviewStandIns.Store threads = new ReviewStandIns.Store();
    private final Rights rights = new Rights();
    private final Map<String, RunPlace> recorded = new HashMap<>();
    private final Map<String, Run> runs = new HashMap<>();
    private final List<String> lent = new ArrayList<>();
    private final List<String> events = new ArrayList<>();
    private List<ProfileItem> submitted = List.of();

    private ReleasedSender sender;

    private RunIntake intake;

    @BeforeEach
    void wire() {
        holds = new HoldJournal(dir.resolve("holds"));
        // Pinned to the work of the task that pushed it, as its first pin recorded.
        recorded.put(
                READER,
                new RunPlace(READS, "shop", "shop-3")
                        .pinnedTo(new RunPlace.Pin(WAITING.head(), null, null, WAITING.task())));
        final CommentIntake.Parts parts =
                new CommentIntake.Parts(
                        (project, repository, name) -> WAITING,
                        check,
                        threads,
                        new ReviewStandIns.Writer(),
                        push -> new ReviewCounts.Counts(0, 0, List.of()),
                        new RecordedTaskWrites(
                                rights,
                                recorded::get,
                                container ->
                                        Optional.of(
                                                new TaskIdentity(
                                                        TaskIdentity.Format.ENCODED,
                                                        "uc",
                                                        "reader-1",
                                                        READER,
                                                        "1".repeat(64)))));
        final ReviewPlace waiting =
                new WaitingPushPlace(
                        "uc",
                        "shop",
                        "shop-3",
                        parts,
                        (task, push) -> {
                            throw new UnsupportedOperationException("not pinned here");
                        });
        final ReviewPlaces places =
                name -> PLACE.equals(name) ? Optional.of(waiting) : Optional.empty();
        sender =
                new ReleasedSender(
                        holds,
                        places,
                        rights,
                        entry -> {
                            lent.add(entry);
                            return new ForgeCredential(entry, "secret");
                        },
                        Clock.systemUTC());
        final ProfileReading reading =
                (final byte[] document, final ProfileChannel channel) ->
                        new ProfileRead(submitted, Set.of(), Completeness.COMPLETE);
        intake =
                new RunIntake(
                        reading,
                        check,
                        rights,
                        holds,
                        new Runs(runs),
                        places,
                        new HoldingRule(
                                project -> {
                                    throw new IllegalStateException(
                                            "a waiting push never asks the class");
                                }),
                        new UnheldWrites(
                                parts.intake(),
                                holds,
                                sender::send,
                                (kind, project, task, detail) ->
                                        events.add(kind + " " + task + " " + detail)));
        runs.put(READER, new Run(PLACE, new Pinned(WAITING, null), holds.startRun(READER, PLACE)));
    }

    private void submitting(final ReviewItem item) {
        submitted = List.of(new ProfileItem(null, null, new Task(READER), item, null));
    }

    @Test
    void aReadersCommentIsInTheWaitingPushsThreadsAtOnceAndNothingIsHeld() throws Exception {
        // The pushing and the reading task see each other's items at once; nobody waits.
        submitting(COMMENT);

        intake.take(READER, DOCUMENT, false);

        assertThat(threads.threads(WAITING))
                .as("a thread of the push it reads, opened by the reader")
                .singleElement()
                .satisfies(
                        thread ->
                                assertThat(thread.items())
                                        .extracting(Kept::origin, Kept::item)
                                        .containsExactly(
                                                org.assertj.core.groups.Tuple.tuple(
                                                        Origin.of(new Task(READER)), COMMENT)));
        assertThat(holds.undecided(null)).as("nothing held").isEmpty();
        assertThat(events)
                .as("in the event log by task and place")
                .containsExactly("unheld " + READER + " comment at " + PLACE + ", run 1");
    }

    @Test
    void aReadersUnsignedCommentIsInThePushsThreadsAtOnceAndNothingIsHeld() throws Exception {
        // A task without a certified key writes at a waiting push as one with it does.
        submitting(COMMENT);

        intake.take(READER, DOCUMENT, true);

        assertThat(holds.undecided(null)).as("nothing held").isEmpty();
        assertThat(threads.threads(WAITING))
                .as("in the push's threads, as the task")
                .singleElement()
                .satisfies(
                        thread ->
                                assertThat(thread.items().get(0).origin())
                                        .isEqualTo(Origin.of(new Task(READER))));
        assertThat(lent).as("no credential lent").isEmpty();
    }

    @Test
    void aHeldItemAtAWaitingPushIsAfterItsReleaseInTheThreadsWithNoCredential() throws Exception {
        // Nothing is held at a waiting push any more; one held before still goes when released.
        final Held one =
                holds.hold(
                        runs.get(READER),
                        READER,
                        org.fuin.porelo.gate.review.Direction.OUTGOING,
                        COMMENT,
                        null,
                        null,
                        true);
        holds.decide(READER, one.id(), one.digest(), Decision.RELEASE);
        assertThat(sender.send()).as("handed to the place's write").isEqualTo(1);

        assertThat(threads.threads(WAITING))
                .as("in the push's threads, as the task")
                .singleElement()
                .satisfies(
                        thread ->
                                assertThat(thread.items().get(0).origin())
                                        .isEqualTo(Origin.of(new Task(READER))));
        assertThat(holds.find(READER, one.id()))
                .as("its answer marked")
                .get()
                .extracting(Held::state)
                .isEqualTo(HeldState.DELIVERED);
        assertThat(lent).as("no credential lent").isEmpty();
        assertThat(rights.entriesAsked).as("no vault entry asked").isEmpty();
    }

    @Test
    void aReadersItemForAnotherPushOfTheProjectIsRefusedAndNothingIsKept() {
        // Its recorded place names another push; the one its run pins is not its to write in.
        recorded.put(READER, new RunPlace(READS, "shop", "shop-4"));
        submitting(COMMENT);

        assertThatThrownBy(() -> intake.take(READER, DOCUMENT, false))
                .isInstanceOfSatisfying(
                        ProfileRefused.class,
                        ex ->
                                assertThat(ex.rule())
                                        .as("the threads' rule")
                                        .isEqualTo("threads: NoSuchThread"));
        assertThat(threads.kept).as("nothing kept").isEmpty();
        assertThat(holds.undecided(null)).as("nothing held").isEmpty();
        assertThat(events)
                .as("recorded before the threads were asked: never less than went")
                .containsExactly("unheld " + READER + " comment at " + PLACE + ", run 1");
    }

    /** Each task's run, from a map. */
    private record Runs(Map<String, Run> byTask) implements CurrentRuns {
        @Override
        public Optional<Run> of(final String task) {
            return Optional.ofNullable(byTask.get(task));
        }

        @Override
        public void begin(final String task, final Run run) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void end(final String task) {
            throw new UnsupportedOperationException();
        }
    }

    /** The reader's recorded parts, and a record of every vault entry asked for. */
    private static final class Rights implements TaskRights {
        final List<String> entriesAsked = new ArrayList<>();

        @Override
        public TaskParts parts(final String task) {
            if (!READER.equals(task)) {
                throw new IllegalStateException("task " + task + " recorded no parts");
            }
            return READS;
        }

        @Override
        public boolean mayPush(final String task) {
            return false;
        }

        @Override
        public Producer postingAccount(final String task, final ReviewPlace place) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String postingEntry(final String task, final ReviewPlace place) {
            entriesAsked.add(task);
            throw new IllegalStateException("a gate push uses no forge account");
        }

        @Override
        public boolean mayOpenThread(final String task, final ReviewPlace place) {
            return parts(task).holds(Right.OPEN_THREADS);
        }

        @Override
        public boolean mayReply(final String task, final ReviewPlace place) {
            return parts(task).holds(Right.REPLY);
        }

        @Override
        public boolean mayResolve(final String task, final ReviewPlace place) {
            return false;
        }

        @Override
        public boolean mayReject(final String task, final ReviewPlace place) {
            return false;
        }

        @Override
        public boolean mayPin(final String task, final String head) {
            throw new UnsupportedOperationException();
        }
    }
}
