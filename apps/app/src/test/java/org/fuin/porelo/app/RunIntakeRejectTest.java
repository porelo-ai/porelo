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
import org.fuin.porelo.gate.review.ForgeCredential;
import org.fuin.porelo.gate.review.HoldJournal;
import org.fuin.porelo.gate.review.LineAnchor;
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
import org.fuin.porelo.gate.review.ReviewError;
import org.fuin.porelo.gate.review.ReviewPlace;
import org.fuin.porelo.gate.review.ReviewPlaces;
import org.fuin.porelo.gate.review.ReviewRefusal;
import org.fuin.porelo.gate.review.ReviewRefused;
import org.fuin.porelo.gate.review.Right;
import org.fuin.porelo.gate.review.Run;
import org.fuin.porelo.gate.review.Side;
import org.fuin.porelo.gate.review.Task;
import org.fuin.porelo.gate.review.TaskParts;
import org.fuin.porelo.gate.review.TaskRights;
import org.fuin.porelo.gate.review.ThreadResolve;
import org.fuin.porelo.gate.review.ThreadState;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A reader holding {@code reject} discards exactly the waiting push it reads, through its mailbox:
 * judged by its recorded parts and place before anything goes, the push discarded last at the
 * commit its run was pinned to, and refused whenever any of that does not hold.
 */
class RunIntakeRejectTest {

    private static final String READER = "porelo-uc_reader-1";

    private static final String READER_ID = "1".repeat(64);

    /** Another task's push of the project's repository shop, waiting at the gate. */
    private static final Push WAITING =
            new Push("uc", "shop", "shop-3", "d".repeat(40), "b".repeat(40), "e".repeat(64));

    private static final String PLACE = "uc/shop/shop-3";

    private static final byte[] DOCUMENT = "{}".getBytes(StandardCharsets.UTF_8);

    private static final Body REASON = new Body("A blank password logs in; start over.");

    private static final ReviewComment COMMENT =
            new ReviewComment(
                    new LineAnchor("src/Shop.java", Side.NEW, 3, 5),
                    new Body("This loses the order."),
                    null);

    private static final TaskParts REJECTS =
            TaskParts.anothersWork(List.of(Right.OPEN_THREADS, Right.REPLY, Right.REJECT));

    @TempDir Path dir;

    private HoldJournal holds;

    private final ReviewStandIns.Check check = new ReviewStandIns.Check();
    private final ReviewStandIns.Store threads = new ReviewStandIns.Store();
    private final Map<String, RunPlace> recorded = new HashMap<>();
    private final Map<String, Run> runs = new HashMap<>();
    private final List<String> events = new ArrayList<>();
    private final Gate gate = new Gate();
    private TaskParts parts = REJECTS;
    private Push waitingNow = WAITING;
    private List<ProfileItem> submitted = List.of();
    private @Nullable Body reject = REASON;
    private String ownerId = READER_ID;

    private RunIntake intake;

    @BeforeEach
    void wire() {
        holds = new HoldJournal(dir.resolve("holds"));
        // Pinned to the work of the task that pushed it, as its first pin recorded.
        recorded.put(READER, pinnedBy(WAITING.task()));
        final Rights rights = new Rights();
        final RecordedTaskWrites.Owners owners =
                container ->
                        Optional.of(
                                new TaskIdentity(
                                        TaskIdentity.Format.ENCODED,
                                        "uc",
                                        "reader-1",
                                        READER,
                                        ownerId));
        final CommentIntake.Pushes pushes =
                (project, repository, name) -> {
                    if (waitingNow == null) {
                        throw new ReviewRefused(new ReviewRefusal(ReviewError.NO_SUCH_PUSH, null));
                    }
                    return waitingNow;
                };
        final CommentIntake.Parts commentParts =
                new CommentIntake.Parts(
                        pushes,
                        check,
                        threads,
                        new ReviewStandIns.Writer(),
                        push -> new ReviewCounts.Counts(0, 0, List.of()),
                        new RecordedTaskWrites(rights, recorded::get, owners));
        final ReviewPlace waiting =
                new WaitingPushPlace(
                        "uc",
                        "shop",
                        "shop-3",
                        commentParts,
                        (task, push) -> {
                            throw new UnsupportedOperationException("not pinned here");
                        });
        final ReviewPlaces places =
                name -> PLACE.equals(name) ? Optional.of(waiting) : Optional.empty();
        final ReleasedSender sender =
                new ReleasedSender(
                        holds,
                        places,
                        rights,
                        entry -> new ForgeCredential(entry, "secret"),
                        Clock.systemUTC());
        final ProfileReading reading =
                (final byte[] document, final ProfileChannel channel) ->
                        new ProfileRead(submitted, Set.of(), Completeness.COMPLETE, reject);
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
                                        commentParts.intake(),
                                        holds,
                                        sender::send,
                                        (kind, project, task, detail) ->
                                                events.add(kind + " " + task + " " + detail)))
                        .rejecting(
                                new TaskReject(rights, recorded::get, owners, pushes, gate)::judge);
        runs.put(READER, new Run(PLACE, new Pinned(WAITING, null), holds.startRun(READER, PLACE)));
    }

    @Test
    void theHoldersRejectDiscardsThePinnedPushWithItsReason() throws Exception {
        intake.take(READER, DOCUMENT, false);

        assertThat(gate.discarded)
                .as("the pinned push, the rejecting task and the reason, once")
                .containsExactly(WAITING + " by " + READER + ": " + REASON.text());
        assertThat(holds.undecided(null)).as("nothing held").isEmpty();
    }

    @Test
    void aTaskWithoutTheRightIsRefusedAndThePushStillWaits() {
        parts = TaskParts.anothersWork(List.of(Right.OPEN_THREADS, Right.REPLY));

        refused("RightMissing: reject (discard the push)");
        assertThat(gate.upstreamAsked).as("the gate is never touched").isZero();
    }

    @Test
    void anUnsignedRejectByAHolderDiscardsThePushAndHoldsNothing() throws Exception {
        // A reject is only ever at a waiting push: nothing leaves the machine, and the host
        // stamps the mailbox it came through, certified key or not.
        intake.take(READER, DOCUMENT, true);

        assertThat(gate.discarded)
                .as("discarded, once")
                .containsExactly(WAITING + " by " + READER + ": " + REASON.text());
        assertThat(holds.undecided(null)).as("nothing held").isEmpty();
    }

    @Test
    void aTaskHoldingRejectWhosePlaceIsItsOwnPushIsRefused() {
        // A record no start makes, planted beside the task: the push is its own container's.
        ownerId = WAITING.task();

        refused(TaskReject.OWN_PUSH);
    }

    @Test
    void aTaskWhoseRecordedPlaceIsAnotherPushIsRefused() {
        recorded.put(READER, new RunPlace(REJECTS, "shop", "shop-4"));

        refused(TaskReject.NOT_ITS_PLACE);
    }

    @Test
    void aTaskWhosePinRecordedAnotherPushingTaskIsRefused() {
        // Its pin names the work of another task than the one whose push waits under the name.
        recorded.put(READER, pinnedBy("9".repeat(64)));

        refused(TaskReject.NOT_ITS_PLACE);
        assertThat(gate.upstreamAsked).as("the gate is never touched").isZero();
    }

    /** The reader's recorded place, pinned to the work of the task of that recorded id. */
    private static RunPlace pinnedBy(final String pushedBy) {
        return new RunPlace(REJECTS, "shop", "shop-3")
                .pinnedTo(new RunPlace.Pin(WAITING.head(), WAITING.base(), null, pushedBy));
    }

    @Test
    void aPushThatMovedSinceTheRunWasPinnedIsNotDiscarded() {
        waitingNow =
                new Push("uc", "shop", "shop-3", "f".repeat(40), "b".repeat(40), "e".repeat(64));

        refused(TaskReject.MOVED);
    }

    @Test
    void aPushThatNoLongerWaitsIsNotDiscarded() {
        waitingNow = null;

        refused(TaskReject.GONE);
    }

    @Test
    void aPushTheUpstreamAlreadyHasIsNotDiscardedAndTheTaskIsTold() {
        gate.upstream = true;

        refused(TaskReject.UPSTREAM);
    }

    @Test
    void aReasonTooLongIsRefused() {
        reject = new Body("x".repeat(TaskReject.MAX_REASON + 1));

        refused(TaskReject.TOO_LONG);
    }

    @Test
    void aCommentBesideTheRejectGoesFirstAndThePushIsDiscardedLast() throws Exception {
        submitted = List.of(new ProfileItem(null, null, new Task(READER), COMMENT, null));
        gate.before = () -> assertThat(threads.kept).as("the comment went before").hasSize(1);

        intake.take(READER, DOCUMENT, false);

        assertThat(gate.discarded).hasSize(1);
    }

    @Test
    void aRejectThatFailsAfterItsCommentsWereWrittenSaysSoInWordsTheTaskIsShown() {
        // The comments are the reject's reasons and go into the pushing task's note, so they are
        // written first; a discard that fails after them says what was written and what was not.
        submitted =
                List.of(
                        new ProfileItem(null, null, new Task(READER), COMMENT, null),
                        new ProfileItem(null, null, new Task(READER), COMMENT, null));
        for (final String rule :
                List.of(
                        TaskReject.MOVED,
                        TaskReject.UPSTREAM,
                        TaskReject.UNREADABLE,
                        TaskReject.GATE_FAILED)) {
            gate.fails = rule;
            threads.kept.clear();

            assertThatThrownBy(() -> intake.take(READER, DOCUMENT, false))
                    .as("after %s", rule)
                    .isInstanceOfSatisfying(
                            ProfileRefused.class,
                            ex ->
                                    assertThat(ex.rule())
                                            .as("why, what was written, what was not")
                                            .startsWith(rule)
                                            .contains("2 items written")
                                            .contains("push not discarded")
                                            .matches("[A-Za-z0-9_./: -]{1,128}"));
            assertThat(threads.kept).as("the comments stay written").hasSize(2);
        }
        assertThat(gate.discarded).as("nothing discarded").isEmpty();
    }

    @Test
    void everyRuleOfARejectIsShownToTheTaskAsItIs() {
        for (final String rule :
                List.of(
                        TaskReject.NOT_ITS_PLACE,
                        TaskReject.OWN_PUSH,
                        TaskReject.TOO_LONG,
                        TaskReject.GONE,
                        TaskReject.MOVED,
                        TaskReject.UPSTREAM,
                        TaskReject.NOT_TAKEN,
                        TaskReject.UNREADABLE,
                        TaskReject.GATE_FAILED)) {
            assertThat(rule)
                    .as("short plain text the task is told, never the unshown rule")
                    .matches("[A-Za-z0-9_./: -]{1,128}");
        }
    }

    @Test
    void aCommentBesideARefusedRejectIsNotWritten() {
        submitted = List.of(new ProfileItem(null, null, new Task(READER), COMMENT, null));
        waitingNow =
                new Push("uc", "shop", "shop-3", "f".repeat(40), "b".repeat(40), "e".repeat(64));

        refused(TaskReject.MOVED);
        assertThat(threads.kept).as("every item judged before any goes").isEmpty();
    }

    @Test
    void aCommentAResolveAndARejectInOneSubmissionAllGoAndThePushIsDiscardedLast()
            throws Exception {
        parts = withResolve();
        final String thread =
                threads.opened(
                        WAITING,
                        org.fuin.porelo.gate.review.Origin.of(new Task("porelo-uc_shop-3")),
                        COMMENT);
        submitted =
                List.of(
                        new ProfileItem(null, null, new Task(READER), COMMENT, null),
                        new ProfileItem(
                                null, null, new Task(READER), new ThreadResolve(thread), null));
        gate.before =
                () -> {
                    assertThat(threads.kept)
                            .as("the pushing task's comment and the reader's, before the reject")
                            .hasSize(2);
                    assertThat(threads.states.get(thread))
                            .as("resolved before the reject")
                            .isEqualTo(ThreadState.RESOLVED);
                };

        intake.take(READER, DOCUMENT, false);

        assertThat(threads.stateBy.get(thread))
                .as("resolved by the reading task")
                .isEqualTo(org.fuin.porelo.gate.review.Origin.of(new Task(READER)));
        assertThat(gate.discarded)
                .as("discarded last, once")
                .containsExactly(WAITING + " by " + READER + ": " + REASON.text());
        assertThat(holds.undecided(null)).as("nothing held").isEmpty();
    }

    @Test
    void aResolveOfAThreadThePushDoesNotHoldWritesNothingAndThePushStillWaits() throws Exception {
        parts = withResolve();
        final String thread =
                threads.opened(
                        WAITING,
                        org.fuin.porelo.gate.review.Origin.of(new Task("porelo-uc_shop-3")),
                        COMMENT);
        submitted =
                List.of(
                        new ProfileItem(null, null, new Task(READER), COMMENT, null),
                        new ProfileItem(
                                null,
                                null,
                                new Task(READER),
                                new ThreadResolve("t-elsewhere"),
                                null));

        refused("check: NoSuchThread");
        assertThat(threads.kept).as("only the pushing task's comment").hasSize(1);
        assertThat(threads.states.get(thread)).as("still open").isNotEqualTo(ThreadState.RESOLVED);
        assertThat(gate.upstreamAsked).as("the gate never touched").isZero();
    }

    private static TaskParts withResolve() {
        return TaskParts.anothersWork(
                List.of(Right.OPEN_THREADS, Right.REPLY, Right.RESOLVE_THREADS, Right.REJECT));
    }

    private void refused(final String rule) {
        assertThatThrownBy(() -> intake.take(READER, DOCUMENT, false))
                .isInstanceOfSatisfying(
                        ProfileRefused.class, ex -> assertThat(ex.rule()).isEqualTo(rule));
        assertThat(gate.discarded).as("nothing discarded").isEmpty();
        assertThat(holds.undecided(null)).as("nothing held").isEmpty();
        assertThat(events).as("nothing recorded").isEmpty();
    }

    /** A gate that records what it was asked. */
    private static final class Gate implements TaskReject.Gate {
        final List<String> discarded = new ArrayList<>();
        int upstreamAsked;
        boolean upstream;
        Runnable before = () -> {};

        @Override
        public boolean alreadyUpstream(final Push push) {
            upstreamAsked++;
            return upstream;
        }

        @Nullable String fails;

        @Override
        public void discard(final Push push, final TaskIdentity rejecting, final String reason)
                throws ProfileRefused {
            before.run();
            if (fails != null) {
                throw new ProfileRefused(fails);
            }
            discarded.add(push + " by " + rejecting.container() + ": " + reason);
        }
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

    /** The reader's parts, as the test sets them. */
    private final class Rights implements TaskRights {

        @Override
        public TaskParts parts(final String task) {
            return parts;
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
            throw new IllegalStateException("a gate push uses no forge account");
        }

        @Override
        public boolean mayOpenThread(final String task, final ReviewPlace place) {
            return parts.holds(Right.OPEN_THREADS);
        }

        @Override
        public boolean mayReply(final String task, final ReviewPlace place) {
            return parts.holds(Right.REPLY);
        }

        @Override
        public boolean mayResolve(final String task, final ReviewPlace place) {
            return parts.holds(Right.RESOLVE_THREADS);
        }

        @Override
        public boolean mayReject(final String task, final ReviewPlace place) {
            return parts.holds(Right.REJECT);
        }

        @Override
        public boolean mayPin(final String task, final String head) {
            throw new UnsupportedOperationException();
        }
    }
}
