package org.fuin.porelo.agent.api;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
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
 * @param usage For a {@link Kind#SUB_AGENT}'s {@link Phase#END}, the tokens the sub-agent spent as
 *     the stream says, or {@code null} where it says not: never zero for an unknown count.
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
        @Nullable Long durationMs,
        @Nullable Usage usage) {

    /**
     * Checks the fields.
     *
     * @throws IllegalArgumentException If the line is negative, which names no line of the batch,
     *     or the duration is: a call takes no negative time.
     */
    public ToolEvent {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(tool, "tool");
        Objects.requireNonNull(kind, "kind");
        if (line < 0) {
            throw new IllegalArgumentException("A tool event's line is from 0 up, not " + line);
        }
        if (durationMs != null && durationMs < 0) {
            throw new IllegalArgumentException(
                    "A tool event's duration is from 0 up, not " + durationMs);
        }
    }

    /**
     * Creates one without a sub-agent's tokens.
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
     * @param durationMs How long the call took in milliseconds, or {@code null}.
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
            final @Nullable String parent,
            final @Nullable Long durationMs) {
        this(
                line,
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
                durationMs,
                null);
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
        this(line, id, phase, tool, kind, command, background, jobId, at, null, null, null, null);
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
        this(line, id, phase, tool, kind, command, background, jobId, at, name, parent, null, null);
    }

    /**
     * The tokens a sub-agent spent, each kind as the usage record names it.
     *
     * @param input Input tokens, or {@code null} where the stream says not.
     * @param cacheRead Tokens read from the cache, or {@code null}.
     * @param cacheWrite Tokens written to the cache, or {@code null}.
     * @param output Output tokens, its reasoning among them, or {@code null}.
     * @param reasoning The part of the output that is reasoning, or {@code null}.
     */
    public record Usage(
            @Nullable Long input,
            @Nullable Long cacheRead,
            @Nullable Long cacheWrite,
            @Nullable Long output,
            @Nullable Long reasoning) {

        private static final List<String> KINDS =
                List.of("input", "cacheRead", "cacheWrite", "output", "reasoning");

        /**
         * Checks the counts.
         *
         * @throws IllegalArgumentException If one is negative.
         */
        public Usage {
            for (final Long count :
                    Arrays.asList(input, cacheRead, cacheWrite, output, reasoning)) {
                if (count != null && count < 0) {
                    throw new IllegalArgumentException(
                            "A sub-agent's token count is from 0 up, not " + count);
                }
            }
        }

        /**
         * Returns it as it crosses the wire.
         *
         * @return Each kind given; an absent one left out.
         */
        public Map<String, Object> asMap() {
            final List<@Nullable Long> counts =
                    Arrays.asList(input, cacheRead, cacheWrite, output, reasoning);
            final Map<String, Object> map = new LinkedHashMap<>();
            for (int i = 0; i < KINDS.size(); i++) {
                final Long count = counts.get(i);
                if (count != null) {
                    map.put(KINDS.get(i), count);
                }
            }
            return map;
        }

        /**
         * Reads one as it crossed the wire.
         *
         * @param map The kinds.
         * @return The usage, or {@code null} when a count is no whole number from 0 up.
         */
        static @Nullable Usage of(final Map<?, ?> map) {
            final Long[] counts = new Long[KINDS.size()];
            for (int i = 0; i < KINDS.size(); i++) {
                final Object value = map.get(KINDS.get(i));
                if (value != null) {
                    final Long count = whole(value);
                    if (count == null || count < 0) {
                        return null;
                    }
                    counts[i] = count;
                }
            }
            return new Usage(counts[0], counts[1], counts[2], counts[3], counts[4]);
        }
    }

    /** A whole number as the wire carries one, or {@code null} for any other value. */
    private static @Nullable Long whole(final Object value) {
        final java.util.OptionalLong whole =
                org.fuin.porelo.wire.varlink.VarlinkNumbers.whole(value);
        return whole.isPresent() ? Long.valueOf(whole.getAsLong()) : null;
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
                durationMs,
                usage);
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
        if (usage != null) {
            map.put("usage", usage.asMap());
        }
        return map;
    }

    /**
     * Reads one as it crossed the wire.
     *
     * @param map The fields.
     * @return The event, or {@code null} when the map holds no line, id or phase this Porelo knows,
     *     or a duration or token count that is no whole number from 0 up.
     */
    public static @Nullable ToolEvent of(final Map<?, ?> map) {
        final java.util.OptionalInt line =
                org.fuin.porelo.wire.varlink.VarlinkNumbers.wholeInt(map.get("line"));
        if (line.isEmpty()
                || !(map.get("id") instanceof String id)
                || !(map.get("phase") instanceof String phase)) {
            return null;
        }
        final int index = line.getAsInt();
        final Phase known = phase(phase);
        if (known == null || index < 0) {
            return null;
        }
        final Object duration = map.get("durationMs");
        final Long durationMs = duration == null ? null : whole(duration);
        if (duration != null && (durationMs == null || durationMs < 0)) {
            return null;
        }
        final Object tokens = map.get("usage");
        final Usage usage = tokens instanceof Map<?, ?> kinds ? Usage.of(kinds) : null;
        if (tokens != null && usage == null) {
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
                durationMs,
                usage);
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
