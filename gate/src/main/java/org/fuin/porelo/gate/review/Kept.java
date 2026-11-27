package org.fuin.porelo.gate.review;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * An item as the host keeps it: its id, its thread, the host's stamps and what the producer gave.
 *
 * @param guid The item's own id.
 * @param thread The thread it belongs to.
 * @param origin The host's stamps.
 * @param item What the producer gave, unchanged.
 * @param at When the host kept it, in whole seconds; null for an item kept before items carried a
 *     time.
 */
public record Kept(
        String guid, String thread, Origin origin, ReviewItem item, @Nullable Instant at) {

    /**
     * Constructor for an item without a time, as a stand-in keeps one.
     *
     * @param guid The item's own id.
     * @param thread The thread it belongs to.
     * @param origin The host's stamps.
     * @param item What the producer gave.
     */
    public Kept(
            final String guid, final String thread, final Origin origin, final ReviewItem item) {
        this(guid, thread, origin, item, null);
    }

    /** Refuses a missing part and cuts the time to whole seconds. */
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
        if (item instanceof ThreadResolve) {
            throw new IllegalArgumentException("a resolve sets its thread's state; it is not kept");
        }
        if (item instanceof ReviewReply reply && !reply.thread().equals(thread)
                || item instanceof ForgeReply forge && !forge.thread().equals(thread)) {
            throw new IllegalArgumentException("a reply kept in another thread than it names");
        }
        if (at != null) {
            at = at.truncatedTo(ChronoUnit.SECONDS);
        }
    }
}
