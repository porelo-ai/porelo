package org.fuin.porelo.daemon;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.fuin.porelo.gate.ReviewRanking;
import org.junit.jupiter.api.Test;

/**
 * The reviews a daemon keeps for their pages: a page of a review just read is answered from it, and
 * a review no longer kept is read again rather than refused.
 */
class KeptReviewsTest {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.EPOCH);

    private final KeptReviews kept = new KeptReviews(now::get);

    private final AtomicInteger reads = new AtomicInteger();

    private static KeptReviews.Key key(final String name) {
        return new KeptReviews.Key("/mirrors/app.git", name, "c".repeat(40), "", false);
    }

    private ReviewRanking.Review read() {
        reads.incrementAndGet();
        return new ReviewRanking.Review(List.of(), "diff --git a/x b/x\n");
    }

    @Test
    void aPageOfAReviewJustReadIsAnsweredWithoutReadingItAgain() {
        final ReviewRanking.Review review = read();
        kept.keep(key("t"), review);

        final ReviewRanking.Review page = kept.review(key("t"), this::read);

        assertThat(page).isSameAs(review);
        assertThat(reads).as("only Review's own read").hasValue(1);
    }

    @Test
    void anotherBaseCommitOrSwitchIsAnotherReview() {
        kept.keep(key("t"), read());

        kept.review(
                new KeptReviews.Key("/mirrors/app.git", "t", "c".repeat(40), "b".repeat(40), false),
                this::read);
        kept.review(
                new KeptReviews.Key("/mirrors/app.git", "t", "c".repeat(40), "", true), this::read);
        kept.review(
                new KeptReviews.Key("/mirrors/app.git", "t", "d".repeat(40), "", false),
                this::read);
        kept.review(
                new KeptReviews.Key("/mirrors/other.git", "t", "c".repeat(40), "", false),
                this::read);

        assertThat(reads).hasValue(5);
    }

    @Test
    void aReviewPastItsTimeIsReadAgainRatherThanRefused() {
        kept.keep(key("t"), read());
        now.set(Instant.EPOCH.plus(KeptReviews.KEPT));

        final ReviewRanking.Review page = kept.review(key("t"), this::read);

        assertThat(page.patch()).isEqualTo("diff --git a/x b/x\n");
        assertThat(reads).hasValue(2);
        now.set(Instant.EPOCH.plus(KeptReviews.KEPT).plus(Duration.ofMinutes(9)));
        kept.review(key("t"), this::read);
        assertThat(reads).as("kept anew from its second read").hasValue(2);
    }

    @Test
    void atMostTheNewestFewAreKept() {
        for (int i = 0; i <= KeptReviews.MOST_REVIEWS; i++) {
            kept.keep(key("t" + i), read());
        }

        assertThat(kept.kept()).isEqualTo(KeptReviews.MOST_REVIEWS);
        kept.review(key("t" + KeptReviews.MOST_REVIEWS), this::read);
        assertThat(reads).as("the newest is kept").hasValue(KeptReviews.MOST_REVIEWS + 1);
        kept.review(key("t0"), this::read);
        assertThat(reads).as("the oldest made room").hasValue(KeptReviews.MOST_REVIEWS + 2);
    }
}
