package org.fuin.porelo.gate.review;

import java.util.Objects;

/**
 * A reply in a forge's thread, named by the forge's own thread id. It never reaches a push at the
 * gate, whose threads are Porelo's.
 *
 * @param thread The forge's native thread id, as profile 2 carries it: at most 64 characters.
 * @param body What it says.
 */
public record ForgeReply(String thread, Body body) implements ReviewItem {

    /** The longest thread id profile 2 carries. */
    public static final int THREAD_LIMIT = 64;

    /** Refuses a blank or overlong thread id. */
    public ForgeReply {
        Objects.requireNonNull(thread, "thread");
        Objects.requireNonNull(body, "body");
        if (thread.isBlank()) {
            throw new IllegalArgumentException("a forge reply names no thread");
        }
        if (thread.length() > THREAD_LIMIT) {
            throw new IllegalArgumentException("a forge thread id is over 64 characters");
        }
    }
}
