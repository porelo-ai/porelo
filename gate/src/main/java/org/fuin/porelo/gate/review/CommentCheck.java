package org.fuin.porelo.gate.review;

import java.util.Optional;

/**
 * Checks an item against its push: its fields and its anchor in the pinned tree. The same for every
 * producer; whether a producer may write into a thread is the intake's rule, not this one's.
 *
 * <p>A stand-in answers a fixed refusal or none, and records what it was asked.
 */
public interface CommentCheck {

    /**
     * Checks one item.
     *
     * @param push The push it is given for.
     * @param item What was given.
     * @return The refusal by rule, or empty when the item may be kept.
     */
    Optional<ReviewRefusal> check(Push push, ReviewItem item);
}
