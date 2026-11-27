package org.fuin.porelo.daemon;

import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whether each method of the interface description is meant for agents, as its marker says: the
 * last comment line above it, {@code # x-mcp: true}, or {@code # x-mcp: false} and the reason.
 *
 * <p>A method without a marker is not meant for agents: what is offered to an agent is named, never
 * guessed.
 */
final class McpMarkers {

    /** A marker and the method it stands directly above. */
    private static final Pattern MARKED =
            Pattern.compile(
                    "(?m)^# x-mcp: (?:(true)|false (\\S.{0,199}))\\nmethod\\s+([A-Za-z][A-Za-z0-9]*)\\s*\\(");

    private static final Map<String, Optional<String>> DAEMON = read(PoreloDaemon.description());

    private McpMarkers() {}

    /**
     * Reads the markers of a description.
     *
     * @param description The interface description.
     * @return Each marked method's name, with empty for {@code true} and the reason for {@code
     *     false}.
     */
    static Map<String, Optional<String>> read(final String description) {
        final Map<String, Optional<String>> markers = new TreeMap<>();
        final Matcher matcher = MARKED.matcher(description);
        while (matcher.find()) {
            markers.put(
                    matcher.group(3),
                    matcher.group(1) != null ? Optional.empty() : Optional.of(matcher.group(2)));
        }
        return Map.copyOf(markers);
    }

    /**
     * Tells whether this daemon's description marks a method {@code true}.
     *
     * @param method The method, without the interface.
     * @return True only for a method marked {@code x-mcp: true}.
     */
    static boolean offered(final String method) {
        final Optional<String> marker = DAEMON.get(method);
        return marker != null && marker.isEmpty();
    }

    /**
     * Says why this daemon's description does not mean a method for agents.
     *
     * @param method The method, without the interface.
     * @return The marker's reason; a fixed sentence for a method without one; empty when it is
     *     marked {@code true}.
     */
    static Optional<String> withheld(final String method) {
        final Optional<String> marker = DAEMON.get(method);
        if (marker == null) {
            return Optional.of("it carries no marker saying it is meant for agents");
        }
        return marker;
    }

    /**
     * Returns this daemon's markers.
     *
     * @return Each method's name, with empty for {@code true} and the reason for {@code false}.
     */
    static Map<String, Optional<String>> daemon() {
        return DAEMON;
    }
}
