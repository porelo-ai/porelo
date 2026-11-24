package org.fuin.porelo.gate.review;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A comment that opens a thread at an anchor, with an optional suggestion.
 *
 * @param anchor Where it points.
 * @param body What it says.
 * @param suggestion Text proposed in place of the anchor's lines, or null.
 */
public record ReviewComment(Anchor anchor, Body body, @Nullable Suggestion suggestion)
        implements ReviewItem {

    /** Refuses a suggestion on the old side, which no commit could take. */
    public ReviewComment {
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(body, "body");
        if (suggestion != null && !anchor.takesSuggestion()) {
            throw new IllegalArgumentException("a suggestion on the old side cannot be taken");
        }
    }
}
