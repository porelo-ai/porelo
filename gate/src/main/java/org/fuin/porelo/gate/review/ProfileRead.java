package org.fuin.porelo.gate.review;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * What one profile document held, as records.
 *
 * @param items Its results in order, each stamped from the channel.
 * @param resolved The threads every one of whose results on this document says {@code resolved};
 *     empty for a submission, which carries no thread state.
 * @param completeness What the document says of itself: {@code complete} on a forge's last page,
 *     {@code partial} on one with more after it; a submission is complete.
 * @param reject The reason of the submission's one reject result, or {@code null} when it has none;
 *     a page never carries one. It names no push: the run's place is the push it discards.
 * @param named The places a task's submission names, one per run: {@code push:} with the push's
 *     project, repository and name joined by {@code /}, or {@code pullRequest:} with the pull
 *     request's address; empty for a page.
 */
public record ProfileRead(
        List<ProfileItem> items,
        Set<String> resolved,
        Completeness completeness,
        @Nullable Body reject,
        List<String> named) {

    /** Copies the items, the resolved threads and the places named. */
    public ProfileRead {
        items = List.copyOf(items);
        resolved = Set.copyOf(resolved);
        Objects.requireNonNull(completeness, "completeness");
        named = List.copyOf(named);
    }

    /**
     * Constructor for a document that names no place.
     *
     * @param items Its results in order.
     * @param resolved The threads it calls resolved.
     * @param completeness What it says of itself.
     * @param reject The reject's reason, or null.
     */
    public ProfileRead(
            final List<ProfileItem> items,
            final Set<String> resolved,
            final Completeness completeness,
            final @Nullable Body reject) {
        this(items, resolved, completeness, reject, List.of());
    }

    /**
     * Constructor for a document without a reject.
     *
     * @param items Its results in order.
     * @param resolved The threads it calls resolved.
     * @param completeness What it says of itself.
     */
    public ProfileRead(
            final List<ProfileItem> items,
            final Set<String> resolved,
            final Completeness completeness) {
        this(items, resolved, completeness, null, List.of());
    }
}
