package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.fuin.porelo.core.project.SecurityClass;
import org.fuin.porelo.gate.review.Body;
import org.fuin.porelo.gate.review.CommentCheck;
import org.fuin.porelo.gate.review.CommentReading;
import org.fuin.porelo.gate.review.Completeness;
import org.fuin.porelo.gate.review.Conversation;
import org.fuin.porelo.gate.review.CurrentRuns;
import org.fuin.porelo.gate.review.Decision;
import org.fuin.porelo.gate.review.Direction;
import org.fuin.porelo.gate.review.ForgeCredential;
import org.fuin.porelo.gate.review.ForgeReply;
import org.fuin.porelo.gate.review.Held;
import org.fuin.porelo.gate.review.HeldState;
import org.fuin.porelo.gate.review.Kept;
import org.fuin.porelo.gate.review.LineAnchor;
import org.fuin.porelo.gate.review.OwnerAccount;
import org.fuin.porelo.gate.review.Pinned;
import org.fuin.porelo.gate.review.Producer;
import org.fuin.porelo.gate.review.ProfileChannel;
import org.fuin.porelo.gate.review.ProfileItem;
import org.fuin.porelo.gate.review.ProfileRead;
import org.fuin.porelo.gate.review.ProfileReading;
import org.fuin.porelo.gate.review.ProfileRefused;
import org.fuin.porelo.gate.review.PullRequestFacts;
import org.fuin.porelo.gate.review.PullRequestRef;
import org.fuin.porelo.gate.review.PullRequestState;
import org.fuin.porelo.gate.review.Push;
import org.fuin.porelo.gate.review.ReviewComment;
import org.fuin.porelo.gate.review.ReviewError;
import org.fuin.porelo.gate.review.ReviewHolds;
import org.fuin.porelo.gate.review.ReviewItem;
import org.fuin.porelo.gate.review.ReviewPlace;
import org.fuin.porelo.gate.review.ReviewRefusal;
import org.fuin.porelo.gate.review.ReviewReply;
import org.fuin.porelo.gate.review.Right;
import org.fuin.porelo.gate.review.Run;
import org.fuin.porelo.gate.review.Side;
import org.fuin.porelo.gate.review.Task;
import org.fuin.porelo.gate.review.TaskParts;
import org.fuin.porelo.gate.review.TaskRights;
import org.fuin.porelo.gate.review.WriteOutcome;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** A run's submission: read from its task's channel, judged by its rights, checked, then held. */
class RunIntakeTest {

    private static final String TASK = "shop-2";

    private static final String PLACE = "https://github.com/uc/shop/pull/7";

    private static final Push PINNED =
            new Push("uc", "shop", "shop-2", "a".repeat(40), "b".repeat(40), "c".repeat(64));

    private static final byte[] DOCUMENT = "{}".getBytes(StandardCharsets.UTF_8);

    private static final ReviewComment COMMENT =
            new ReviewComment(
                    new LineAnchor("src/Shop.java", Side.NEW, 3, 5),
                    new Body("This loses the order."),
                    null);

    private static final ForgeReply REPLY = new ForgeReply("1001", new Body("Fixed."));

    private final Reading reading = new Reading();
    private final Check check = new Check();
    private final Holds holds = new Holds();
    private final Roles roles = new Roles();
    private final Runs runs = new Runs();
    private final Map<String, ReviewPlace> places = new HashMap<>();

    private final RunIntake intake =
            new RunIntake(
                    reading,
                    check,
                    roles,
                    holds,
                    runs,
                    name -> Optional.ofNullable(places.get(name)),
                    // A guarded project's pull request: every item waits for the owner account.
                    new HoldingRule(project -> SecurityClass.GUARDED),
                    new UnheldWrites(
                            new CommentIntake(
                                    check,
                                    new ReviewStandIns.Store(),
                                    (container, push, item) -> false),
                            holds,
                            () -> {
                                throw new UnsupportedOperationException("nothing goes at once");
                            },
                            (kind, project, task, detail) -> {
                                throw new UnsupportedOperationException("nothing goes at once");
                            }));

    private static final PullRequestFacts FACTS =
            new PullRequestFacts(
                    PullRequestRef.of(java.net.URI.create(PLACE)),
                    "https://github.com/uc/shop",
                    PINNED.head(),
                    "f".repeat(40),
                    "f".repeat(40),
                    PullRequestState.OPEN,
                    false,
                    null,
                    null,
                    null,
                    null);

    /** Today's author: own work, push and reply. */
    private static final TaskParts AUTHOR = TaskParts.ownWork();

    /** Today's reviewer: another task's work, reply and openThreads. */
    private static final TaskParts REVIEWER =
            TaskParts.anothersWork(List.of(Right.REPLY, Right.OPEN_THREADS));

    private void runAs(final TaskParts parts) {
        roles.parts = parts;
        runs.byTask.put(TASK, new Run(PLACE, new Pinned(PINNED, FACTS), 2));
        places.put(PLACE, new Place(PLACE));
    }

    private static ProfileItem submitted(final ReviewItem item) {
        return new ProfileItem(null, null, new Task(TASK), item, null);
    }

    @Test
    void aSubmissionOfATaskWithoutARunIsRefusedAndNothingIsHeld() {
        // Without a run there is no place and pinned commit a release could be bound to.
        reading.items = List.of(submitted(REPLY));

        assertThatThrownBy(() -> intake.take(TASK, DOCUMENT, false))
                .as("a task without a run")
                .isInstanceOfSatisfying(
                        ProfileRefused.class,
                        ex ->
                                assertThat(ex.rule())
                                        .as("the run's rule")
                                        .isEqualTo("run: none for the task"));
        assertThat(reading.channels).as("the document never read").isEmpty();
        assertThat(holds.held).as("nothing held").isEmpty();
        assertThat(check.asked).as("nothing checked").isEmpty();
    }

    @Test
    void aTaskWithoutARunIsRefusedWhileAnotherTasksRunExists() {
        // A run is the task's own; another task's run must never carry its words to a place.
        runAs(REVIEWER);
        reading.items = List.of(submitted(REPLY));

        assertThatThrownBy(() -> intake.take("shop-3", DOCUMENT, false))
                .as("a second task without a run")
                .isInstanceOfSatisfying(
                        ProfileRefused.class,
                        ex ->
                                assertThat(ex.rule())
                                        .as("the run's rule")
                                        .isEqualTo("run: none for the task"));
        assertThat(reading.channels).as("the document never read").isEmpty();
        assertThat(holds.held).as("nothing held").isEmpty();
    }

    @Test
    void anItemNotTheTasksOwnRefusesTheWholeSubmissionAndHoldsNothing() {
        // A submission names no producer, id or thread; one that does was not stamped by this
        // task's channel.
        runAs(REVIEWER);
        final List<ProfileItem> foreign =
                List.of(
                        new ProfileItem(null, null, new Task("shop-3"), REPLY, null),
                        new ProfileItem(null, null, new OwnerAccount(), REPLY, null),
                        new ProfileItem("77", null, new Task(TASK), REPLY, null),
                        new ProfileItem(null, "1001", new Task(TASK), REPLY, null));

        for (final ProfileItem item : foreign) {
            reading.items = List.of(submitted(COMMENT), item);

            assertThatThrownBy(() -> intake.take(TASK, DOCUMENT, false))
                    .as("an item %s", item)
                    .isInstanceOfSatisfying(
                            ProfileRefused.class,
                            ex ->
                                    assertThat(ex.rule())
                                            .as("the intake's rule")
                                            .isEqualTo("intake: item not the task's own"));
        }
        assertThat(holds.held).as("nothing held").isEmpty();
    }

    @Test
    void anEmptySubmissionIsRefused() {
        // An empty submission would reach the owner account as a run that said nothing.
        runAs(REVIEWER);
        reading.items = List.of();

        assertThatThrownBy(() -> intake.take(TASK, DOCUMENT, false))
                .as("a submission without items")
                .isInstanceOfSatisfying(
                        ProfileRefused.class,
                        ex ->
                                assertThat(ex.rule())
                                        .as("the submission's rule")
                                        .isEqualTo("submission: no item"));
        assertThat(holds.held).as("nothing held").isEmpty();
    }

    @Test
    void aCheckedItemIsHeldOutgoingWithTheRunsPinnedState() throws Exception {
        // The release must reach exactly the commit the run read, so the hold carries the run.
        runAs(REVIEWER);
        reading.items = List.of(submitted(COMMENT), submitted(REPLY));

        intake.take(TASK, DOCUMENT, false);

        assertThat(reading.channels)
                .as("read as the task's submission")
                .containsExactly(new ProfileChannel.FromTask(new Task(TASK)));
        assertThat(check.pushes).as("checked against the pinned state").containsOnly(PINNED);
        assertThat(holds.held)
                .as(
                        "each item held, in order, outgoing, with nothing received, at the pinned"
                                + " commit")
                .extracting(Held::item, Held::direction, Held::received, Held::commit, Held::run)
                .containsExactly(
                        tuple(COMMENT, Direction.OUTGOING, null, PINNED.head(), 2),
                        tuple(REPLY, Direction.OUTGOING, null, PINNED.head(), 2));
        assertThat(holds.held)
                .as("bound to the run's place and the task")
                .allSatisfy(
                        held -> {
                            assertThat(held.place()).as("place").isEqualTo(PLACE);
                            assertThat(held.task()).as("task").isEqualTo(TASK);
                        });
    }

    @Test
    void anUnsignedSubmissionsItemsAreEachHeldMarkedUnsignedAndASignedOnesUnmarked()
            throws Exception {
        // The owner account sees which review no certified key signed before it decides.
        runAs(REVIEWER);
        reading.items = List.of(submitted(COMMENT), submitted(REPLY));

        intake.take(TASK, DOCUMENT, true);
        intake.take(TASK, DOCUMENT, false);

        assertThat(holds.held)
                .as("the unsigned submission's items marked, the signed one's not")
                .extracting(Held::unsigned)
                .containsExactly(true, true, false, false);
    }

    @Test
    void aRefusedCheckRefusesTheWholeSubmissionAndHoldsNothing() {
        // Half a submission held would show the owner account a review its task never sent.
        runAs(REVIEWER);
        final ReviewReply later = new ReviewReply("1002", new Body("bad"));
        reading.items = List.of(submitted(COMMENT), submitted(REPLY), submitted(later));
        check.refuse.add(REPLY);

        assertThatThrownBy(() -> intake.take(TASK, DOCUMENT, false))
                .as("the second item refused by the check")
                .isInstanceOfSatisfying(
                        ProfileRefused.class,
                        ex ->
                                assertThat(ex.rule())
                                        .as("the check's rule, never the text")
                                        .isEqualTo("check: TextRefused (bidi)"));
        assertThat(holds.held).as("not even the item before it").isEmpty();
        assertThat(check.asked).as("the third item never checked").containsExactly(COMMENT, REPLY);
    }

    @Test
    void aNewCommentOfATaskWithoutOpenThreadsIsRefusedAndNothingIsHeld() {
        // A task opens a thread only where its start recorded openThreads; today's author has not.
        runAs(AUTHOR);
        reading.items = List.of(submitted(REPLY), submitted(COMMENT));

        assertThatThrownBy(() -> intake.take(TASK, DOCUMENT, false))
                .as("an author's new comment")
                .isInstanceOfSatisfying(
                        ProfileRefused.class,
                        ex ->
                                assertThat(ex.rule())
                                        .as("the missing right's rule")
                                        .isEqualTo(RunIntake.MAY_NOT_OPEN)
                                        .isEqualTo("RightMissing: openThreads (open a thread)"));
        assertThat(holds.held).as("not even its reply").isEmpty();
        assertThat(roles.askedOpen).as("asked of the run's place").containsExactly(PLACE);
    }

    @Test
    void anAuthorsReplyIsHeld() throws Exception {
        runAs(AUTHOR);
        reading.items = List.of(submitted(REPLY));

        intake.take(TASK, DOCUMENT, false);

        assertThat(holds.held).extracting(Held::item).as("the reply held").containsExactly(REPLY);
    }

    @Test
    void aReplyOfATaskWithoutReplyIsRefusedAndNothingIsHeld() {
        // A task answers in a thread only where its start recorded reply.
        runAs(TaskParts.anothersWork(List.of(Right.OPEN_THREADS)));
        final ReviewReply answer = new ReviewReply("1002", new Body("Agreed."));
        reading.items = List.of(submitted(COMMENT), submitted(answer));

        assertThatThrownBy(() -> intake.take(TASK, DOCUMENT, false))
                .as("a reply without reply")
                .isInstanceOfSatisfying(
                        ProfileRefused.class,
                        ex ->
                                assertThat(ex.rule())
                                        .as("the missing right's rule")
                                        .isEqualTo(RunIntake.MAY_NOT_REPLY)
                                        .isEqualTo("RightMissing: reply (reply in a thread)"));
        assertThat(holds.held).as("not even its comment").isEmpty();
        assertThat(check.asked).as("nothing checked").isEmpty();
        assertThat(roles.askedReply).as("asked of the run's place").containsExactly(PLACE);
    }

    @Test
    void aTaskAtItsOwnWorkWithOpenThreadsHasItsCommentHeld() throws Exception {
        // The refusal of a task at its own work is gone with the role: openThreads alone decides.
        runAs(
                new TaskParts(
                        TaskParts.Workspace.OWN,
                        List.of(),
                        true,
                        TaskParts.Account.AUTHOR,
                        Set.of(Right.PUSH, Right.REPLY, Right.OPEN_THREADS)));
        reading.items = List.of(submitted(COMMENT));

        intake.take(TASK, DOCUMENT, false);

        assertThat(holds.held)
                .extracting(Held::item)
                .as("the comment held")
                .containsExactly(COMMENT);
    }

    @Test
    void aReviewersCommentIsHeld() throws Exception {
        runAs(REVIEWER);
        reading.items = List.of(submitted(COMMENT));

        intake.take(TASK, DOCUMENT, false);

        assertThat(holds.held)
                .extracting(Held::item)
                .as("the comment held")
                .containsExactly(COMMENT);
    }

    @Test
    void aDocumentTheReadingRefusesHoldsNothing() {
        runAs(REVIEWER);
        reading.refusal = new ProfileRefused("schema: porelo/sourceKind in a submission");

        assertThatThrownBy(() -> intake.take(TASK, DOCUMENT, false))
                .as("the reading's refusal")
                .isSameAs(reading.refusal);
        assertThat(holds.held).as("nothing held").isEmpty();
    }

    @Test
    void aSubmissionWhoseRunsPlaceIsNoLongerFoundIsRefusedAndNothingIsHeld() {
        // A hold bound to a place nobody can find could never be released where it was meant.
        runAs(AUTHOR);
        places.clear();
        reading.items = List.of(submitted(REPLY));

        assertThatThrownBy(() -> intake.take(TASK, DOCUMENT, false))
                .as("a run whose place is gone")
                .isInstanceOfSatisfying(
                        ProfileRefused.class,
                        ex ->
                                assertThat(ex.rule())
                                        .as("the run's rule")
                                        .isEqualTo("run: place unreachable"));
        assertThat(holds.held).as("nothing held").isEmpty();
    }

    /** Answers fixed items or a fixed refusal, and records the channel. */
    private static final class Reading implements ProfileReading {
        List<ProfileItem> items = List.of();
        @Nullable ProfileRefused refusal;
        final List<ProfileChannel> channels = new ArrayList<>();

        @Override
        public ProfileRead read(final byte[] document, final ProfileChannel channel)
                throws ProfileRefused {
            channels.add(channel);
            if (refusal != null) {
                throw refusal;
            }
            return new ProfileRead(items, Set.of(), Completeness.COMPLETE);
        }
    }

    /** Answers each task's own run, or none. */
    private static final class Runs implements CurrentRuns {
        final Map<String, Run> byTask = new HashMap<>();

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

    /** Refuses the items named, and records what it was asked against which push. */
    private static final class Check implements CommentCheck {
        final Set<ReviewItem> refuse = new HashSet<>();
        final List<ReviewItem> asked = new ArrayList<>();
        final List<Push> pushes = new ArrayList<>();

        @Override
        public Optional<ReviewRefusal> check(final Push push, final ReviewItem item) {
            asked.add(item);
            pushes.add(push);
            return refuse.contains(item)
                    ? Optional.of(new ReviewRefusal(ReviewError.TEXT_REFUSED, "bidi"))
                    : Optional.empty();
        }
    }

    /** Answers by fixed parts, and records the places asked. */
    private static final class Roles implements TaskRights {
        TaskParts parts = TaskParts.ownWork();
        final List<String> askedOpen = new ArrayList<>();
        final List<String> askedReply = new ArrayList<>();

        @Override
        public TaskParts parts(final String task) {
            return parts;
        }

        @Override
        public boolean mayPush(final String task) {
            return parts.holds(Right.PUSH);
        }

        @Override
        public Producer postingAccount(final String task, final ReviewPlace place) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String postingEntry(final String task, final ReviewPlace place) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean mayOpenThread(final String task, final ReviewPlace place) {
            askedOpen.add(place.name());
            return parts.holds(Right.OPEN_THREADS);
        }

        @Override
        public boolean mayReply(final String task, final ReviewPlace place) {
            askedReply.add(place.name());
            return parts.holds(Right.REPLY);
        }

        @Override
        public boolean mayResolve(final String task, final ReviewPlace place) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean mayReject(final String task, final ReviewPlace place) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean mayPin(final String task, final String head) {
            throw new UnsupportedOperationException();
        }
    }

    /** Keeps what it is asked to hold, in order. */
    private static final class Holds implements ReviewHolds {

        @Override
        public Held sending(final String task, final String id) {
            throw new UnsupportedOperationException("not used here");
        }

        final List<Held> held = new ArrayList<>();

        @Override
        public int startRun(final String task, final String place) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Held hold(
                final Run run,
                final String task,
                final Direction direction,
                final ReviewItem item,
                final @Nullable Kept received,
                final @Nullable String rule) {
            return hold(run, task, direction, item, received, rule, false);
        }

        @Override
        public Held hold(
                final Run run,
                final String task,
                final Direction direction,
                final ReviewItem item,
                final @Nullable Kept received,
                final @Nullable String rule,
                final boolean unsigned) {
            final Held one =
                    new Held(
                            "h" + held.size(),
                            direction,
                            task,
                            run.number(),
                            run.place(),
                            run.push().head(),
                            item,
                            received,
                            "f".repeat(64),
                            Instant.EPOCH,
                            HeldState.UNDECIDED,
                            rule,
                            null,
                            unsigned);
            held.add(one);
            return one;
        }

        @Override
        public Optional<Held> incoming(
                final String task, final String place, final String received) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Held> undecided(final @Nullable String task) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Held> find(final String task, final String id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Held decide(
                final String task, final String id, final String digest, final Decision decision) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Held> due(final Instant now) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void mark(
                final String task, final String id, final WriteOutcome outcome, final Instant now) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int count(final String task) {
            throw new UnsupportedOperationException();
        }
    }

    /** A place by name alone; the intake neither pins, reads nor writes it. */
    private record Place(String name) implements ReviewPlace {
        @Override
        public Pinned pin(final String task) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Conversation conversation(final Pinned pinned, final ForgeCredential credential) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<byte[]> pages(final Pinned pinned, final Conversation conversation) {
            throw new UnsupportedOperationException();
        }

        @Override
        public WriteOutcome write(final Held held, final ForgeCredential credential) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CommentReading reading() {
            throw new UnsupportedOperationException();
        }
    }
}
