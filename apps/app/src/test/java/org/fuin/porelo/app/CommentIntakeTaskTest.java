package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.fuin.porelo.app.ReviewStandIns.OTHER;
import static org.fuin.porelo.app.ReviewStandIns.SHOP;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.fuin.porelo.gate.review.Body;
import org.fuin.porelo.gate.review.ForgeReply;
import org.fuin.porelo.gate.review.Kept;
import org.fuin.porelo.gate.review.LineAnchor;
import org.fuin.porelo.gate.review.Origin;
import org.fuin.porelo.gate.review.OwnerAccount;
import org.fuin.porelo.gate.review.Push;
import org.fuin.porelo.gate.review.ReviewComment;
import org.fuin.porelo.gate.review.ReviewError;
import org.fuin.porelo.gate.review.ReviewItem;
import org.fuin.porelo.gate.review.ReviewRefused;
import org.fuin.porelo.gate.review.ReviewReply;
import org.fuin.porelo.gate.review.Right;
import org.fuin.porelo.gate.review.Side;
import org.fuin.porelo.gate.review.Task;
import org.junit.jupiter.api.Test;

/** A task writes into a push's threads only as its recorded rights and place allow. */
class CommentIntakeTaskTest {

    private static final String CONTAINER = "porelo-uc_shop-2";

    private static final ReviewComment COMMENT =
            new ReviewComment(
                    new LineAnchor("src/Shop.java", Side.NEW, 3, 5),
                    new Body("This loses the order."),
                    null);

    private final ReviewStandIns.Check check = new ReviewStandIns.Check();
    private final ReviewStandIns.Store store = new ReviewStandIns.Store();
    private final Writes writes = new Writes();
    private final CommentIntake intake = new CommentIntake(check, store, writes);

    private static ReviewError error(final ReviewRefused ex) {
        return ex.refusal().error();
    }

    @Test
    void aTaskAllowedThereOpensAThreadStampedAsItsChannelSaysAndAskedByItsContainer()
            throws Exception {
        // The thread shows the task as its channel names it; its rights are recorded by container.
        final List<Kept> kept =
                intake.takeAsTask(CONTAINER, new Task("shop-2"), SHOP, List.of(COMMENT));

        assertThat(kept)
                .as("kept as the task")
                .singleElement()
                .extracting(Kept::origin)
                .isEqualTo(Origin.of(new Task("shop-2")));
        assertThat(writes.asked)
                .as("asked by the container, for the push")
                .containsExactly(CONTAINER + " " + SHOP.name() + " comment");
    }

    @Test
    void aTaskItsRecordDoesNotAllowWritesNothing() {
        // Fail closed: no right, another place or a record that does not read keeps nothing.
        writes.allowed = false;
        assertThatThrownBy(
                        () ->
                                intake.takeAsTask(
                                        CONTAINER, new Task(CONTAINER), SHOP, List.of(COMMENT)))
                .isInstanceOfSatisfying(
                        ReviewRefused.class,
                        ex ->
                                assertThat(error(ex))
                                        .as("the error")
                                        .isEqualTo(ReviewError.NO_SUCH_THREAD));
        assertThat(store.kept).as("nothing kept").isEmpty();
    }

    @Test
    void aMailboxReplyAsksTheReplyRightToo() throws Exception {
        // A plain reply goes the same way as a run's items, and is judged the same.
        final String thread = store.opened(SHOP, Origin.of(new OwnerAccount()), COMMENT);
        writes.allowed = false;

        assertThatThrownBy(
                        () ->
                                intake.takeAsTask(
                                        CONTAINER,
                                        new Task("shop-2"),
                                        SHOP,
                                        List.of(new ReviewReply(thread, new Body("Fixed.")))))
                .isInstanceOf(ReviewRefused.class);
        assertThat(writes.asked)
                .as("asked for the reply")
                .containsExactly(CONTAINER + " " + SHOP.name() + " reply");
        assertThat(store.kept).as("only the comment").hasSize(1);
    }

    @Test
    void everyItemIsJudgedBeforeAnyIsKept() throws Exception {
        // A refusal at the second item keeps not even the first, so nothing is written twice.
        final String elsewhere = store.opened(OTHER, Origin.of(new OwnerAccount()), COMMENT);

        assertThatThrownBy(
                        () ->
                                intake.takeAsTask(
                                        CONTAINER,
                                        new Task(CONTAINER),
                                        SHOP,
                                        List.of(
                                                COMMENT,
                                                new ReviewReply(elsewhere, new Body("Fixed.")))))
                .isInstanceOfSatisfying(
                        ReviewRefused.class,
                        ex ->
                                assertThat(error(ex))
                                        .as("the error")
                                        .isEqualTo(ReviewError.NO_SUCH_THREAD));
        assertThat(store.threads(SHOP)).as("not even the comment kept").isEmpty();
    }

    @Test
    void aTaskNeverWritesWithoutItsContainerNamed() {
        // The owner account's way names no container; a task given it writes nothing.
        assertThatThrownBy(() -> intake.take(new Task(CONTAINER), SHOP, COMMENT, null))
                .isInstanceOf(ReviewRefused.class);
        assertThat(writes.asked).as("nothing asked").isEmpty();
        assertThat(store.kept).as("nothing kept").isEmpty();
    }

    @Test
    void aReplyByTheProfilesThreadIdAnswersInThatThreadOfThePush() throws Exception {
        // The profile carries porelo/replyTo as a forge reply; a push's threads are Porelo's own.
        final String thread = store.opened(SHOP, Origin.of(new OwnerAccount()), COMMENT);

        final List<Kept> kept =
                intake.takeAsTask(
                        CONTAINER,
                        new Task(CONTAINER),
                        SHOP,
                        List.of(new ForgeReply(thread, new Body("Fixed."))));

        assertThat(kept)
                .as("kept as a reply in that thread")
                .singleElement()
                .extracting(Kept::item)
                .isEqualTo(new ReviewReply(thread, new Body("Fixed.")));
        assertThat(writes.asked)
                .as("judged as a reply")
                .containsExactly(CONTAINER + " " + SHOP.name() + " reply");
    }

    @Test
    void aReplyInNoThreadOfThePushIsKeptNowhereWhateverTheRights() {
        assertThatThrownBy(
                        () ->
                                intake.takeAsTask(
                                        CONTAINER,
                                        new Task(CONTAINER),
                                        SHOP,
                                        List.of(new ForgeReply("1001", new Body("Fixed.")))))
                .isInstanceOf(ReviewRefused.class);
        assertThat(store.kept).as("nothing kept").isEmpty();
    }

    @Test
    void aTaskWithoutOpenThreadsThatOpensAThreadIsToldTheRightItLacks() {
        // Said as the right, so the task knows a comment is not its to give; nothing is kept.
        writes.held.remove(Right.OPEN_THREADS);

        assertThatThrownBy(
                        () ->
                                intake.takeAsTask(
                                        CONTAINER, new Task("shop-2"), SHOP, List.of(COMMENT)))
                .isInstanceOfSatisfying(
                        TaskRightMissing.class,
                        ex -> {
                            assertThat(ex.rule())
                                    .isEqualTo("RightMissing: openThreads (open a thread)");
                            assertThat(error(ex))
                                    .as("still the contract's error on the wire")
                                    .isEqualTo(ReviewError.NO_SUCH_THREAD);
                        });
        assertThat(store.kept).as("nothing kept").isEmpty();
    }

    @Test
    void aTaskWithoutReplyThatAnswersIsToldTheRightItLacksWhoeverOpenedTheThread()
            throws Exception {
        final String thread = store.opened(SHOP, Origin.of(new OwnerAccount()), COMMENT);
        writes.held.remove(Right.REPLY);

        assertThatThrownBy(
                        () ->
                                intake.takeAsTask(
                                        CONTAINER,
                                        new Task("shop-2"),
                                        SHOP,
                                        List.of(new ForgeReply(thread, new Body("Fixed.")))))
                .isInstanceOfSatisfying(
                        TaskRightMissing.class,
                        ex ->
                                assertThat(ex.rule())
                                        .isEqualTo("RightMissing: reply (reply in a thread)"));
        assertThat(store.kept).as("only the owner account's comment").hasSize(1);
    }

    @Test
    void aTaskHoldingTheRightsWritesNothingAtAPushThatIsNotItsPlace() {
        // The rights are held; the push is not its place, which is not said beyond NoSuchThread.
        writes.allowed = false;

        assertThatThrownBy(
                        () ->
                                intake.takeAsTask(
                                        CONTAINER, new Task("shop-2"), OTHER, List.of(COMMENT)))
                .isInstanceOfSatisfying(
                        ReviewRefused.class,
                        ex -> {
                            assertThat(ex).isNotInstanceOf(TaskRightMissing.class);
                            assertThat(error(ex)).isEqualTo(ReviewError.NO_SUCH_THREAD);
                        });
        assertThat(store.kept).as("nothing kept").isEmpty();
    }

    /** Allows every item or none, holds the rights it is given, and records what it was asked. */
    private static final class Writes implements TaskWrites {
        boolean allowed = true;
        final Set<Right> held = EnumSet.allOf(Right.class);
        final List<String> asked = new ArrayList<>();

        @Override
        public boolean holds(final String container, final Right right) {
            return held.contains(right);
        }

        @Override
        public boolean may(final String container, final Push push, final ReviewItem item) {
            asked.add(
                    container
                            + " "
                            + push.name()
                            + (item instanceof ReviewComment ? " comment" : " reply"));
            return allowed;
        }
    }
}
