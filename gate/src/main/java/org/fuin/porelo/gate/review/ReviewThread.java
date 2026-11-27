package org.fuin.porelo.gate.review;

import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A thread: the comment that opened it and every reply, in order, and its state.
 *
 * @param id The thread's id.
 * @param state Open or resolved.
 * @param items Its kept items, the opening comment first.
 * @param stateBy Who set its state last, as the host stamped it: the owner account or a task that
 *     resolved it; null when nobody did.
 */
public record ReviewThread(
        String id, ThreadState state, List<Kept> items, @Nullable Producer stateBy) {

    /**
     * A thread whose state nobody set: open since its comment.
     *
     * @param id The thread's id.
     * @param state Open or resolved.
     * @param items Its kept items, the opening comment first.
     */
    public ReviewThread(final String id, final ThreadState state, final List<Kept> items) {
        this(id, state, items, null);
    }

    /** Refuses a thread without an item. */
    public ReviewThread {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(state, "state");
        items = List.copyOf(items);
        if (items.isEmpty()) {
            throw new IllegalArgumentException("a thread holds at least one item");
        }
        if (!(items.get(0).item() instanceof ReviewComment)) {
            throw new IllegalArgumentException("a thread opens with a comment");
        }
        for (final Kept kept : items) {
            if (!kept.thread().equals(id)) {
                throw new IllegalArgumentException("an item of another thread: " + kept.guid());
            }
        }
    }
}
