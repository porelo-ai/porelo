package org.fuin.porelo.gate.review;

import java.util.Objects;

/**
 * What the host stamps on an item, apart from what its producer gave. Source level and risk class
 * are later fields.
 *
 * @param producer Who gave it, by channel.
 */
public record Origin(Producer producer) {

    /** Refuses a missing producer. */
    public Origin {
        Objects.requireNonNull(producer, "producer");
    }

    /**
     * Returns the stamps of a producer. Build an origin through this, never the constructor, so a
     * later field (source level, risk class) changes no caller.
     *
     * @param producer Who gave it.
     * @return Its origin.
     */
    public static Origin of(final Producer producer) {
        return new Origin(producer);
    }
}
