package org.fuin.porelo.gate.review;

import org.jspecify.annotations.Nullable;

/**
 * Something a producer gives to a review: a comment that opens a thread, a reply in one of Porelo's
 * threads, a reply in a forge's thread, or a task's mark that a thread is resolved. A finding is a
 * later case. A comment and a reply carry text; a resolve carries none.
 */
public sealed interface ReviewItem permits ReviewComment, ReviewReply, ForgeReply, ThreadResolve {

    /**
     * Returns what an item says.
     *
     * @param item The item.
     * @return Its text; null for a resolve, which says nothing.
     */
    static @Nullable Body text(final ReviewItem item) {
        return switch (item) {
            case ReviewComment comment -> comment.body();
            case ReviewReply reply -> reply.body();
            case ForgeReply reply -> reply.body();
            case ThreadResolve _ -> null;
        };
    }
}
