package org.fuin.porelo.core.project.file;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The {@code mcp} section: what a task of the project may do through its MCP endpoint. Without the
 * section a task gets none. A key left out takes its default.
 *
 * @param methods The Tasks1 methods the endpoint carries, by name without the interface ({@code
 *     methods}), required.
 * @param agents The agents a task started through it may use, each to the models it may be asked
 *     for; {@code null} for a model list means any model ({@code agents}). {@code null} for the
 *     whole when the file leaves it out.
 * @param tasks The most tasks one task may have running through it at once ({@code tasks}).
 * @param minutes The longest run a task started through it may be given ({@code minutes}).
 */
public record Mcp(
        @Nullable List<String> methods,
        @Nullable Map<String, @Nullable List<String>> agents,
        @Nullable Integer tasks,
        @Nullable Integer minutes) {

    /**
     * Constructor.
     *
     * @param methods The methods, or {@code null}.
     * @param agents The agents to their models, or {@code null}.
     * @param tasks The most tasks at once, or {@code null}.
     * @param minutes The longest run, or {@code null}.
     */
    public Mcp {
        methods = methods == null ? null : List.copyOf(methods);
        agents =
                agents == null
                        ? null
                        : java.util.Collections.unmodifiableMap(
                                new java.util.LinkedHashMap<>(agents));
    }
}
