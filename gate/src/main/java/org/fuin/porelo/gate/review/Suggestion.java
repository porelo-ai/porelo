package org.fuin.porelo.gate.review;

import java.util.Objects;

/**
 * Text a comment proposes in place of its anchor's lines, on the new side only.
 *
 * @param replacement The proposed lines, as given.
 */
public record Suggestion(String replacement) {

    /** Refuses a missing replacement. */
    public Suggestion {
        Objects.requireNonNull(replacement, "replacement");
    }
}
