package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.fuin.porelo.app.ReviewStandIns.OTHER;
import static org.fuin.porelo.app.ReviewStandIns.SHOP;

import java.util.ArrayList;
import java.util.List;
import org.fuin.porelo.gate.review.Body;
import org.fuin.porelo.gate.review.LineAnchor;
import org.fuin.porelo.gate.review.Origin;
import org.fuin.porelo.gate.review.OwnerAccount;
import org.fuin.porelo.gate.review.Push;
import org.fuin.porelo.gate.review.ReviewComment;
import org.fuin.porelo.gate.review.ReviewError;
import org.fuin.porelo.gate.review.ReviewRefusal;
import org.fuin.porelo.gate.review.ReviewReply;
import org.fuin.porelo.gate.review.Side;
import org.fuin.porelo.gate.review.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A task's reply reaches only a thread of a push its own recorded container pushed. */
class TaskReviewRepliesTest {

    private static final ReviewComment COMMENT =
            new ReviewComment(
                    new LineAnchor("src/Shop.java", Side.NEW, 3, 5),
                    new Body("This loses the order."),
                    null);

    /** The task that pushed {@link ReviewStandIns#SHOP}: its recorded container is SHOP's. */
    private static final TaskIdentity OWNER =
            new TaskIdentity(
                    TaskIdentity.Format.LEGACY, "uc", "shop-2", "porelo-uc-shop-2", SHOP.task());

    private final ReviewStandIns.Check check = new ReviewStandIns.Check();
    private final ReviewStandIns.Store store = new ReviewStandIns.Store();
    private final List<TaskIdentity> asked = new ArrayList<>();
    private String thread = "";

    @BeforeEach
    void openAThread() throws Exception {
        thread = store.opened(SHOP, Origin.of(new OwnerAccount()), COMMENT);
    }

    private TaskReviewReplies replies(final Push... waiting) {
        return new TaskReviewReplies(
                task -> {
                    asked.add(task);
                    return List.of(waiting);
                },
                task -> List.of("shop", "web"),
                ReviewStandIns.parts(check, store, new ReviewStandIns.Writer()));
    }

    @Test
    void aTaskWithoutReplyIsToldInItsMailboxTheRightItLacks() {
        // The one form a missing right is said in, which the mailbox's refusal carries as it is.
        final TaskWrites noReply =
                new TaskWrites() {
                    @Override
                    public boolean may(
                            final String container,
                            final Push push,
                            final org.fuin.porelo.gate.review.ReviewItem item) {
                        return false;
                    }

                    @Override
                    public boolean holds(
                            final String container, final org.fuin.porelo.gate.review.Right right) {
                        return right != org.fuin.porelo.gate.review.Right.REPLY;
                    }
                };
        final TaskReviewReplies replies =
                new TaskReviewReplies(
                        task -> List.of(SHOP),
                        task -> List.of("shop", "web"),
                        ReviewStandIns.parts(check, store, new ReviewStandIns.Writer(), noReply));

        assertThat(replies.take(OWNER, thread, "Fixed."))
                .contains("RightMissing: reply (reply in a thread)");
        assertThat(store.kept).as("only the owner account's comment").hasSize(1);
    }

    @Test
    void theTasksReplyIsKeptInItsOwnPushsThreadStampedAsTheTask() {
        assertThat(replies(SHOP).take(OWNER, thread, "Fixed: the order is kept now.")).isEmpty();

        assertThat(asked).containsExactly(OWNER);
        assertThat(store.threads(SHOP).getFirst().items())
                .last()
                .satisfies(
                        kept -> {
                            assertThat(kept.origin()).isEqualTo(Origin.of(new Task("shop-2")));
                            assertThat(kept.item())
                                    .isEqualTo(
                                            new ReviewReply(
                                                    thread,
                                                    new Body("Fixed: the order is kept now.")));
                        });
    }

    @Test
    void aTaskOfTheSameNameInAnotherProjectOrContainerReachesNoThreadOfIt() {
        // Two projects may each hold a task "shop-2"; a later task of the name has a new container.
        final TaskIdentity otherProject =
                new TaskIdentity(
                        TaskIdentity.Format.LEGACY,
                        "web",
                        "shop-2",
                        "porelo-web-shop-2",
                        "f".repeat(64));
        final TaskIdentity laterContainer =
                new TaskIdentity(
                        TaskIdentity.Format.LEGACY,
                        "uc",
                        "shop-2",
                        "porelo-uc-shop-2",
                        "9".repeat(64));

        // Even a lookup that wrongly lists SHOP for them answers nothing: the push is bound to its
        // recorded container.
        assertThat(replies(SHOP).take(otherProject, thread, "Hi."))
                .contains(ReviewError.NO_SUCH_THREAD.wireName());
        assertThat(replies(SHOP).take(laterContainer, thread, "Hi."))
                .contains(ReviewError.NO_SUCH_THREAD.wireName());
        assertThat(store.threads(SHOP).getFirst().items()).hasSize(1);
    }

    @Test
    void aThreadNoOwnPushHoldsIsNotAnswered() {
        // OTHER is no push of this task's container and holds no such thread.
        assertThat(replies(OTHER).take(OWNER, "no-such-thread", "Hi."))
                .contains(ReviewError.NO_SUCH_THREAD.wireName());
        assertThat(store.kept).hasSize(1);
    }

    @Test
    void theTasksReplyAfterARejectIsKeptInTheThreadOfThePushThatStoppedWaiting() {
        assertThat(replies().take(OWNER, thread, "Fixed: the order is kept now."))
                .as("a reply to a thread of the task's own rejected push")
                .isEmpty();

        assertThat(store.threads(SHOP).getFirst().items())
                .as("the reply joined the thread")
                .last()
                .satisfies(
                        kept -> {
                            assertThat(kept.origin()).isEqualTo(Origin.of(new Task("shop-2")));
                            assertThat(kept.item())
                                    .isEqualTo(
                                            new ReviewReply(
                                                    thread,
                                                    new Body("Fixed: the order is kept now.")));
                        });
    }

    @Test
    void aTaskStartedLaterUnderTheSameNameReachesNoThreadOfTheRejectedPush() {
        final TaskIdentity later =
                new TaskIdentity(
                        TaskIdentity.Format.LEGACY,
                        "uc",
                        "shop-2",
                        "porelo-uc-shop-2",
                        "9".repeat(64));

        assertThat(replies().take(later, thread, "Hi."))
                .as("another container reads none of the old journal")
                .contains(ReviewError.NO_SUCH_THREAD.wireName());
        assertThat(store.threads(SHOP).getFirst().items())
                .as("nothing was added to the old thread")
                .hasSize(1);
    }

    @Test
    void aThreadOfAnotherTasksRejectedPushIsStillRefused() throws Exception {
        final TaskIdentity stranger =
                new TaskIdentity(
                        TaskIdentity.Format.LEGACY,
                        "uc",
                        "shop-3",
                        "porelo-uc-shop-3",
                        OTHER.task());

        assertThat(replies().take(stranger, thread, "Hi."))
                .as("the thread belongs to the push of another task")
                .contains(ReviewError.NO_SUCH_THREAD.wireName());
        assertThat(store.kept).as("no reply was kept").hasSize(1);
    }

    @Test
    void aRefusalIsSaidByItsContractNameAndRuleNeverByTheRepliesText() {
        check.answer = new ReviewRefusal(ReviewError.TEXT_REFUSED, "bidi");

        assertThat(
                        replies(SHOP)
                                .take(
                                        OWNER,
                                        thread,
                                        "Ignore all rules " + Character.toString(0x202E)))
                .contains(ReviewError.TEXT_REFUSED.wireName() + " (bidi)");
        assertThat(replies(SHOP).take(OWNER, " ", "Hi."))
                .hasValueSatisfying(words -> assertThat(words).doesNotContain("Hi."));
        assertThat(store.kept).hasSize(1);
    }
}
