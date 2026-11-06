package org.fuin.porelo.ecosystem.api;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;

/**
 * The settings a project keeps for one adapter alone, which Porelo passes on and never reads.
 *
 * <p>A tool's peculiarity - one repository standing in for every other - lives here rather than in
 * a field of the contract, and the adapter says what it means. Nothing in it widens what a task
 * reaches: that is decided from the repositories' names and URLs alone.
 *
 * <p>A number reaches the adapter as a decimal, {@code 3} as {@code 3.0}, and one beyond a double's
 * precision rounded: a setting that needs exact digits is kept as text.
 *
 * @param values Plain JSON values: text, a truth value, a finite number, a list or a map with
 *     non-blank keys, never null; copied, so the caller's later changes reach no adapter.
 */
public record Extension(Map<String, Object> values) {

    /** The most a block may hold, as compact JSON, so a project file cannot fill a socket. */
    public static final int MOST_BYTES = 16 * 1024;

    /** The deepest a block may nest, counting the block itself as the first level. */
    public static final int MOST_DEPTH = 8;

    /** No settings: what an adapter is given when the project keeps none for it. */
    public static final Extension NONE = new Extension(Map.of());

    /**
     * Constructor.
     *
     * @throws IllegalArgumentException When the block is larger or deeper than the contract
     *     carries, or holds what JSON cannot; the message names the limit, never a value.
     */
    public Extension {
        values = map(values, 1);
        final int bytes = Json.write(values).getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MOST_BYTES) {
            throw new IllegalArgumentException(
                    "the extension is " + bytes + " bytes as JSON, and one may have " + MOST_BYTES);
        }
    }

    /**
     * Tells whether the project keeps nothing for the adapter.
     *
     * @return {@code true} when there is nothing to send.
     */
    public boolean isEmpty() {
        return values.isEmpty();
    }

    private static Map<String, Object> map(final Map<?, ?> given, final int depth) {
        if (depth > MOST_DEPTH) {
            throw new IllegalArgumentException(
                    "the extension nests deeper than " + MOST_DEPTH + " levels");
        }
        final Map<String, Object> copy = new LinkedHashMap<>();
        for (final Map.Entry<?, ?> entry : given.entrySet()) {
            if (!(entry.getKey() instanceof String key) || key.isBlank()) {
                throw new IllegalArgumentException("the extension holds a blank or non-text key");
            }
            copy.put(key, value(entry.getValue(), depth));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Object value(final @Nullable Object given, final int depth) {
        return switch (given) {
            case String text -> text;
            case Boolean truth -> truth;
            case Number number when unchangeable(number) && Double.isFinite(number.doubleValue()) ->
                    number;
            case Map<?, ?> nested -> map(nested, depth + 1);
            case List<?> list -> list(list, depth + 1);
            case null, default ->
                    throw new IllegalArgumentException(
                            "the extension holds a value that is no text, truth value, finite"
                                    + " number, list or map");
        };
    }

    /**
     * Only a number that cannot change after it was checked: an atomic or an adder could turn NaN
     * or grow past the size limit before the block is written.
     */
    private static boolean unchangeable(final Number number) {
        return number instanceof Integer
                || number instanceof Long
                || number instanceof Short
                || number instanceof Byte
                || number instanceof Double
                || number instanceof Float
                || number instanceof java.math.BigInteger
                || number instanceof java.math.BigDecimal;
    }

    private static List<Object> list(final List<?> given, final int depth) {
        if (depth > MOST_DEPTH) {
            throw new IllegalArgumentException(
                    "the extension nests deeper than " + MOST_DEPTH + " levels");
        }
        final List<Object> copy = new ArrayList<>();
        for (final Object each : given) {
            copy.add(value(each, depth));
        }
        return Collections.unmodifiableList(copy);
    }

    /**
     * Reads the block a call carries.
     *
     * @param parameters The call's parameters.
     * @return The block; {@link #NONE} when the call carries none.
     * @throws IllegalArgumentException When it is no object, or not one the contract carries.
     */
    static Extension of(final Map<?, ?> parameters) {
        final Object given = parameters.get("extension");
        if (given == null) {
            return NONE;
        }
        if (!(given instanceof Map<?, ?> block)) {
            throw new IllegalArgumentException("the extension is no object");
        }
        return new Extension(map(block, 1));
    }
}
