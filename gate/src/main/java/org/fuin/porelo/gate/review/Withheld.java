package org.fuin.porelo.gate.review;

import java.util.Objects;

/**
 * A comment of a conversation kept from the run, by the rule that withheld it; its text is not
 * here, so nothing of it reaches the run.
 *
 * @param id The comment's id at its place.
 * @param thread Its thread's id at its place.
 * @param rule The rule that withheld it, never its text.
 */
public record Withheld(String id, String thread, String rule) {

    /** Refuses a blank part. */
    public Withheld {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(thread, "thread");
        Objects.requireNonNull(rule, "rule");
        if (id.isBlank() || thread.isBlank() || rule.isBlank()) {
            throw new IllegalArgumentException("a withheld comment names its id, thread and rule");
        }
    }
}
