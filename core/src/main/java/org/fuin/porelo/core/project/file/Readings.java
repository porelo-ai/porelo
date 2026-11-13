package org.fuin.porelo.core.project.file;

import org.jspecify.annotations.Nullable;

/**
 * Where a reading of an issue writes its findings ({@code readings}).
 *
 * @param directory The directory, relative to the repository's root ({@code directory}); {@code
 *     null} for the default.
 */
public record Readings(@Nullable String directory) {}
