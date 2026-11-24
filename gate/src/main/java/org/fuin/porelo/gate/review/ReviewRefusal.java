package org.fuin.porelo.gate.review;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Why an item, a page or a state change was refused, by the contract's named error.
 *
 * @param error The contract's error.
 * @param detail The rule or limit it names, or null. Never a producer's text.
 */
public record ReviewRefusal(ReviewError error, @Nullable String detail) {

    /** Refuses a missing error. */
    public ReviewRefusal {
        Objects.requireNonNull(error, "error");
    }
}
