package org.fuin.porelo.core.project.file;

import org.jspecify.annotations.Nullable;

/**
 * The {@code project} section: who the project is and how far its agents are trusted.
 *
 * @param name The project's name ({@code name}), required.
 * @param description For people ({@code description}).
 * @param securityClass {@code offline}, {@code guarded} or {@code online} ({@code security_class}),
 *     required.
 * @param upstream Where approved work on the project's own repository goes ({@code upstream}).
 * @param review {@code required} or {@code optional} ({@code review}).
 * @param nickname The name the agent of its own repository goes by ({@code nickname}).
 */
public record ProjectInfo(
        @Nullable String name,
        @Nullable String description,
        @Nullable String securityClass,
        @Nullable String upstream,
        @Nullable String review,
        @Nullable String nickname) {}
