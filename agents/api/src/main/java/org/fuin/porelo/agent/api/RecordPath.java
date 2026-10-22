package org.fuin.porelo.agent.api;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A field of an agent's machine-readable record, named by a dotted path through its mappings, as an
 * agent writes some values below the top level of a record.
 *
 * <p>A name without a dot is a top-level field, as every definition wrote before paths existed.
 */
final class RecordPath {

    /** How many segments a path may have: enough for what agents write, and no deeper. */
    static final int MOST_SEGMENTS = 4;

    private static final java.util.regex.Pattern SEGMENT =
            java.util.regex.Pattern.compile("[A-Za-z0-9_]+");

    private RecordPath() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Refuses a path that is not one to four segments of letters, digits and underscores.
     *
     * @param path The path as declared.
     * @param what What it names, for the refusal.
     * @return The path.
     * @throws AgentException When it is not such a path.
     */
    static String checked(String path, String what) {
        final String[] segments = path.split("\\.", -1);
        if (segments.length > MOST_SEGMENTS) {
            throw new AgentException(
                    what
                            + " '"
                            + path
                            + "' has more than "
                            + MOST_SEGMENTS
                            + " segments joined by dots");
        }
        for (final String segment : segments) {
            if (!SEGMENT.matcher(segment).matches()) {
                throw new AgentException(
                        what
                                + " '"
                                + path
                                + "' is letters, digits and underscores, its segments joined by"
                                + " single dots");
            }
        }
        return path;
    }

    /**
     * Returns the value a path names in a record, walking through its mappings.
     *
     * @param record The record.
     * @param path A checked path.
     * @return The value, or {@code null} when a segment is missing or not a mapping.
     */
    static @Nullable Object value(Map<?, ?> record, String path) {
        Object current = record;
        for (final String segment : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> fields)) {
                return null;
            }
            current = fields.get(segment);
        }
        return current;
    }

    /**
     * Whether a record holds every wanted value at its path.
     *
     * @param record The record.
     * @param wanted Checked paths and the values they must have.
     * @return true when every one matches
     */
    static boolean matches(Map<?, ?> record, Map<String, String> wanted) {
        for (final Map.Entry<String, String> each : wanted.entrySet()) {
            if (!each.getValue().equals(value(record, each.getKey()))) {
                return false;
            }
        }
        return true;
    }
}
