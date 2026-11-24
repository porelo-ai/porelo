package org.fuin.porelo.gate.review;

import java.util.Objects;

/**
 * A reply in a thread. It carries no anchor: a reply never moves its thread.
 *
 * @param thread The thread's id.
 * @param body What it says.
 */
public record ReviewReply(String thread, Body body) implements ReviewItem {

    /** Refuses a blank thread id. */
    public ReviewReply {
        Objects.requireNonNull(thread, "thread");
        Objects.requireNonNull(body, "body");
        if (thread.isBlank()) {
            throw new IllegalArgumentException("a reply names no thread");
        }
    }
}
