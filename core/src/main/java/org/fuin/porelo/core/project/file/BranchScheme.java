package org.fuin.porelo.core.project.file;

import org.jspecify.annotations.Nullable;

/**
 * A {@code branch} block: how a task's branch at the forge is named, and which names the forge
 * accepts.
 *
 * @param template The name, with placeholders in braces ({@code template}); needs a pattern.
 * @param pattern The names the forge accepts, a regular expression ({@code pattern}).
 */
public record BranchScheme(@Nullable String template, @Nullable String pattern) {}
