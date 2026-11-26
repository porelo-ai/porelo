package org.fuin.porelo.gate.review;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * What one profile document held, as records.
 *
 * @param items Its results in order, each stamped from the channel.
 * @param resolved The threads every one of whose results on this document says {@code resolved};
 *     empty for a submission, which carries no thread state.
 * @param completeness What the document says of itself: {@code complete} on a forge's last page,
 *     {@code partial} on one with more after it; a submission is complete.
 */
public record ProfileRead(
        List<ProfileItem> items, Set<String> resolved, Completeness completeness) {

    /** Copies the items and the resolved threads. */
    public ProfileRead {
        items = List.copyOf(items);
        resolved = Set.copyOf(resolved);
        Objects.requireNonNull(completeness, "completeness");
    }
}
