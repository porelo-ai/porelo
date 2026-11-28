package org.fuin.porelo.app;

import org.fuin.porelo.gate.review.Push;
import org.fuin.porelo.gate.review.ReviewItem;
import org.fuin.porelo.gate.review.Right;

/**
 * Whether a task may write an item into a waiting push's threads: by its recorded rights, and only
 * in the push its recorded place names.
 *
 * <p>A stand-in answers fixed.
 */
@FunctionalInterface
public interface TaskWrites {

    /**
     * Whether the task may write the item there.
     *
     * @param container The task's container name, by which its rights and place are recorded.
     * @param push The waiting push the item is for.
     * @param item The item: a comment opens a thread, a reply answers in one.
     * @return False when it may not, and when its record cannot be read.
     */
    boolean may(String container, Push push, ReviewItem item);

    /**
     * Whether the task's start recorded a right, wherever the task writes.
     *
     * @param container The task's container name, by which its rights are recorded.
     * @param right The right.
     * @return False when it did not; true when its record cannot be read, and from a stand-in that
     *     does not say, since {@link #may} still refuses every item the task may not write.
     */
    default boolean holds(final String container, final Right right) {
        return true;
    }

    /**
     * Whether the push that waits now is the work a reading task reads: the push its recorded place
     * names, pushed by the task its first pin recorded, or by any task while it has none.
     *
     * @param container The reading task's container name.
     * @param push The push as it waits now, bound to the task that pushed it.
     * @return False for another task's push under the same name, for a task that reads no other
     *     task's work, and when its record cannot be read; a stand-in that does not say answers
     *     false.
     */
    default boolean reads(final String container, final Push push) {
        return false;
    }
}
