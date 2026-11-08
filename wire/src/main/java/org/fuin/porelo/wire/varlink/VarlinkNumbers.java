package org.fuin.porelo.wire.varlink;

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
}
