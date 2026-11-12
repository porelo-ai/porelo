package org.fuin.porelo.core.project.file;

import org.jspecify.annotations.Nullable;

/**
 * One entry of {@code tool_repositories}, as it is written.
 *
 * @param ecosystem The adapter that writes the tool's settings ({@code ecosystem}).
 * @param name The repository's name ({@code name}).
 * @param url Its URL ({@code url}).
 */
public record ToolRepositoryEntry(
        @Nullable String ecosystem, @Nullable String name, @Nullable String url) {}
