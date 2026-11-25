package org.fuin.porelo.gate.review;

/**
 * Something a producer gives to a review: a comment that opens a thread, a reply in one of Porelo's
 * threads, or a reply in a forge's thread. A finding is a later case.
 */
public sealed interface ReviewItem permits ReviewComment, ReviewReply, ForgeReply {

    /**
     * Returns what the item says.
     *
     * @return Its text.
     */
    Body body();
}
