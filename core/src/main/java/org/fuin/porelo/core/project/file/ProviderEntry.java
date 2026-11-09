package org.fuin.porelo.core.project.file;

import org.jspecify.annotations.Nullable;

/**
 * One entry under {@code providers}: the model a task on that provider runs on unless its start
 * names another.
 *
 * @param model The model's id, as the provider names it ({@code model}).
 * @param label What a person is shown for it ({@code label}).
 */
public record ProviderEntry(@Nullable String model, @Nullable String label) {}
