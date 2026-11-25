package org.fuin.porelo.gate.review;

import java.util.List;
import java.util.Objects;

/**
 * What one profile document held, as records.
 *
 * @param items Its results in order, each stamped from the channel.
 * @param completeness What the document says of itself: {@code complete} on a forge's last page,
 *     {@code partial} on one with more after it; a submission is complete.
 */
public record ProfileRead(List<ProfileItem> items, Completeness completeness) {

    /** Copies the items. */
    public ProfileRead {
        items = List.copyOf(items);
        Objects.requireNonNull(completeness, "completeness");
    }
}
