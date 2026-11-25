package org.fuin.porelo.app;

import java.util.Objects;
import org.fuin.porelo.gate.review.ForgeCredential;
import org.fuin.porelo.gate.review.ForgeReply;
import org.fuin.porelo.gate.review.ForgeReview;
import org.fuin.porelo.gate.review.Held;
import org.fuin.porelo.gate.review.LineAnchor;
import org.fuin.porelo.gate.review.ProfileWriting;
import org.fuin.porelo.gate.review.PullRequestFacts;
import org.fuin.porelo.gate.review.PullRequestRef;
import org.fuin.porelo.gate.review.ReviewComment;
import org.fuin.porelo.gate.review.ReviewError;
import org.fuin.porelo.gate.review.ReviewReply;
import org.fuin.porelo.gate.review.WriteOutcome;
import org.jspecify.annotations.Nullable;

/**
 * Writes one released item to its pull request: as a one-result profile 2 submission, posted once
 * through the forge adapter, against the commit it was held for.
 *
 * <p>The only caller of {@link ForgeReview#post}. The credential is the one lent for this write: it
 * is used for the one post and never kept, looked up or shown. No forge id is stored, so a crash
 * after the forge took a post and before it is marked delivered may post it twice.
 */
public final class ForgeWrite {

    /** The reason an item held for another commit than the pinned head is not posted. */
    public static final String MOVED_SINCE_REVIEW = ReviewError.MOVED_SINCE_REVIEW.wireName();

    /** The reason a reply in one of Porelo's own threads, which no forge knows, is not posted. */
    public static final String NO_SUCH_THREAD = ReviewError.NO_SUCH_THREAD.wireName();

    /** The reason an item profile 2 cannot carry is not posted. */
    public static final String NOT_IN_PROFILE = "NotInProfile";

    private final ForgeReview forge;

    private final ProfileWriting writing;

    /**
     * Constructor.
     *
     * @param forge The forge adapter's review calls.
     * @param writing The profile 2 writer.
     */
    public ForgeWrite(final ForgeReview forge, final ProfileWriting writing) {
        this.forge = Objects.requireNonNull(forge, "forge");
        this.writing = Objects.requireNonNull(writing, "writing");
    }

    /**
     * Posts one released item once.
     *
     * @param ref The pull request.
     * @param facts The pull request as the item's run pinned it.
     * @param held The released item.
     * @param credential The write credential, lent for this write.
     * @param threadPlace The anchor of the opening comment of the forge thread a reply answers, as
     *     a fresh read of the conversation gives it; null when that thread was not found. A comment
     *     is written at its own anchor.
     * @return What became of it: {@link WriteOutcome.Refused}, unposted, for an item held for
     *     another commit than the pinned head ({@value #MOVED_SINCE_REVIEW}), a reply in one of
     *     Porelo's own threads or a forge reply whose thread's place is not given ({@value
     *     #NO_SUCH_THREAD}) or one profile 2 cannot carry ({@value #NOT_IN_PROFILE}); otherwise the
     *     forge's answer, and {@link WriteOutcome.OutcomeUnknown} when the post failed.
     */
    public WriteOutcome write(
            final PullRequestRef ref,
            final PullRequestFacts facts,
            final Held held,
            final ForgeCredential credential,
            final @Nullable LineAnchor threadPlace) {
        if (!held.commit().equals(facts.head())) {
            return new WriteOutcome.Refused(MOVED_SINCE_REVIEW);
        }
        final LineAnchor place;
        switch (held.item()) {
            case ReviewReply _ -> {
                return new WriteOutcome.Refused(NO_SUCH_THREAD);
            }
            case ForgeReply _ -> {
                if (threadPlace == null) {
                    // A reply is written at its thread's place, which a fresh read did not find.
                    return new WriteOutcome.Refused(NO_SUCH_THREAD);
                }
                place = threadPlace;
            }
            case ReviewComment comment -> {
                switch (comment.anchor()) {
                    case LineAnchor own -> place = own;
                }
            }
        }
        final byte[] submission;
        try {
            submission = writing.submission(facts, held.item(), place);
        } catch (IllegalArgumentException ex) {
            return new WriteOutcome.Refused(NOT_IN_PROFILE);
        }
        try {
            return java.util.Objects.requireNonNull(
                    forge.post(ref, submission, credential), "an adapter's post answers");
        } catch (RuntimeException ex) {
            // It may have gone out, a post that answered nothing too; sending it again could write
            // it twice, and the failure's text is the adapter's, which is neither kept nor shown.
            return new WriteOutcome.OutcomeUnknown();
        }
    }
}
