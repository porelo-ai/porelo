package org.fuin.porelo.core.project.file;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One entry under {@code repositories}: a repository the project's work happens in.
 *
 * <p>It names no image, egress or limits: the image is the repository's own file's, and what a task
 * may reach or consume is the project's alone.
 *
 * @param upstream Where approved work goes ({@code upstream}).
 * @param central Where a host takes the repository from ({@code central}).
 * @param review {@code required} or {@code optional} ({@code review}).
 * @param description For people ({@code description}).
 * @param issues The issue prefixes it owns ({@code issues}), empty when it names none.
 * @param agent The agent it is worked with ({@code agent}).
 * @param nickname The one word its agent goes by ({@code nickname}).
 * @param maturity How far others can build on it ({@code maturity}).
 * @param branch How a task's branch at its forge is named ({@code branch}).
 */
public record RepositoryEntry(
        @Nullable String upstream,
        @Nullable String central,
        @Nullable String review,
        @Nullable String description,
        List<String> issues,
        @Nullable String agent,
        @Nullable String nickname,
        @Nullable String maturity,
        @Nullable BranchScheme branch) {

    /**
     * Constructor.
     *
     * @param upstream Where approved work goes.
     * @param central Where a host takes it from.
     * @param review Whether a merge needs approval.
     * @param description For people.
     * @param issues The issue prefixes it owns.
     * @param agent The agent it is worked with.
     * @param nickname The one word its agent goes by.
     * @param maturity How far others can build on it.
     * @param branch How a task's branch is named.
     */
    public RepositoryEntry {
        issues = issues == null ? List.of() : List.copyOf(issues);
    }
}
