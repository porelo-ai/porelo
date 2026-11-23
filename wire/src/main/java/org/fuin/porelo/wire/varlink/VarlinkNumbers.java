package org.fuin.porelo.wire.varlink;

import java.util.Map;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.jspecify.annotations.Nullable;

/** Numbers as a varlink reader takes them. */
public final class VarlinkNumbers {

    private VarlinkNumbers() {}

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
     * Reads a recorded whole number that may be absent, refusing one that is there and is no whole
     * number within an int, never reading it as the default.
     *
     * @param value The value read, {@code null} when the record does not carry it.
     * @param field The field's name, for the refusal.
     * @param absent What an absent value stands for.
     * @return The number, or {@code absent} when {@code value} is {@code null}.
     * @throws IllegalArgumentException Naming the field, when it is present and no whole int.
     */
    public static int wholeIntOr(@Nullable Object value, String field, int absent) {
        if (value == null) {
            return absent;
        }
        return wholeInt(value)
                .orElseThrow(
                        () ->
                                new IllegalArgumentException(
                                        field + " is no whole number within an int: " + value));
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
