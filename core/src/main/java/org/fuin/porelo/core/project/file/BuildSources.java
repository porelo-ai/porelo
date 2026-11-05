package org.fuin.porelo.core.project.file;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The {@code build} section: what the image build itself fetches from.
 *
 * @param packageSources Where apt fetches from while the image is built ({@code package_sources}),
 *     or {@code null} for the default; an empty list is refused by the check.
 */
public record BuildSources(@Nullable List<String> packageSources) {

    /**
     * Constructor.
     *
     * @param packageSources Where apt fetches from, or {@code null}.
     */
    public BuildSources {
        packageSources = packageSources == null ? null : List.copyOf(packageSources);
    }
}
