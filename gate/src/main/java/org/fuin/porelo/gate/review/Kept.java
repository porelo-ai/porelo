package org.fuin.porelo.gate.review;

import java.util.Objects;

/**
 * An item as the host keeps it: its id, its thread, the host's stamps and what the producer gave.
 *
 * @param guid The item's own id.
 * @param thread The thread it belongs to.
 * @param origin The host's stamps.
 * @param item What the producer gave, unchanged.
 */
public record Kept(String guid, String thread, Origin origin, ReviewItem item) {

    /** Refuses a missing part. */
    public Kept {
        Objects.requireNonNull(guid, "guid");
        Objects.requireNonNull(thread, "thread");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(item, "item");
        if (guid.isBlank()) {
            throw new IllegalArgumentException("guid is blank");
        }
        if (thread.isBlank()) {
            throw new IllegalArgumentException("thread is blank");
        }
        if (item instanceof ReviewReply reply && !reply.thread().equals(thread)) {
            throw new IllegalArgumentException("a reply kept in another thread than it names");
        }
    }
}
