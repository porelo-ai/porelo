package org.fuin.porelo.ecosystem.api;

import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Reads the fields of a varlink answer: strictly, since a field of the contract is never optional
 * today, and leaving aside the fields a newer contract added.
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

    /**
     * Returns a text field that must carry something.
     *
     * @param map The object.
     * @param name The field.
     * @return Its text.
     * @throws IllegalArgumentException When it is absent, blank or of another type.
     */
    static String required(final Map<?, ?> map, final String name) {
        final String value = text(map, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException("no " + name);
        }
        return value;
    }

    /**
     * Returns a field of the contract's type bool.
     *
     * @param map The object.
     * @param name The field.
     * @return Its value.
     * @throws IllegalArgumentException When it is absent or of another type, which is never read as
     *     {@code false}.
     */
    static boolean bool(final Map<?, ?> map, final String name) {
        if (map.get(name) instanceof Boolean value) {
            return value.booleanValue();
        }
        throw new IllegalArgumentException("no bool " + name);
    }

    /**
     * Returns a field of the contract's type int.
     *
     * @param map The object.
     * @param name The field.
     * @return Its value.
     * @throws IllegalArgumentException When it is absent, not whole or beyond an int.
     */
    static int integer(final Map<?, ?> map, final String name) {
        final OptionalLong whole = whole(map.get(name));
        if (whole.isEmpty()
                || whole.getAsLong() < Integer.MIN_VALUE
                || whole.getAsLong() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("no int " + name);
        }
        return (int) whole.getAsLong();
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
