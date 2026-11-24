package org.fuin.porelo.gate.review;

import java.util.List;
import java.util.Objects;

/**
 * A thread: the comment that opened it and every reply, in order, and its state.
 *
 * @param id The thread's id.
 * @param state Open or resolved.
 * @param items Its kept items, the opening comment first.
 */
public record ReviewThread(String id, ThreadState state, List<Kept> items) {

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
