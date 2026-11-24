package org.fuin.porelo.gate.review;

import java.util.Objects;

/**
 * What a comment or reply says, as plain text. Its limits and characters are {@link
 * CommentCheck}'s.
 *
 * @param text The text as the producer gave it.
 */
public record Body(String text) {

    /** Refuses a missing text. */
    public Body {
        Objects.requireNonNull(text, "text");
    }
}
