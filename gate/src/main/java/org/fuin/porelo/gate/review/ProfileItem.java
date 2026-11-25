package org.fuin.porelo.gate.review;

import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One result of a profile document as a record, its producer stamped from the channel.
 *
 * @param id The result's id at its place; null in a submission, which carries none.
 * @param thread Its thread's id at its place; null for a submitted comment, which opens one.
 * @param producer Who gave it, from the channel, never from the document.
 * @param item The comment or reply.
 * @param at When the place says it was written; null when it said none.
 */
public record ProfileItem(
        @Nullable String id,
        @Nullable String thread,
        Producer producer,
        ReviewItem item,
        @Nullable Instant at) {

    /** Refuses a missing producer or item. */
    public ProfileItem {
        Objects.requireNonNull(producer, "producer");
        Objects.requireNonNull(item, "item");
    }
}
