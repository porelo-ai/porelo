package org.fuin.porelo.gate.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Guards the records a review comment travels in: what they refuse before any check runs. */
class ReviewRecordsTest {

    @Test
    void aPullRequestsFactsCarryADecisionTimeExactlyWhenMergedOrClosed() {
        final PullRequestRef ref =
                PullRequestRef.of(java.net.URI.create("https://github.com/shop/app/pull/1"));
        final String sha = "a".repeat(40);
        final java.time.Instant decided = java.time.Instant.parse("2026-11-20T10:15:30Z");
        assertThatThrownBy(
                        () ->
                                new PullRequestFacts(
                                        ref,
                                        "https://github.com/shop/app",
                                        sha,
                                        sha,
                                        sha,
                                        PullRequestState.OPEN,
                                        false,
                                        null,
                                        null,
                                        decided))
                .as("an open pull request was not decided")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new PullRequestFacts(
                                        ref,
                                        "https://github.com/shop/app",
                                        sha,
                                        sha,
                                        sha,
                                        PullRequestState.CLOSED,
                                        false,
                                        null,
                                        null,
                                        null))
                .as("a closed one names when it was closed")
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(
                        new PullRequestFacts(
                                        ref,
                                        "https://github.com/shop/app",
                                        sha,
                                        sha,
                                        sha,
                                        PullRequestState.MERGED,
                                        true,
                                        sha,
                                        "main",
                                        decided)
                                .decidedAt())
                .as("a merged one carries when it merged")
                .isEqualTo(decided);
    }

    @Test
    void aLineAnchorRefusesAMissingOrBlankPath() {
        assertThatThrownBy(() -> new LineAnchor(nullString(), Side.NEW, 1, 1))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new LineAnchor(" ", Side.NEW, 1, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("path");
    }

    @Test
    void aSideIsOldOrNewAndNothingElse() {
        assertThat(Side.named("old")).isEqualTo(Side.OLD);
        assertThat(Side.named("new")).isEqualTo(Side.NEW);
        assertThat(Side.NEW.word()).isEqualTo("new");
        assertThatThrownBy(() -> Side.named("both"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("old or new");
        assertThatThrownBy(() -> Side.named("NEW")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void linesAreCountedFromOneAndTheEndIsNeverBeforeTheStart() {
        assertThat(new LineAnchor("a/b.txt", Side.OLD, 3, 3).lines()).isEqualTo(1L);
        assertThatThrownBy(() -> new LineAnchor("a/b.txt", Side.NEW, 0, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("startLine");
        assertThatThrownBy(() -> new LineAnchor("a/b.txt", Side.NEW, 5, 4))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("endLine");
    }

    @Test
    void aSuggestionOnTheOldSideIsRefused() {
        final LineAnchor old = new LineAnchor("a.txt", Side.OLD, 1, 2);
        assertThatThrownBy(() -> new ReviewComment(old, new Body("why"), new Suggestion("instead")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("old side");
        assertThat(new ReviewComment(old, new Body("why"), null).suggestion()).isNull();
        assertThat(
                        new ReviewComment(
                                        new LineAnchor("a.txt", Side.NEW, 1, 2),
                                        new Body("why"),
                                        new Suggestion("instead"))
                                .suggestion())
                .isEqualTo(new Suggestion("instead"));
    }

    @Test
    void aReplyNamesItsThreadAndEveryItemIsOneOfTheKnownKinds() {
        assertThatThrownBy(() -> new ReviewReply(" ", new Body("done")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("thread");
        final List<ReviewItem> items =
                List.of(
                        new ReviewReply("t-1", new Body("done")),
                        new ReviewComment(
                                new LineAnchor("a.txt", Side.NEW, 1, 1), new Body("x"), null));
        assertThat(items).extracting(ReviewRecordsTest::kind).containsExactly("reply", "comment");
    }

    @Test
    void aProducerIsNamedByItsChannelNeverAsAPerson() {
        assertThat(new OwnerAccount().words()).isEqualTo("owner account");
        assertThat(new Task("shop-2").words()).isEqualTo("task shop-2");
        assertThat(new Task("porelo-shop_review-2").words())
                .as("a container name, as the daemon names a task")
                .isEqualTo("task porelo-shop_review-2");
        assertThatThrownBy(() -> new Task("shop\n2"))
                .as("a line break")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Task(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("task");
    }

    @Test
    void aKeptItemKeepsTheHostsStampsApartFromWhatTheProducerGave() {
        final ReviewItem given = new ReviewReply("t-1", new Body("done"));
        final Kept kept = new Kept("g-2", "t-1", Origin.of(new Task("shop")), given);
        assertThat(kept.item()).isSameAs(given);
        assertThat(kept.origin().producer()).isEqualTo(new Task("shop"));
    }

    @Test
    void aThreadOpensWithItsCommentAndHoldsOnlyItsOwnItems() {
        final Kept opening = new Kept("g-1", "t-1", Origin.of(new OwnerAccount()), comment());
        final Kept reply =
                new Kept("g-2", "t-1", Origin.of(new Task("shop")), new ReviewReply("t-1", BODY));
        assertThat(new ReviewThread("t-1", ThreadState.OPEN, List.of(opening, reply)).items())
                .containsExactly(opening, reply);
        assertThatThrownBy(() -> new ReviewThread("t-1", ThreadState.RESOLVED, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("item");
        assertThatThrownBy(() -> new ReviewThread("t-1", ThreadState.OPEN, List.of(reply)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("opens with a comment");
        final Kept elsewhere = new Kept("g-3", "t-9", Origin.of(new OwnerAccount()), comment());
        assertThatThrownBy(
                        () ->
                                new ReviewThread(
                                        "t-1", ThreadState.OPEN, List.of(opening, elsewhere)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("another thread");
        assertThatThrownBy(
                        () ->
                                new Kept(
                                        "g-4",
                                        "t-1",
                                        Origin.of(new Task("shop")),
                                        new ReviewReply("t-2", BODY)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("another thread");
    }

    private static final String HEAD = "a".repeat(40);
    private static final String BASE = "b".repeat(40);
    private static final String CONTAINER = "0123456789ab".repeat(4);

    @Test
    void aPushNamesItsCommitsAndItsTasksRecordedContainerAndNothingThatLeavesADirectory() {
        assertThat(new Push("shop", "app", "fix", HEAD, null, CONTAINER).base()).isNull();
        assertThat(new Push("shop", "app", "fix", HEAD, BASE, CONTAINER).task())
                .isEqualTo(CONTAINER);
        assertThatThrownBy(() -> new Push("shop", "app", "fix", HEAD, BASE, "../other"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("container");
        assertThatThrownBy(() -> new Push("shop", "app", "fix", HEAD, BASE, "porelo-shop-fix"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("container");
        assertThatThrownBy(() -> new Push("shop", "app", "fix", "main", BASE, CONTAINER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("head");
        assertThatThrownBy(() -> new Push("shop", "app", "fix", HEAD, "HEAD~1", CONTAINER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("base");
    }

    @Test
    void aPushAndAKeptItemRefuseBlankParts() {
        assertThatThrownBy(() -> new Push("shop", "app", " ", HEAD, BASE, CONTAINER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
        assertThatThrownBy(() -> new Kept("", "t-1", Origin.of(new OwnerAccount()), comment()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("guid");
    }

    @Test
    void aTaskProducerIsNamedAsTaskNamesAreAndCarriesNoOtherCharacter() {
        assertThatThrownBy(() -> new Task("shop\n2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("task name");
        assertThatThrownBy(() -> new Task("Shop")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Task("shop-")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void everyRefusalIsOneOfTheContractsNamedErrors() {
        assertThat(ReviewError.values())
                .extracting(ReviewError::wireName)
                .containsExactlyInAnyOrder(
                        "MovedSinceReview",
                        "NoSuchPush",
                        "AnchorOutsideBlob",
                        "NotARegularFile",
                        "FixOutsideAnchor",
                        "OldSideFix",
                        "TextRefused",
                        "TooLarge",
                        "NoSuchThread",
                        "CursorRefused",
                        "IdReused");
        assertThat(new ReviewRefusal(ReviewError.TOO_LARGE, "8 KiB").error().wireName())
                .isEqualTo("TooLarge");
        assertThat(new ReviewRefused(new ReviewRefusal(ReviewError.ID_REUSED, null)))
                .hasMessage("IdReused");
    }

    @Test
    void countsAreNeverNegativeAndARunsCompletenessIsOneOfFourWords() {
        assertThat(Completeness.values())
                .extracting(Completeness::word)
                .containsExactly("complete", "partial", "failed", "unknown");
        assertThatThrownBy(() -> new ReviewCounts.Counts(0, -1, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative");
        assertThatThrownBy(() -> new ReviewCounts.Run("owner account", Completeness.COMPLETE, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative");
    }

    @Test
    void theWidestRangeCountsItsLinesWithoutOverflow() {
        assertThat(new LineAnchor("a.txt", Side.NEW, 1, Integer.MAX_VALUE).lines())
                .isEqualTo((long) Integer.MAX_VALUE);
    }

    private static final Body BODY = new Body("done");

    private static ReviewComment comment() {
        return new ReviewComment(new LineAnchor("a.txt", Side.NEW, 1, 1), new Body("why"), null);
    }

    private static String kind(final ReviewItem item) {
        return switch (item) {
            case ReviewComment comment -> "comment";
            case ReviewReply reply -> "reply";
            case ForgeReply reply -> "forge reply";
        };
    }

    @SuppressWarnings("NullAway")
    private static String nullString() {
        return null;
    }
}
