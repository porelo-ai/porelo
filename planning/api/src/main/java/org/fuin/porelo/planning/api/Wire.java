package org.fuin.porelo.planning.api;

import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Reads the fields of a varlink answer strictly; record readers handle compatibility with older
 * answers, leaving aside the fields a newer contract added.
 */
final class Wire {

    private static final Pattern UNPRINTABLE = Pattern.compile("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]+");

    private Wire() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Returns a field of the contract's type string.
     *
     * @param map The object.
     * @param name The field.
     * @return Its text, which may be "".
     * @throws IllegalArgumentException When it is absent or of another type.
     */
    static String text(final Map<?, ?> map, final String name) {
        if (map.get(name) instanceof String value) {
            return value;
        }
        throw new IllegalArgumentException("no string " + name);
    }

    /** Returns a nonblank identifier, as opposed to possibly empty display text. */
    static String required(Map<?, ?> map, String name) {
        String value = text(map, name);
        if (value.isBlank()) throw new IllegalArgumentException("blank " + name);
        return value;
    }

    /**
     * Returns a whole number as JSON carries it, every number there being a double.
     *
     * @param value What was carried.
     * @return The number; empty when it is none, or not whole.
     */
    static OptionalLong whole(final @Nullable Object value) {
        if (value instanceof Number number
                && number.doubleValue() == Math.rint(number.doubleValue())
                && !Double.isInfinite(number.doubleValue())) {
            return OptionalLong.of(number.longValue());
        }
        return OptionalLong.empty();
    }

    /**
     * Makes a plugin's words fit for a diagnostic: control and format characters, which steer a
     * terminal or reorder what it shows, become a space, and the length is bounded.
     *
     * @param words What it said.
     * @param most The longest result.
     * @return The words.
     */
    static String plain(final String words, final int most) {
        final String plain = UNPRINTABLE.matcher(words).replaceAll(" ");
        return plain.length() <= most ? plain : plain.substring(0, most - 3) + "...";
    }

    /**
     * Returns an array of objects, each read.
     *
     * @param <T> What each becomes.
     * @param map The object.
     * @param name The field.
     * @param each Reads one.
     * @return The list.
     * @throws IllegalArgumentException When it is absent, or an element is no object.
     */
    static Map<String, Object> object(final Map<?, ?> map, final String name) {
        if (!(map.get(name) instanceof Map<?, ?> object)) {
            throw new IllegalArgumentException("no object " + name);
        }
        final Map<String, Object> answer = new java.util.LinkedHashMap<>();
        for (final var entry : object.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException("invalid object " + name);
            }
            // JSON null is valid for an unknown optional field and for unread settings.
            answer.putAll(java.util.Collections.singletonMap(key, entry.getValue()));
        }
        return answer;
    }

    static List<String> strings(final Map<?, ?> map, final String name) {
        if (!(map.get(name) instanceof List<?> values)) {
            throw new IllegalArgumentException("no array " + name);
        }
        return values.stream()
                .map(
                        value -> {
                            if (value instanceof String text) {
                                return text;
                            }
                            throw new IllegalArgumentException("non-string in " + name);
                        })
                .toList();
    }

    static <T> List<T> list(
            final Map<?, ?> map, final String name, final Function<Map<?, ?>, T> each) {
        if (!(map.get(name) instanceof List<?> listed)) {
            throw new IllegalArgumentException("no " + name);
        }
        return listed.stream()
                .map(
                        element -> {
                            if (element instanceof Map<?, ?> object) {
                                return each.apply(object);
                            }
                            throw new IllegalArgumentException(name + " holds a non-object");
                        })
                .toList();
    }
}
