package org.fuin.porelo.gate.review;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The state a run is pinned to: the push it reads, and for a pull request the facts the forge
 * answered when it was pinned.
 *
 * @param push The pinned push: its head the commit reviewed, its base the merge base.
 * @param facts The pull request's facts at pinning; null at a place that is no pull request.
 */
public record Pinned(Push push, @Nullable PullRequestFacts facts) {

    /** Refuses a missing push, and facts whose head is not the push's. */
    public Pinned {
        Objects.requireNonNull(push, "push");
        if (facts != null && !facts.head().equals(push.head())) {
            throw new IllegalArgumentException("the facts are of another head than the push");
        }
    }
}
