package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.fuin.porelo.app.ReviewStandIns.OTHER;
import static org.fuin.porelo.app.ReviewStandIns.SHOP;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.gate.review.Body;
import org.fuin.porelo.gate.review.ForgeAccount;
import org.fuin.porelo.gate.review.ForgeReply;
import org.fuin.porelo.gate.review.Kept;
import org.fuin.porelo.gate.review.LineAnchor;
import org.fuin.porelo.gate.review.Origin;
import org.fuin.porelo.gate.review.OwnerAccount;
import org.fuin.porelo.gate.review.ReviewComment;
import org.fuin.porelo.gate.review.ReviewError;
import org.fuin.porelo.gate.review.ReviewItem;
import org.fuin.porelo.gate.review.ReviewRefusal;
import org.fuin.porelo.gate.review.ReviewRefused;
import org.fuin.porelo.gate.review.ReviewReply;
import org.fuin.porelo.gate.review.Side;
import org.fuin.porelo.gate.review.Task;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The one way in for a comment or reply: checked, judged by its producer's authority, kept. */
class CommentIntakeTest {

    private final ReviewStandIns.Check check = new ReviewStandIns.Check();
    private final ReviewStandIns.Store store = new ReviewStandIns.Store();
    private final CommentIntake intake = new CommentIntake(check, store);

    private static final ReviewComment COMMENT =
            new ReviewComment(
                    new LineAnchor("src/Shop.java", Side.NEW, 3, 5),
                    new Body("This loses the order."),
                    null);

    private static ReviewReply reply(final String thread) {
        return new ReviewReply(thread, new Body("Fixed in the next commit."));
    }

    private static ReviewRefusal refusal(final ReviewRefused ex) {
        return ex.refusal();
    }

    @Test
    void anOwnerAccountsCommentIsCheckedThenKeptStampedWithTheOwnerAccount() throws Exception {
        // An unchecked comment kept would carry a hostile path or text into every reader.
        final Kept kept = intake.take(new OwnerAccount(), SHOP, COMMENT, null);

        assertThat(check.asked).containsExactly(COMMENT);
        assertThat(kept.origin()).isEqualTo(Origin.of(new OwnerAccount()));
        assertThat(store.threads(SHOP))
                .singleElement()
                .satisfies(thread -> assertThat(thread.items()).containsExactly(kept));
    }

    @Test
    void anItemTheCheckRefusesIsKeptNowhere() {
        check.answer = new ReviewRefusal(ReviewError.TEXT_REFUSED, "bidi");

        assertThatThrownBy(() -> intake.take(new OwnerAccount(), SHOP, COMMENT, null))
                .isInstanceOfSatisfying(
                        ReviewRefused.class,
                        ex ->
                                assertThat(refusal(ex))
                                        .isEqualTo(
                                                new ReviewRefusal(
                                                        ReviewError.TEXT_REFUSED, "bidi")));
        assertThat(store.kept).isEmpty();
    }

    @Test
    void aTasksReplyIsStampedWithTheTaskItCameFrom() throws Exception {
        // The producer is the channel's, so a reply can never pass as the owner account's.
        final String thread = store.opened(SHOP, Origin.of(new OwnerAccount()), COMMENT);

        final Kept kept = intake.take(new Task("shop-2"), SHOP, reply(thread), null);

        assertThat(kept.origin()).isEqualTo(Origin.of(new Task("shop-2")));
        assertThat(kept.thread()).isEqualTo(thread);
    }

    @Test
    void aTasksReplyInAThreadOfAnotherPushIsRefusedAndKeptNowhere() throws Exception {
        // One task writing into another task's review would speak where it was never asked.
        final String foreign = store.opened(OTHER, Origin.of(new OwnerAccount()), COMMENT);
        final int before = store.kept.size();

        assertThatThrownBy(() -> intake.take(new Task("shop-2"), SHOP, reply(foreign), null))
                .isInstanceOfSatisfying(
                        ReviewRefused.class,
                        ex ->
                                assertThat(refusal(ex).error())
                                        .isEqualTo(ReviewError.NO_SUCH_THREAD));
        assertThat(store.kept).hasSize(before);
    }

    @Test
    void aForgeAccountsItemNeverReachesAPushsThreads() throws Exception {
        // A forge's words are written at their pull request; at the gate they would speak as no
        // channel Porelo stamps there.
        final String thread = store.opened(SHOP, Origin.of(new OwnerAccount()), COMMENT);
        final int before = store.kept.size();
        final ForgeAccount octocat = new ForgeAccount("github.com", "583231", "octocat");

        for (final ReviewItem item : List.of(COMMENT, reply(thread))) {
            assertThatThrownBy(() -> intake.take(octocat, SHOP, item, null))
                    .as("a forge account's " + item.getClass().getSimpleName())
                    .isInstanceOfSatisfying(
                            ReviewRefused.class,
                            ex ->
                                    assertThat(refusal(ex).error())
                                            .as("refused by rule")
                                            .isEqualTo(ReviewError.NO_SUCH_THREAD));
        }
        assertThatThrownBy(
                        () ->
                                intake.take(
                                        new OwnerAccount(),
                                        SHOP,
                                        new ForgeReply(thread, new Body("Agreed.")),
                                        null))
                .as("a forge reply, even from the owner account")
                .isInstanceOfSatisfying(
                        ReviewRefused.class,
                        ex ->
                                assertThat(refusal(ex).error())
                                        .as("refused by rule")
                                        .isEqualTo(ReviewError.NO_SUCH_THREAD));
        assertThat(store.kept).as("nothing kept").hasSize(before);
    }

    @Test
    void aTaskOpensNoThread() {
        assertThatThrownBy(() -> intake.take(new Task("shop-2"), SHOP, COMMENT, null))
                .isInstanceOfSatisfying(
                        ReviewRefused.class,
                        ex ->
                                assertThat(refusal(ex).error())
                                        .isEqualTo(ReviewError.NO_SUCH_THREAD));
        assertThat(store.kept).isEmpty();
    }

    @Test
    void theOwnerAccountRepliesInAThreadOfThePushButNotInAnotherPushs() throws Exception {
        final String own = store.opened(SHOP, Origin.of(new OwnerAccount()), COMMENT);
        final String foreign = store.opened(OTHER, Origin.of(new OwnerAccount()), COMMENT);

        assertThat(intake.take(new OwnerAccount(), SHOP, reply(own), null).thread()).isEqualTo(own);
        final int before = store.kept.size();
        assertThatThrownBy(() -> intake.take(new OwnerAccount(), SHOP, reply(foreign), null))
                .isInstanceOfSatisfying(
                        ReviewRefused.class,
                        ex ->
                                assertThat(refusal(ex).error())
                                        .isEqualTo(ReviewError.NO_SUCH_THREAD));
        assertThat(store.kept).hasSize(before);
    }

    @Test
    void aRetryWithTheSameIdAnswersTheFirstKeptCommentAndKeepsNoSecond() throws Exception {
        // A client that lost the answer and sends again must not open a second thread.
        final Kept first = intake.take(new OwnerAccount(), SHOP, COMMENT, "retry-1");
        final Kept again = intake.take(new OwnerAccount(), SHOP, COMMENT, "retry-1");

        assertThat(again).isEqualTo(first);
        assertThat(store.kept).containsExactly(first);
    }

    @Test
    void theCheckIsAskedBeforeTheThreadIsJudgedAndTheStoresRefusalPassesUnchanged()
            throws Exception {
        final String foreign = store.opened(OTHER, Origin.of(new OwnerAccount()), COMMENT);
        check.answer = new ReviewRefusal(ReviewError.TOO_LARGE, "text");

        assertThatThrownBy(() -> intake.take(new Task("shop-2"), SHOP, reply(foreign), null))
                .isInstanceOfSatisfying(
                        ReviewRefused.class,
                        ex -> assertThat(refusal(ex).error()).isEqualTo(ReviewError.TOO_LARGE));

        check.answer = null;
        store.refusal = new ReviewRefusal(ReviewError.ID_REUSED, null);
        assertThatThrownBy(() -> intake.take(new OwnerAccount(), SHOP, COMMENT, "retry-2"))
                .isInstanceOfSatisfying(
                        ReviewRefused.class,
                        ex -> assertThat(refusal(ex).error()).isEqualTo(ReviewError.ID_REUSED));
    }

    @Test
    void aTaskRecordThatCannotBeReadIsNoSuchPushAndQuotesNothingOfTheFailure(@TempDir Path dir)
            throws Exception {
        // Unmapped, the failure would reach the caller as an internal error quoting a local path.
        final Path runtime = Files.createDirectory(dir.resolve("run"));
        Files.writeString(runtime.resolve("porelo"), "not a directory");
        final PoreloContext context =
                new PoreloContext(
                        new FakeCommandRunner(),
                        new PoreloPaths(
                                XdgPaths.of(
                                        name ->
                                                "XDG_RUNTIME_DIR".equals(name)
                                                        ? runtime.toString()
                                                        : null,
                                        dir),
                                dir.resolve("bin")),
                        arguments -> 0);

        assertThatThrownBy(() -> TaskHistory.forPush(context, "uc", "shop-2"))
                .as("the records cannot be read")
                .isInstanceOf(RuntimeException.class);

        assertThatThrownBy(() -> CommentIntake.recorded(context, "uc", "shop-2"))
                .isInstanceOfSatisfying(
                        ReviewRefused.class,
                        ex ->
                                assertThat(refusal(ex))
                                        .isEqualTo(
                                                new ReviewRefusal(ReviewError.NO_SUCH_PUSH, null)));
    }
}
