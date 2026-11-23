package org.fuin.porelo.agent.api;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
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
     * Keeps the values a record match wants as their own type, refusing any that is not one.
     *
     * @param wanted Checked paths and the values they must have, as declared.
     * @param what What the match belongs to, for the refusal.
     * @return The same paths with each value a {@link String}, a {@link Boolean}, a {@link Long}
     *     for a whole number or a {@link Double} for any other.
     * @throws AgentException When a value is not a string, a boolean or a finite number.
     */
    static Map<String, Object> scalars(Map<String, ?> wanted, String what) {
        final Map<String, Object> result = new LinkedHashMap<>();
        wanted.forEach((field, value) -> result.put(field, scalar(field, value, what)));
        return Map.copyOf(result);
    }

    private static Object scalar(String field, @Nullable Object value, String what) {
        if (value instanceof String || value instanceof Boolean) {
            return value;
        }
        final BigDecimal number = value instanceof Number declared ? exact(declared) : null;
        if (number == null || Double.isInfinite(number.doubleValue())) {
            throw new AgentException(
                    what
                            + " '"
                            + field
                            + "' wants a string, true or false, or a number; quote any other text");
        }
        try {
            return number.longValueExact();
        } catch (ArithmeticException notWhole) {
            return number.doubleValue();
        }
    }

    private static @Nullable BigDecimal exact(Number number) {
        try {
            return new BigDecimal(number.toString());
        } catch (NumberFormatException notFinite) {
            return null;
        }
    }

    /**
     * Whether a record holds every wanted value at its path: a string only the same string, a
     * boolean only the same boolean, a number any number of the same value.
     *
     * @param record The record.
     * @param wanted Checked paths and the values they must have, as {@link #scalars} keeps them.
     * @return true when every one matches
     */
    static boolean matches(Map<?, ?> record, Map<String, ?> wanted) {
        for (final Map.Entry<String, ?> each : wanted.entrySet()) {
            if (!same(each.getValue(), value(record, each.getKey()))) {
                return false;
            }
        }
        return true;
    }

    private static boolean same(@Nullable Object wanted, @Nullable Object found) {
        if (wanted instanceof Number number) {
            final BigDecimal want = exact(number);
            final BigDecimal have = found instanceof Number other ? exact(other) : null;
            return want != null && have != null && want.compareTo(have) == 0;
        }
        return wanted != null && wanted.equals(found);
    }
}
