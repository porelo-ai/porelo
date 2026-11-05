package org.fuin.porelo.core.project.file;

import org.jspecify.annotations.Nullable;

/**
 * The {@code limits} section: what one task may consume. A key left out takes its default.
 *
 * @param memory As podman takes it, or {@code none} ({@code memory}).
 * @param pids The most processes ({@code pids}).
 * @param cpus As podman takes it, or {@code none} ({@code cpus}).
 * @param handIn The largest file handed to a running task, such as {@code 64m} ({@code hand_in}).
 * @param subagents The most subagents at the same time ({@code subagents}).
 */
public record Limits(
        @Nullable String memory,
        @Nullable Integer pids,
        @Nullable String cpus,
        @Nullable String handIn,
        @Nullable Integer subagents) {}
