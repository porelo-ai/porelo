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
 * @param shortName Optional container spelling ({@code short}).
 * @param nickname The name the agent of its own repository goes by ({@code nickname}).
 * @param secrets What a start does about a credential in the workspace: {@code warn} or {@code
 *     refuse} ({@code secrets}).
 */
public record ProjectInfo(
        @Nullable String name,
        @Nullable String description,
        @Nullable String securityClass,
        @Nullable String upstream,
        @Nullable String review,
        @Nullable String nickname,
        @Nullable String shortName,
        @Nullable String secrets) {

    /**
     * Constructor for a file that says nothing about a credential in the workspace.
     *
     * @param name The project's name.
     * @param description For people.
     * @param securityClass The security class.
     * @param upstream The central address.
     * @param review The review.
     * @param nickname The nickname.
     * @param shortName The short spelling.
     */
    public ProjectInfo(
            @Nullable String name,
            @Nullable String description,
            @Nullable String securityClass,
            @Nullable String upstream,
            @Nullable String review,
            @Nullable String nickname,
            @Nullable String shortName) {
        this(name, description, securityClass, upstream, review, nickname, shortName, null);
    }

    /** Constructor for a file without a short spelling. */
    public ProjectInfo(
            @Nullable String name,
            @Nullable String description,
            @Nullable String securityClass,
            @Nullable String upstream,
            @Nullable String review,
            @Nullable String nickname) {
        this(name, description, securityClass, upstream, review, nickname, null, null);
    }
}
