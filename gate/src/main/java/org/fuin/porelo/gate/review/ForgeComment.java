package org.fuin.porelo.gate.review;

import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A comment or reply as a forge holds it, by the forge's own ids. Porelo keeps none of these ids
 * across runs: each run reads the whole conversation anew.
 *
 * @param id The forge's native id of this comment.
 * @param thread The forge's native id of its thread; a comment that opens a thread names its own.
 * @param author The account that wrote it, stamped from the adapter's answer.
 * @param item What it says: a {@link ReviewComment} that opens a thread or a {@link ForgeReply}.
 * @param at When the forge says it was written, in whole seconds, or null when it said none.
 */
public record ForgeComment(
        String id, String thread, ForgeAccount author, ReviewItem item, @Nullable Instant at) {

    /** Refuses a blank id, a reply of Porelo's own threads, and a reply naming another thread. */
    public ForgeComment {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(thread, "thread");
        Objects.requireNonNull(author, "author");
        Objects.requireNonNull(item, "item");
        if (id.isBlank() || thread.isBlank()) {
            throw new IllegalArgumentException("a forge comment names its id and its thread");
        }
        switch (item) {
            case ReviewComment _ -> {
                // Opens its thread.
            }
            case ForgeReply reply -> {
                if (!reply.thread().equals(thread)) {
                    throw new IllegalArgumentException(
                            "a reply kept in another thread than it names");
                }
            }
            case ReviewReply _ ->
                    throw new IllegalArgumentException("a forge's reply is a forge reply");
            case ThreadResolve _ ->
                    throw new IllegalArgumentException("a forge's page carries no resolve");
        }
    }

    /**
     * Returns this comment as an item of a conversation.
     *
     * @return It, its id as the guid and its author as the producer.
     */
    public Kept kept() {
        return new Kept(id, thread, Origin.of(author), item, at);
    }
}
