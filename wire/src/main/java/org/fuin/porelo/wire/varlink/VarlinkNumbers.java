package org.fuin.porelo.wire.varlink;

import java.util.Map;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.jspecify.annotations.Nullable;

/** Numbers as a varlink reader takes them. */
public final class VarlinkNumbers {

    private VarlinkNumbers() {}

    /**
     * Reads a number as an int, held at the int's bounds rather than cut to its low bits.
     *
     * <p>A whole number arrives as a {@link Long}, whose {@code intValue} keeps the low 32 bits:
     * 2^32 would read as 0, and a far newer protocol version as an old one.
     *
     * @param number The number read.
     * @return It, or {@link Integer#MIN_VALUE} or {@link Integer#MAX_VALUE} past them.
     */
    public static int intOf(final Number number) {
        if (number instanceof Long
                || number instanceof Integer
                || number instanceof Short
                || number instanceof Byte) {
            return (int) Math.clamp(number.longValue(), Integer.MIN_VALUE, Integer.MAX_VALUE);
        }
        // A double's own narrowing saturates, and NaN reads as 0.
        return (int) number.doubleValue();
    }

    /**
     * Reads a whole number exactly, as a varlink peer's whole number arrives as a {@link Long} and
     * anything else as a {@link Double}.
     *
     * @param value The value read.
     * @return It, empty for a fraction or no number at all; {@code 1.0} is the whole number 1, as
     *     {@link org.fuin.porelo.wire.Json#parseExact} reads it.
     */
    public static OptionalLong whole(@Nullable Object value) {
        return value instanceof Long
                        || value instanceof Integer
                        || value instanceof Short
                        || value instanceof Byte
                ? OptionalLong.of(((Number) value).longValue())
                : OptionalLong.empty();
    }

    /**
     * Reads a whole number that fits an int exactly.
     *
     * @param value The value read.
     * @return It, empty as {@link #whole} is or when it lies past an int's bounds.
     */
    public static OptionalInt wholeInt(@Nullable Object value) {
        final OptionalLong whole = whole(value);
        return whole.isPresent()
                        && whole.getAsLong() >= Integer.MIN_VALUE
                        && whole.getAsLong() <= Integer.MAX_VALUE
                ? OptionalInt.of((int) whole.getAsLong())
                : OptionalInt.empty();
    }

    /**
     * Reads an optional whole-number parameter of a call, refusing one that is given but is no
     * whole number within an int, never cutting it to one.
     *
     * @param parameters The call's parameters.
     * @param name The parameter's name.
     * @return It, or {@code null} when it is not given.
     * @throws VarlinkException {@code org.varlink.service.InvalidParameter} naming it otherwise.
     */
    public static @Nullable Integer intParameter(Map<String, ?> parameters, String name) {
        final Object value = parameters.get(name);
        if (value == null) {
            return null;
        }
        return wholeInt(value).orElseThrow(() -> invalid(name));
    }

    /**
     * Reads a required whole-number parameter of a call, refusing one that is missing or is no
     * whole number, never cutting it to one.
     *
     * @param parameters The call's parameters.
     * @param name The parameter's name.
     * @return It.
     * @throws VarlinkException {@code org.varlink.service.InvalidParameter} naming it otherwise.
     */
    public static long longParameter(Map<String, ?> parameters, String name) {
        return whole(parameters.get(name)).orElseThrow(() -> invalid(name));
    }

    private static VarlinkException invalid(String name) {
        return new VarlinkException(
                "org.varlink.service.InvalidParameter", Map.of("parameter", name));
    }
}
