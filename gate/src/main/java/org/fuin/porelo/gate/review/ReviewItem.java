package org.fuin.porelo.gate.review;

/**
 * Something a producer gives to a review: a comment that opens a thread, or a reply in one. A
 * finding is a later case.
 */
public sealed interface ReviewItem permits ReviewComment, ReviewReply {

    /**
     * Returns what the item says.
     *
     * @return Its text.
     */
    Body body();
}
