package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.fuin.porelo.core.project.SecurityClass;
import org.fuin.porelo.gate.review.Body;
import org.fuin.porelo.gate.review.CommentReading;
import org.fuin.porelo.gate.review.Completeness;
import org.fuin.porelo.gate.review.Conversation;
import org.fuin.porelo.gate.review.CurrentRuns;
import org.fuin.porelo.gate.review.Decision;
import org.fuin.porelo.gate.review.ForgeCredential;
import org.fuin.porelo.gate.review.ForgeReply;
import org.fuin.porelo.gate.review.Held;
import org.fuin.porelo.gate.review.HeldState;
import org.fuin.porelo.gate.review.HoldJournal;
import org.fuin.porelo.gate.review.LineAnchor;
import org.fuin.porelo.gate.review.Origin;
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
import org.fuin.porelo.gate.review.ReviewCounts;
import org.fuin.porelo.gate.review.ReviewItem;
import org.fuin.porelo.gate.review.ReviewPlace;
import org.fuin.porelo.gate.review.ReviewPlaces;
import org.fuin.porelo.gate.review.ReviewReply;
import org.fuin.porelo.gate.review.Run;
import org.fuin.porelo.gate.review.Side;
import org.fuin.porelo.gate.review.Task;
import org.fuin.porelo.gate.review.TaskParts;
import org.fuin.porelo.gate.review.TaskRights;
import org.fuin.porelo.gate.review.ThreadResolve;
import org.fuin.porelo.gate.review.ThreadState;
import org.fuin.porelo.gate.review.WriteOutcome;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Whether a run's checked items wait for the owner account or go at once: by the run's place and
 * its project's class alone.
 */
class RunIntakeHoldingTest {

    private static final String TASK = "porelo-uc_shop-2";

    private static final Push PINNED =
            new Push("uc", "shop", "shop-2", "a".repeat(40), "b".repeat(40), "c".repeat(64));

    /** The waiting push's place, named as the gate names it. */
    private static final String GATE_PUSH = "uc/shop/shop-2";

    private static final PullRequestRef REF =
            PullRequestRef.of(URI.create("https://github.com/uc/shop/pull/7"));

    private static final String PULL_REQUEST = REF.url().toString();

    private static final PullRequestFacts FACTS =
            new PullRequestFacts(
                    REF,
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

    private static final byte[] DOCUMENT = "{}".getBytes(StandardCharsets.UTF_8);

    private static final ReviewComment COMMENT =
            new ReviewComment(
                    new LineAnchor("src/Shop.java", Side.NEW, 3, 5),
                    new Body("This loses the order."),
                    null);

    @TempDir Path dir;

    private HoldJournal holds;

    private final Reading reading = new Reading();
    private final ReviewStandIns.Check check = new ReviewStandIns.Check();
    private final ReviewStandIns.Store threads = new ReviewStandIns.Store();
    private final Rights rights = new Rights();
    private final Runs runs = new Runs();
    private final Map<String, ReviewPlace> places = new HashMap<>();
    private final Map<String, SecurityClass> classes = new HashMap<>();
    private final List<String> events = new ArrayList<>();

    /** Whether the event log can be written. */
    private boolean logWritten = true;

    private final Writes writes = new Writes();

    private RunIntake intake;

    private ReleasedSender sender;

    @BeforeEach
    void wire() {
        holds = new HoldJournal(dir.resolve("holds"));
        final ReviewPlaces named = name -> Optional.ofNullable(places.get(name));
        sender =
                new ReleasedSender(
                        holds,
                        named,
                        rights,
                        entry -> new ForgeCredential(entry, "secret"),
                        Clock.systemUTC());
        intake =
                new RunIntake(
                        reading,
                        check,
                        rights,
                        holds,
                        runs,
                        named,
                        new HoldingRule(
                                project -> {
                                    final SecurityClass known = classes.get(project);
                                    if (known == null) {
                                        throw new IllegalStateException("no project file");
                                    }
                                    return known;
                                }),
                        new UnheldWrites(
                                new CommentIntake(check, threads, writes),
                                holds,
                                // The daemon's one sending path, run here at once.
                                sender::send,
                                (kind, project, task, detail) -> {
                                    if (!logWritten) {
                                        throw new java.io.IOException("no space left");
                                    }
                                    events.add(kind + " " + project + " " + task + " " + detail);
                                }));
    }

    /** Begins the task's run at a place, a pull request with its facts, a gate push without. */
    private void runAt(final String place, final @Nullable PullRequestFacts facts) {
        final int number = holds.startRun(TASK, place);
        runs.byTask.put(TASK, new Run(place, new Pinned(PINNED, facts), number));
        places.put(
                place,
                GATE_PUSH.equals(place)
                        ? new WaitingPushPlace(
                                "uc",
                                "shop",
                                "shop-2",
                                new CommentIntake.Parts(
                                        (project, repository, name) -> PINNED,
                                        check,
                                        threads,
                                        new ReviewStandIns.Writer(),
                                        push -> new ReviewCounts.Counts(0, 0, List.of()),
                                        writes),
                                (task, push) -> {
                                    throw new UnsupportedOperationException("not pinned here");
                                })
                        : new Place(place));
    }

    /** The pull request's stand-in, which plays the forge. */
    private Place forge() {
        return (Place) places.get(PULL_REQUEST);
    }

    private static ProfileItem submitted(final ReviewItem item) {
        return new ProfileItem(null, null, new Task(TASK), item, null);
    }

    @Test
    void aGatePushWritesTheCommentIntoTheThreadsAtOnceAndHoldsNothing() throws Exception {
        // At a gate push nobody waits: the pushing and the reading task see each other's items at
        // once, written as the task itself.
        runAt(GATE_PUSH, null);
        reading.items = List.of(submitted(COMMENT));

        intake.take(TASK, DOCUMENT, false);

        assertThat(holds.undecided(null)).as("nothing held").isEmpty();
        assertThat(threads.kept)
                .as("the comment in the push's threads, as the task")
                .singleElement()
                .satisfies(
                        kept -> {
                            assertThat(kept.item()).as("the item").isEqualTo(COMMENT);
                            assertThat(kept.origin())
                                    .as("its producer")
                                    .isEqualTo(Origin.of(new Task(TASK)));
                        });
        assertThat(threads.threads(PINNED)).as("a thread of the run's push").hasSize(1);
        assertThat(events)
                .as("the event log names task and place, never the text")
                .containsExactly("unheld uc " + TASK + " comment at " + GATE_PUSH + ", run 1");
    }

    @Test
    void aGatePushTakesAReplyInAThreadOfTheRunsPushAtOnce() throws Exception {
        // A task holding reply answers in any thread of the push that is its place.
        runAt(GATE_PUSH, null);
        final String thread =
                threads.opened(PINNED, Origin.of(new Task("porelo-uc_shop-3")), COMMENT);
        // The profile's porelo/replyTo reaches the intake as a forge reply, whatever the place.
        reading.items = List.of(submitted(new ForgeReply(thread, new Body("Fixed."))));

        intake.take(TASK, DOCUMENT, false);

        assertThat(holds.undecided(null)).as("nothing held").isEmpty();
        assertThat(threads.kept)
                .as("the reply kept after the comment it answers, in Porelo's thread")
                .hasSize(2)
                .last()
                .satisfies(
                        kept ->
                                assertThat(kept.item())
                                        .as("the reply")
                                        .isEqualTo(new ReviewReply(thread, new Body("Fixed."))));
        assertThat(writes.asked)
                .as("the task's rights and place asked by its container, for the run's push")
                .containsExactly(TASK + " " + PINNED.name());
    }

    @Test
    void aGatePushRefusesAReplyInAThreadOfAnotherPushAndWritesNothing() {
        // The task's place is its run's push; a thread elsewhere is not one it can reach.
        runAt(GATE_PUSH, null);
        reading.items =
                List.of(
                        submitted(COMMENT),
                        submitted(new ReviewReply("t-elsewhere", new Body("Fixed."))));

        assertThatThrownBy(() -> intake.take(TASK, DOCUMENT, false))
                .as("a reply in no thread of the push")
                .isInstanceOfSatisfying(
                        ProfileRefused.class,
                        ex ->
                                assertThat(ex.rule())
                                        .as("the threads' rule")
                                        .isEqualTo("threads: NoSuchThread"));
        assertThat(threads.kept).as("not even the comment before it kept").isEmpty();
        assertThat(holds.undecided(null)).as("nothing held").isEmpty();
        assertThat(events)
                .as("recorded before the threads were asked: never less than went")
                .containsExactly(
                        "unheld uc " + TASK + " comment at " + GATE_PUSH + ", run 1",
                        "unheld uc " + TASK + " reply at " + GATE_PUSH + ", run 1");
    }

    @Test
    void anUnsignedCommentAtAGatePushIsInTheThreadsAtOnceAndNothingIsHeld() throws Exception {
        // Nothing leaves the machine and the host stamps the mailbox it came through: a task
        // without a certified key writes at a waiting push as one with it does.
        runAt(GATE_PUSH, null);
        reading.items = List.of(submitted(COMMENT));

        intake.take(TASK, DOCUMENT, true);

        assertThat(holds.undecided(null)).as("nothing held").isEmpty();
        assertThat(threads.kept)
                .as("the comment in the push's threads, as the task")
                .singleElement()
                .extracting(org.fuin.porelo.gate.review.Kept::origin)
                .isEqualTo(Origin.of(new Task(TASK)));
        assertThat(events)
                .as("the event log's entry, in the signed case's form")
                .containsExactly("unheld uc " + TASK + " comment at " + GATE_PUSH + ", run 1");
    }

    @Test
    void anUnsignedResolveAtAGatePushReadsResolvedAtOnce() throws Exception {
        runAt(GATE_PUSH, null);
        final String thread =
                threads.opened(PINNED, Origin.of(new Task("porelo-uc_shop-3")), COMMENT);
        reading.items = List.of(submitted(new ThreadResolve(thread)));

        intake.take(TASK, DOCUMENT, true);

        assertThat(threads.threads(PINNED))
                .as("the push's thread")
                .singleElement()
                .extracting(org.fuin.porelo.gate.review.ReviewThread::state)
                .isEqualTo(ThreadState.RESOLVED);
        assertThat(holds.undecided(null)).as("nothing held").isEmpty();
    }

    @Test
    void aGatePushTheTaskMayNotWriteInRefusesTheSubmissionAndWritesNothing() {
        // The recorded rights and place decide in the intake of the threads too.
        runAt(GATE_PUSH, null);
        writes.allowed = false;
        reading.items = List.of(submitted(COMMENT));

        assertThatThrownBy(() -> intake.take(TASK, DOCUMENT, false))
                .as("a task the threads refuse")
                .isInstanceOfSatisfying(
                        ProfileRefused.class,
                        ex ->
                                assertThat(ex.rule())
                                        .as("the threads' rule")
                                        .isEqualTo("threads: NoSuchThread"));
        assertThat(threads.kept).as("nothing written").isEmpty();
        assertThat(events)
                .as("recorded before the threads were asked: never less than went")
                .containsExactly("unheld uc " + TASK + " comment at " + GATE_PUSH + ", run 1");
    }

    @Test
    void aGatePushWhoseEventLogCannotBeWrittenRefusesTheSubmissionAndWritesNothing() {
        // The log's entry is the only trace of an item that went without the owner's release.
        runAt(GATE_PUSH, null);
        logWritten = false;
        reading.items = List.of(submitted(COMMENT));

        assertThatThrownBy(() -> intake.take(TASK, DOCUMENT, false))
                .as("a submission whose unheld entry cannot be written")
                .isInstanceOfSatisfying(
                        ProfileRefused.class,
                        ex ->
                                assertThat(ex.rule())
                                        .as("the rule")
                                        .isEqualTo(UnheldWrites.NOT_RECORDED));
        assertThat(threads.kept).as("nothing written").isEmpty();
        assertThat(holds.undecided(null)).as("nothing held").isEmpty();
    }

    @Test
    void anOnlinePullRequestWhoseEventLogCannotBeWrittenSendsNothingAndTheItemWaits() {
        classes.put("uc", SecurityClass.ONLINE);
        runAt(PULL_REQUEST, FACTS);
        logWritten = false;
        reading.items = List.of(submitted(COMMENT));

        assertThatThrownBy(() -> intake.take(TASK, DOCUMENT, false))
                .as("a submission whose unheld entry cannot be written")
                .isInstanceOfSatisfying(
                        ProfileRefused.class,
                        ex ->
                                assertThat(ex.rule())
                                        .as("the rule")
                                        .isEqualTo(UnheldWrites.NOT_RECORDED));
        assertThat(forge().written).as("the forge has nothing").isEmpty();
        assertThat(holds.undecided(null))
                .as("held, never released, for the owner account")
                .singleElement()
                .extracting(Held::item)
                .isEqualTo(COMMENT);
    }

    @Test
    void aPlaceThatIsNeitherAGatePushNorAPullRequestLetsNothingOut() {
        // Fail closed: a run whose place names no push it pins and no pull request goes nowhere.
        runAt("elsewhere/shop-9", null);
        reading.items = List.of(submitted(COMMENT));

        assertThatThrownBy(() -> intake.take(TASK, DOCUMENT, false))
                .isInstanceOfSatisfying(
                        ProfileRefused.class,
                        ex -> assertThat(ex.rule()).as("the rule").isEqualTo(HoldingRule.NO_PLACE));
        assertThat(threads.kept).as("nothing written").isEmpty();
        assertThat(holds.undecided(null)).as("nothing held").isEmpty();
    }

    @Test
    void anOnlineProjectsPullRequestHasTheCommentAtOnceAndNothingWaits() throws Exception {
        // An online project's work reaches the forge unreviewed; its comments go the same way.
        classes.put("uc", SecurityClass.ONLINE);
        runAt(PULL_REQUEST, FACTS);
        reading.items = List.of(submitted(COMMENT));

        intake.take(TASK, DOCUMENT, false);

        assertThat(forge().written)
                .as("the forge has the comment, at the run's pinned commit")
                .singleElement()
                .satisfies(
                        held -> {
                            assertThat(held.item()).as("the item").isEqualTo(COMMENT);
                            assertThat(held.commit()).as("the commit").isEqualTo(PINNED.head());
                        });
        assertThat(rights.lentFor).as("written with the task's entry").containsExactly(TASK);
        assertThat(holds.undecided(null)).as("nothing waits").isEmpty();
        assertThat(onlyHeld())
                .as("the forge's answer kept as for a released item")
                .extracting(Held::state)
                .isEqualTo(HeldState.DELIVERED);
        assertThat(threads.kept).as("nothing in a push's threads").isEmpty();
        assertThat(events)
                .as("the event log names task and place, never the text")
                .containsExactly("unheld uc " + TASK + " comment at " + PULL_REQUEST + ", run 1");
    }

    @Test
    void anOnlineWriteOfUnknownOutcomeIsKeptSoAndNeverSentAgain() throws Exception {
        // A write that may have gone out is never repeated by a later pass.
        classes.put("uc", SecurityClass.ONLINE);
        runAt(PULL_REQUEST, FACTS);
        forge().failing = true;
        reading.items = List.of(submitted(COMMENT));

        intake.take(TASK, DOCUMENT, false);

        assertThat(onlyHeld())
                .as("kept of unknown outcome")
                .extracting(Held::state)
                .isEqualTo(HeldState.OUTCOME_UNKNOWN);
        assertThat(holds.due(java.time.Instant.now().plusSeconds(3600)))
                .as("never due again")
                .isEmpty();
    }

    @Test
    void aGuardedProjectsPullRequestHoldsTheCommentAndTheForgeHasNothing() throws Exception {
        // In a guarded project nothing reaches the forge before the owner account releases it.
        classes.put("uc", SecurityClass.GUARDED);
        runAt(PULL_REQUEST, FACTS);
        reading.items = List.of(submitted(COMMENT));

        intake.take(TASK, DOCUMENT, false);

        assertThat(holds.undecided(null))
                .as("held for the owner account")
                .singleElement()
                .extracting(Held::item)
                .isEqualTo(COMMENT);
        assertThat(forge().written).as("the forge has nothing").isEmpty();
        assertThat(events).as("nothing went unheld").isEmpty();
    }

    @Test
    void anUnsignedSubmissionToAGuardedPullRequestIsHeldMarkedUnsigned() throws Exception {
        // Where items are held the mark is shown to the owner account; nothing is refused.
        classes.put("uc", SecurityClass.GUARDED);
        runAt(PULL_REQUEST, FACTS);
        reading.items = List.of(submitted(COMMENT));

        intake.take(TASK, DOCUMENT, true);

        assertThat(holds.undecided(null))
                .as("held marked unsigned")
                .singleElement()
                .extracting(Held::unsigned)
                .isEqualTo(true);
    }

    @Test
    void anUnsignedSubmissionToAnOnlinePullRequestIsHeldMarkedUnsignedAndNotSent()
            throws Exception {
        // Sent at once, nothing would show the owner account that no certified key signed it.
        classes.put("uc", SecurityClass.ONLINE);
        runAt(PULL_REQUEST, FACTS);
        reading.items = List.of(submitted(COMMENT));

        intake.take(TASK, DOCUMENT, true);

        assertThat(holds.undecided(null))
                .as("held marked unsigned")
                .singleElement()
                .extracting(Held::unsigned)
                .isEqualTo(true);
        assertThat(forge().written).as("the forge has nothing").isEmpty();
        assertThat(events).as("nothing went unheld").isEmpty();
    }

    @Test
    void anOfflineProjectsPullRequestIsRefusedAndNothingGoesOut() {
        // An offline project has no forge; an item for one is never sent, nor held to be.
        classes.put("uc", SecurityClass.OFFLINE);
        runAt(PULL_REQUEST, FACTS);
        reading.items = List.of(submitted(COMMENT));

        assertThatThrownBy(() -> intake.take(TASK, DOCUMENT, false))
                .as("a pull request of an offline project")
                .isInstanceOfSatisfying(
                        ProfileRefused.class,
                        ex -> assertThat(ex.rule()).as("the rule").isEqualTo(HoldingRule.OFFLINE));
        assertThat(forge().written).as("the forge has nothing").isEmpty();
        assertThat(holds.undecided(null)).as("nothing held").isEmpty();
    }

    @Test
    void aProjectFileThatCannotBeReadRefusesThePullRequestsItemsAndNothingGoesOut() {
        // Fail closed: a class that cannot be read lets nothing out unheld.
        runAt(PULL_REQUEST, FACTS);
        reading.items = List.of(submitted(COMMENT));

        assertThatThrownBy(() -> intake.take(TASK, DOCUMENT, false))
                .as("no project file")
                .isInstanceOfSatisfying(
                        ProfileRefused.class,
                        ex ->
                                assertThat(ex.rule())
                                        .as("the rule")
                                        .isEqualTo(HoldingRule.CLASS_UNREADABLE));
        assertThat(forge().written).as("the forge has nothing").isEmpty();
        assertThat(holds.undecided(null)).as("nothing held").isEmpty();
    }

    @Test
    void aGatePushNeverAsksTheProjectsClass() throws Exception {
        // The class decides only towards a pull request; a gate push holds nothing whatever it is.
        classes.put("uc", SecurityClass.OFFLINE);
        runAt(GATE_PUSH, null);
        reading.items = List.of(submitted(COMMENT));

        intake.take(TASK, DOCUMENT, false);

        assertThat(threads.kept).as("written at once").hasSize(1);
    }

    @Test
    void aResolveOfATaskWithoutResolveThreadsIsRefusedAndNothingOfTheSubmissionGoes()
            throws Exception {
        // The right is judged before anything is held, written or sent, wherever the place is.
        classes.put("uc", SecurityClass.ONLINE);
        runAt(PULL_REQUEST, FACTS);
        rights.resolving = false;
        reading.items = List.of(submitted(COMMENT), submitted(new ThreadResolve("1234")));

        assertThatThrownBy(() -> intake.take(TASK, DOCUMENT, false))
                .as("a resolve of a task without the right")
                .isInstanceOfSatisfying(
                        ProfileRefused.class,
                        ex ->
                                assertThat(ex.rule())
                                        .as("the rule")
                                        .isEqualTo(
                                                "RightMissing: resolveThreads (resolve a thread)"));
        assertThat(forge().written).as("not even the comment before it sent").isEmpty();
        assertThat(holds.undecided(null)).as("nothing held").isEmpty();
    }

    @Test
    void aGuardedPullRequestHoldsAResolveAndTheForgeHasItOnlyAfterTheRelease() throws Exception {
        // A resolve waits for the owner account as a comment does, and goes as the task's own.
        classes.put("uc", SecurityClass.GUARDED);
        runAt(PULL_REQUEST, FACTS);
        reading.items = List.of(submitted(new ThreadResolve("1234")));

        intake.take(TASK, DOCUMENT, false);
        sender.send();

        final Held held =
                assertThat(holds.undecided(null))
                        .as("the resolve held for the owner account")
                        .singleElement()
                        .actual();
        assertThat(held.item()).as("the held item").isEqualTo(new ThreadResolve("1234"));
        assertThat(forge().written).as("nothing resolved before the release").isEmpty();

        holds.decide(TASK, held.id(), held.digest(), Decision.RELEASE);
        sender.send();

        assertThat(forge().written)
                .as("one resolve after the release, at the run's pinned commit")
                .singleElement()
                .satisfies(
                        written -> {
                            assertThat(written.item())
                                    .as("the item")
                                    .isEqualTo(new ThreadResolve("1234"));
                            assertThat(written.commit()).as("the commit").isEqualTo(PINNED.head());
                        });
        assertThat(rights.lentFor).as("sent with the task's entry").containsExactly(TASK);
    }

    @Test
    void aGatePushResolvesTheThreadAtOnceAsTheTaskAndHoldsNothing() throws Exception {
        // Nothing leaves the machine: the thread reads resolved at once, set by the task.
        runAt(GATE_PUSH, null);
        final String thread =
                threads.opened(PINNED, Origin.of(new Task("porelo-uc_shop-3")), COMMENT);
        reading.items = List.of(submitted(new ThreadResolve(thread)));

        intake.take(TASK, DOCUMENT, false);

        assertThat(threads.threads(PINNED))
                .as("the push's thread")
                .singleElement()
                .extracting(org.fuin.porelo.gate.review.ReviewThread::state)
                .isEqualTo(ThreadState.RESOLVED);
        assertThat(threads.stateBy.get(thread))
                .as("set by the task, as the host stamps it")
                .isEqualTo(Origin.of(new Task(TASK)));
        assertThat(threads.kept).as("no item kept for it").hasSize(1);
        assertThat(holds.undecided(null)).as("nothing held").isEmpty();
        assertThat(events)
                .as("the event log names task and place")
                .containsExactly("unheld uc " + TASK + " resolve at " + GATE_PUSH + ", run 1");
    }

    @Test
    void aSubmissionThatNamesItsRunsOwnPlaceIsTaken() throws Exception {
        runAt(GATE_PUSH, null);
        reading.items = List.of(submitted(COMMENT));
        reading.named = List.of("push:uc/shop/shop-2");

        intake.take(TASK, DOCUMENT, false);

        assertThat(threads.kept).as("written").hasSize(1);

        classes.put("uc", SecurityClass.GUARDED);
        runAt(PULL_REQUEST, FACTS);
        reading.named = List.of("pullRequest:" + PULL_REQUEST);

        intake.take(TASK, DOCUMENT, false);

        assertThat(holds.undecided(null)).as("held at its pull request").hasSize(1);
    }

    @Test
    void aSubmissionThatNamesAnotherPlaceThanItsRunsIsRefusedAndNothingGoes() throws Exception {
        // The run's place is where its items go: a document naming another one is refused whole.
        runAt(GATE_PUSH, null);
        reading.items = List.of(submitted(COMMENT));
        for (final String elsewhere :
                List.of(
                        "push:uc/shop/shop-3",
                        "push:uc/other/shop-2",
                        "push:vc/shop/shop-2",
                        "pullRequest:" + PULL_REQUEST)) {
            reading.named = List.of(elsewhere);
            assertThatThrownBy(() -> intake.take(TASK, DOCUMENT, false))
                    .as("a push run's submission naming %s", elsewhere)
                    .isInstanceOfSatisfying(
                            ProfileRefused.class,
                            ex -> assertThat(ex.rule()).isEqualTo(RunIntake.NOT_ITS_RUN));
        }
        reading.named = List.of("push:uc/shop/shop-2", "push:uc/shop/shop-3");
        assertThatThrownBy(() -> intake.take(TASK, DOCUMENT, false))
                .as("one run of two naming another push")
                .isInstanceOfSatisfying(
                        ProfileRefused.class,
                        ex -> assertThat(ex.rule()).isEqualTo(RunIntake.NOT_ITS_RUN));
        assertThat(threads.kept).as("nothing written").isEmpty();

        classes.put("uc", SecurityClass.GUARDED);
        runAt(PULL_REQUEST, FACTS);
        for (final String elsewhere :
                List.of("pullRequest:https://github.com/uc/shop/pull/8", "push:uc/shop/shop-2")) {
            reading.named = List.of(elsewhere);
            assertThatThrownBy(() -> intake.take(TASK, DOCUMENT, false))
                    .as("a pull request run's submission naming %s", elsewhere)
                    .isInstanceOfSatisfying(
                            ProfileRefused.class,
                            ex -> assertThat(ex.rule()).isEqualTo(RunIntake.NOT_ITS_RUN));
        }
        assertThat(holds.undecided(null)).as("nothing held").isEmpty();
    }

    @Test
    void aResolveAtAPlaceThatIsNeitherAPushNorAPullRequestFindsNoThread() {
        // Fail closed in the thread's own check, not only in the holding rule after it.
        runAt("elsewhere/shop-9", null);
        reading.items = List.of(submitted(new ThreadResolve("9100000007")));

        assertThatThrownBy(() -> intake.take(TASK, DOCUMENT, false))
                .isInstanceOfSatisfying(
                        ProfileRefused.class,
                        ex -> assertThat(ex.rule()).isEqualTo("check: NoSuchThread"));
        assertThat(holds.undecided(null)).as("nothing held").isEmpty();
    }

    @Test
    void aResolveOfAThreadNotAtTheRunsPlaceIsRefusedAndNothingGoes() throws Exception {
        // A thread of another place is no thread here: not at a gate push, not at a pull request.
        runAt(GATE_PUSH, null);
        reading.items = List.of(submitted(COMMENT), submitted(new ThreadResolve("t-elsewhere")));

        assertThatThrownBy(() -> intake.take(TASK, DOCUMENT, false))
                .as("a thread no push of the gate holds")
                .isInstanceOfSatisfying(
                        ProfileRefused.class,
                        ex ->
                                assertThat(ex.rule())
                                        .as("the rule")
                                        .isEqualTo("check: NoSuchThread"));
        assertThat(threads.kept).as("not even the comment before it kept").isEmpty();

        classes.put("uc", SecurityClass.GUARDED);
        runAt(PULL_REQUEST, FACTS);
        reading.items = List.of(submitted(COMMENT), submitted(new ThreadResolve("t-elsewhere")));

        assertThatThrownBy(() -> intake.take(TASK, DOCUMENT, false))
                .as("a thread id no forge's pull request holds")
                .isInstanceOfSatisfying(
                        ProfileRefused.class,
                        ex ->
                                assertThat(ex.rule())
                                        .as("the rule")
                                        .isEqualTo("check: NoSuchThread"));
        assertThat(holds.undecided(null)).as("nothing held").isEmpty();
    }

    private Held onlyHeld() {
        final List<Held> all = new ArrayList<>();
        for (final Held written : forge().written) {
            holds.find(TASK, written.id()).ifPresent(all::add);
        }
        assertThat(all).as("one item handed to the forge").hasSize(1);
        return all.get(0);
    }

    /** Allows every item or none, and records whom it was asked for, at which push. */
    private static final class Writes implements TaskWrites {
        boolean allowed = true;
        final List<String> asked = new ArrayList<>();

        @Override
        public boolean may(final String container, final Push push, final ReviewItem item) {
            asked.add(container + " " + push.name());
            return allowed;
        }

        @Override
        public boolean reads(final String container, final Push push) {
            return allowed;
        }
    }

    /** Answers fixed items and the places the submission names, read as the channel says. */
    private static final class Reading implements ProfileReading {
        List<ProfileItem> items = List.of();
        List<String> named = List.of();

        @Override
        public ProfileRead read(final byte[] document, final ProfileChannel channel) {
            return new ProfileRead(items, Set.of(), Completeness.COMPLETE, null, named);
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

    /** Opens threads and replies everywhere, and names one entry, recording whom it was for. */
    private static final class Rights implements TaskRights {
        final List<String> lentFor = new ArrayList<>();

        @Override
        public TaskParts parts(final String task) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean mayPush(final String task) {
            return true;
        }

        @Override
        public Producer postingAccount(final String task, final ReviewPlace place) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String postingEntry(final String task, final ReviewPlace place) {
            lentFor.add(task);
            return "git.token.github.com";
        }

        @Override
        public boolean mayOpenThread(final String task, final ReviewPlace place) {
            return true;
        }

        @Override
        public boolean mayReply(final String task, final ReviewPlace place) {
            return true;
        }

        boolean resolving = true;

        @Override
        public boolean mayResolve(final String task, final ReviewPlace place) {
            return resolving;
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

    /** A place that records what it is handed and answers delivered, or throws while failing. */
    private static final class Place implements ReviewPlace {
        final String name;
        final List<Held> written = new ArrayList<>();
        boolean failing;

        Place(final String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

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
            written.add(held);
            if (failing) {
                throw new IllegalStateException("the connection broke after the request");
            }
            return new WriteOutcome.Delivered("t-1");
        }

        @Override
        public CommentReading reading() {
            throw new UnsupportedOperationException();
        }
    }
}
