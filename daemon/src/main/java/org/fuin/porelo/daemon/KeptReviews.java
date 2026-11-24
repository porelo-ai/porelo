package org.fuin.porelo.daemon;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.fuin.porelo.gate.ReviewRanking;
import org.jspecify.annotations.Nullable;

/**
 * The ranked reviews this daemon read, kept for the pages of each: a ReviewPage of a review read a
 * moment ago takes its files and patch from here instead of asking git for the whole change again.
 *
 * <p>A review is the same for the same mirror, push, commit, base and line-ending switch, as the
 * objects it is read from never change. Kept {@link #KEPT} after it was read, at most the {@link
 * #MOST_REVIEWS} newest and at most {@link #MOST_CHARACTERS} characters of patch together; a page
 * of one no longer kept reads it again, never fails for it.
 */
final class KeptReviews {

    /** How long a review is kept for its pages. */
    static final Duration KEPT = Duration.ofMinutes(10);

    /** How many reviews are kept at most; a new one takes the oldest one's place. */
    static final int MOST_REVIEWS = 4;

    /** How many characters of patch all kept reviews hold together at most. */
    static final long MOST_CHARACTERS = 128L * 1024 * 1024;

    /**
     * What one review is read from.
     *
     * @param mirror The gate's mirror, which names the project's repository.
     * @param name The pending push.
     * @param commit The commit reviewed.
     * @param base The base commit, empty for the whole history.
     * @param ignoreLineEndings Whether the patch ignores whitespace at line endings.
     */
    record Key(String mirror, String name, String commit, String base, boolean ignoreLineEndings) {}

    private record Kept(ReviewRanking.Review review, Instant at) {}

    private final InstantSource clock;

    private final Map<Key, Kept> reviews = new LinkedHashMap<>();

    /**
     * Creates the kept reviews of one daemon.
     *
     * @param clock When a review is read and when it is asked for.
     */
    KeptReviews(final InstantSource clock) {
        this.clock = clock;
    }

    /**
     * Keeps a review just read, in place of one kept for the same key.
     *
     * @param key What it was read from.
     * @param review The review.
     */
    synchronized void keep(final Key key, final ReviewRanking.Review review) {
        expire();
        reviews.remove(key);
        if (review.patch().length() > MOST_CHARACTERS) {
            // Too large to hold beside others: its pages read it again.
            return;
        }
        while (reviews.size() >= MOST_REVIEWS
                || held() + review.patch().length() > MOST_CHARACTERS) {
            final Iterator<Key> oldest = reviews.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
        reviews.put(key, new Kept(review, clock.instant()));
    }

    /**
     * Returns a kept review, or reads and keeps it.
     *
     * @param key What it is read from.
     * @param read Reads it, when it is not kept.
     * @return The review.
     */
    ReviewRanking.Review review(final Key key, final Supplier<ReviewRanking.Review> read) {
        final ReviewRanking.Review kept = kept(key);
        if (kept != null) {
            return kept;
        }
        // Read outside the lock: git takes long, and other reviews' pages need not wait for it.
        final ReviewRanking.Review review = read.get();
        keep(key, review);
        return review;
    }

    /**
     * Returns how many reviews are kept now.
     *
     * @return The count.
     */
    synchronized int kept() {
        expire();
        return reviews.size();
    }

    /**
     * Releases every review whose keep time has passed, as a later call would; the daemon's {@link
     * ReadingSweep} calls this, so a review no later page asks for is not held on.
     */
    synchronized void release() {
        expire();
    }

    /**
     * Returns how many characters of patch the kept reviews hold now, as they lie: asking releases
     * none.
     *
     * @return The characters.
     */
    synchronized long characters() {
        return held();
    }

    private synchronized ReviewRanking.@Nullable Review kept(final Key key) {
        expire();
        final Kept kept = reviews.get(key);
        return kept == null ? null : kept.review();
    }

    private long held() {
        long held = 0;
        for (final Kept kept : reviews.values()) {
            held += kept.review().patch().length();
        }
        return held;
    }

    private void expire() {
        final Instant now = clock.instant();
        reviews.values().removeIf(kept -> !now.isBefore(kept.at().plus(KEPT)));
    }
}
