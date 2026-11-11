package org.fuin.porelo.agent.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One moment of one tool call, as an agent reads it out of a line of its output.
 *
 * <p>The mapping is per line and holds no state: Porelo joins a {@link Phase#START} to its {@link
 * Phase#END} by {@link #id()}, and a background job's start and end by {@link #jobId()}.
 *
 * @param line The index of the line it came from in the batch asked; an agent's own {@link
 *     Agent#toolEvents(String)} may give any value, the server sets it.
 * @param id The call's id, the same at its start and its end.
 * @param phase Whether the call starts or ends here.
 * @param tool The agent's own name for the tool.
 * @param kind What kind of tool it is; one the agent cannot tell is {@link Kind#OTHER}.
 * @param command A shell call's whole command line as the stream gives it, unmasked: Porelo masks
 *     it before anything is stored.
 * @param background Whether the call runs in the background, its end then the end of its job.
 * @param jobId The background job's id, joining its start and its end.
 * @param at The stream's own time of this moment, ISO-8601, or {@code null} for Porelo to stamp the
 *     line's arrival.
 * @param name For a {@link Kind#SUB_AGENT} start, the name the sub-agent was given, such as the id
 *     of the issue it works on; else {@code null}.
 * @param parent The id of the sub-agent call this call ran under, or {@code null} for one outside
 *     any sub-agent.
 * @param durationMs For an {@link Phase#END}, how long the call took in milliseconds as the stream
 *     says, or {@code null} where it says not.
 */
public record ToolEvent(
        int line,
        String id,
        Phase phase,
        String tool,
        Kind kind,
        @Nullable String command,
        boolean background,
        @Nullable String jobId,
        @Nullable String at,
        @Nullable String name,
        @Nullable String parent,
        @Nullable Long durationMs) {

    /**
     * Checks the fields.
     *
     * @throws IllegalArgumentException If the line is negative: it names no line of the batch.
     */
    public ToolEvent {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(tool, "tool");
        Objects.requireNonNull(kind, "kind");
        if (line < 0) {
            throw new IllegalArgumentException("A tool event's line is from 0 up, not " + line);
        }
    }

    /**
     * Creates one outside any sub-agent and naming none.
     *
     * @param line The index of its line in the batch.
     * @param id The call's id.
     * @param phase Whether the call starts or ends here.
     * @param tool The agent's own name for the tool.
     * @param kind What kind of tool it is.
     * @param command A shell call's whole command line, or {@code null}.
     * @param background Whether the call runs in the background.
     * @param jobId The background job's id, or {@code null}.
     * @param at The stream's own time, or {@code null}.
     */
    public ToolEvent(
            final int line,
            final String id,
            final Phase phase,
            final String tool,
            final Kind kind,
            final @Nullable String command,
            final boolean background,
            final @Nullable String jobId,
            final @Nullable String at) {
        this(line, id, phase, tool, kind, command, background, jobId, at, null, null, null);
    }

    /**
     * Creates one without a duration.
     *
     * @param line The index of its line in the batch.
     * @param id The call's id.
     * @param phase Whether the call starts or ends here.
     * @param tool The agent's own name for the tool.
     * @param kind What kind of tool it is.
     * @param command A shell call's whole command line, or {@code null}.
     * @param background Whether the call runs in the background.
     * @param jobId The background job's id, or {@code null}.
     * @param at The stream's own time, or {@code null}.
     * @param name A sub-agent's name, or {@code null}.
     * @param parent The sub-agent call it ran under, or {@code null}.
     */
    public ToolEvent(
            final int line,
            final String id,
            final Phase phase,
            final String tool,
            final Kind kind,
            final @Nullable String command,
            final boolean background,
            final @Nullable String jobId,
            final @Nullable String at,
            final @Nullable String name,
            final @Nullable String parent) {
        this(line, id, phase, tool, kind, command, background, jobId, at, name, parent, null);
    }

    /** Whether a call starts or ends. */
    public enum Phase {
        /** The call starts, or its background job does. */
        START,
        /** The call ends, or, for one in the background, its job does. */
        END
    }

    /** The closed set of tool kinds, each with its spelling on the wire. */
    public enum Kind {
        /** Runs a command line. */
        SHELL("shell"),
        /** Writes or changes a file. */
        EDIT("edit"),
        /** Reads a file. */
        READ("read"),
        /** Searches files or their contents. */
        SEARCH("search"),
        /** Fetches from or searches the web. */
        WEB("web"),
        /** Hands work to a sub-agent. */
        SUB_AGENT("sub-agent"),
        /** Calls a tool of an MCP server. */
        MCP("mcp"),
        /** Any other, or one the agent cannot tell. */
        OTHER("other");

        private final String wire;

        Kind(final String wire) {
            this.wire = wire;
        }

        /**
         * Returns its spelling on the wire.
         *
         * @return The spelling.
         */
        public String wire() {
            return wire;
        }

        /**
         * Reads a kind as it crossed the wire.
         *
         * @param wire The spelling.
         * @return The kind; {@link #OTHER} for one this Porelo does not know, never left out.
         */
        public static Kind of(final @Nullable String wire) {
            for (final Kind kind : values()) {
                if (kind.wire.equals(wire)) {
                    return kind;
                }
            }
            return OTHER;
        }
    }

    /**
     * Returns the same event from another line of the batch.
     *
     * @param index The line's index in the batch.
     * @return The event.
     */
    public ToolEvent atLine(final int index) {
        return new ToolEvent(
                index,
                id,
                phase,
                tool,
                kind,
                command,
                background,
                jobId,
                at,
                name,
                parent,
                durationMs);
    }

    /**
     * Returns it as it crosses the wire.
     *
     * @return The fields; an absent one left out.
     */
    public Map<String, Object> asMap() {
        final Map<String, Object> map = new LinkedHashMap<>();
        map.put("line", Integer.valueOf(line));
        map.put("id", id);
        map.put("phase", phase.name());
        map.put("tool", tool);
        map.put("kind", kind.wire());
        if (command != null) {
            map.put("command", command);
        }
        map.put("background", Boolean.valueOf(background));
        if (jobId != null) {
            map.put("jobId", jobId);
        }
        if (at != null) {
            map.put("at", at);
        }
        if (name != null) {
            map.put("name", name);
        }
        if (parent != null) {
            map.put("parent", parent);
        }
        if (durationMs != null) {
            map.put("durationMs", durationMs);
        }
        return map;
    }

    /**
     * Reads one as it crossed the wire.
     *
     * @param map The fields.
     * @return The event, or {@code null} when the map holds no line, id or phase this Porelo knows.
     */
    public static @Nullable ToolEvent of(final Map<?, ?> map) {
        if (!(map.get("line") instanceof Number line)
                || !(map.get("id") instanceof String id)
                || !(map.get("phase") instanceof String phase)) {
            return null;
        }
        final int index = org.fuin.porelo.wire.varlink.VarlinkNumbers.intOf(line);
        final Phase known = phase(phase);
        if (known == null || index < 0) {
            return null;
        }
        return new ToolEvent(
                index,
                id,
                known,
                map.get("tool") instanceof String tool ? tool : "",
                Kind.of(map.get("kind") instanceof String kind ? kind : null),
                map.get("command") instanceof String command ? command : null,
                Boolean.TRUE.equals(map.get("background")),
                map.get("jobId") instanceof String jobId ? jobId : null,
                map.get("at") instanceof String at ? at : null,
                map.get("name") instanceof String name ? name : null,
                map.get("parent") instanceof String parent ? parent : null,
                map.get("durationMs") instanceof Number duration
                        ? Long.valueOf(duration.longValue())
                        : null);
    }

    private static @Nullable Phase phase(final String name) {
        for (final Phase phase : Phase.values()) {
            if (phase.name().equals(name)) {
                return phase;
            }
        }
        return null;
    }
}
