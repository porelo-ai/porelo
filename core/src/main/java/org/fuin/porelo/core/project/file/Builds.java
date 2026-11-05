package org.fuin.porelo.core.project.file;

import org.jspecify.annotations.Nullable;

/**
 * The {@code builds} section: how an {@code online} project's tasks learn what the build of their
 * own push did.
 *
 * @param forge Which forge, by the name its build reader declares ({@code forge}), required.
 * @param credential The vault entry holding the forge token ({@code credential}), required.
 * @param api The forge API's address ({@code api}).
 * @param logs {@code failure} or {@code all} ({@code logs}).
 */
public record Builds(
        @Nullable String forge,
        @Nullable String credential,
        @Nullable String api,
        @Nullable String logs) {}
