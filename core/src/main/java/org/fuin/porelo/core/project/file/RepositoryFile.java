package org.fuin.porelo.core.project.file;

import org.jspecify.annotations.Nullable;

/**
 * A repository's {@code porelo.yml}, or the local {@code .porelo.yml}: what the repository needs to
 * be built in, and nothing else.
 *
 * @param image What a task's container is built from ({@code image}), required.
 */
public record RepositoryFile(@Nullable Image image) {}
