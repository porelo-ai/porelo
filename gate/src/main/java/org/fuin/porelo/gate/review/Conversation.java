package org.fuin.porelo.gate.review;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The whole conversation at a place as one run read it: every item in the place's order, each with
 * its producer and thread, the comments withheld from the run, and how completely it was read.
 *
 * @param items The items read, each thread's opening comment before its replies.
 * @param resolved The threads the forge marks resolved; every other thread is open.
 * @param withheld The comments kept from the run, by rule.
 * @param completeness {@link Completeness#COMPLETE} when every page was read.
 * @param reason Why it is not complete, such as {@code RateLimited}, {@code PagePartial} or {@code
 *     PageRefused}; null when complete.
 */
public record Conversation(
        List<Kept> items,
        Set<String> resolved,
        List<Withheld> withheld,
        Completeness completeness,
        @Nullable String reason) {

    /** Refuses a reason on a complete conversation, and none on an incomplete one. */
    public Conversation {
        items = List.copyOf(items);
        resolved = Set.copyOf(resolved);
        withheld = List.copyOf(withheld);
        Objects.requireNonNull(completeness, "completeness");
        if ((completeness == Completeness.COMPLETE) != (reason == null)) {
            throw new IllegalArgumentException(
                    "an incomplete conversation says why, and only an incomplete one");
        }
        if (reason != null && reason.isBlank()) {
            throw new IllegalArgumentException("the reason is blank");
        }
    }
}
