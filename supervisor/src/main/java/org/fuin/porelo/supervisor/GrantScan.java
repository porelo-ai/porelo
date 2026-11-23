package org.fuin.porelo.supervisor;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Looks for a refused token grant in text that arrives in pieces, of any length, holding only a
 * bounded window of it.
 *
 * <p><strong>Three readings, each produced as the text arrives.</strong> The text as written, the
 * text form-decoded ({@code %xx} and {@code +}) and the text JSON-unescaped ({@code \}{@code
 * uXXXX}): a request spelled in any of them asks the provider the same question. Each reading
 * carries its own unfinished escape into the next piece, so an escape split between two pieces is
 * decoded as if it had arrived whole. A stray {@code %} or backslash is read as itself, and the
 * reading goes on; it never turns a reading off for the rest of the text.
 *
 * <p><strong>A bounded match, so a bounded overlap.</strong> The pattern allows any number of
 * separators between {@code grant_type} and its value, so a match has no longest length, and no
 * fixed overlap between two windows would be certain to hold it. Each reading therefore gives a run
 * of separators as its first one alone: a match in the shortened text is a match in the original
 * and the other way round, since no separator stands in the pattern but in that run. The longest
 * match is then {@link #LONGEST} characters, and each window keeps the last {@code LONGEST - 1}
 * characters of the one before.
 *
 * <p>Characters past ASCII are read as themselves and never match: the pattern is ASCII, and its
 * {@code \s} is ASCII whitespace.
 */
final class GrantScan {

    /** The grants refused, as a request body names them. */
    static final Pattern MINTING_GRANT =
            Pattern.compile(
                    "grant_type[\"'=:\\s]+(" + String.join("|", VaultProxy.REFUSED_GRANTS) + ")");

    /** The longest match, once a run of separators is read as one. */
    static final int LONGEST =
            "grant_type".length()
                    + 1
                    + VaultProxy.REFUSED_GRANTS.stream().mapToInt(String::length).max().orElse(0);

    /** How much a window holds before it is searched and cut back. */
    private static final int WINDOW = 16 * 1024;

    private final List<Reading> readings =
            List.of(
                    new Reading(Decoding.AS_WRITTEN),
                    new Reading(Decoding.FORM),
                    new Reading(Decoding.JSON));

    private boolean found;

    /**
     * Returns whether a text names a refused grant, in any of the three readings.
     *
     * @param text The whole text.
     * @return Whether it does.
     */
    static boolean names(final CharSequence text) {
        final GrantScan scan = new GrantScan();
        for (int i = 0; i < text.length() && !scan.found; i++) {
            scan.accept(text.charAt(i));
        }
        return scan.end();
    }

    /**
     * Reads the next piece of bytes, each as one character.
     *
     * @param bytes Where the piece is.
     * @param offset Where it starts.
     * @param length How long it is.
     * @return Whether a refused grant has been found, in this piece or before.
     */
    boolean accept(final byte[] bytes, final int offset, final int length) {
        for (int i = offset; i < offset + length && !found; i++) {
            accept((char) (bytes[i] & 0xFF));
        }
        search();
        return found;
    }

    /**
     * Ends the text: an unfinished escape is read as written, and the rest of each window searched.
     *
     * @return Whether a refused grant has been found.
     */
    boolean end() {
        for (final Reading reading : readings) {
            reading.end();
        }
        search();
        return found;
    }

    /**
     * Returns whether a refused grant has been found.
     *
     * @return Whether it has.
     */
    boolean found() {
        return found;
    }

    private void accept(final char each) {
        for (final Reading reading : readings) {
            reading.decode(each);
        }
    }

    private void search() {
        for (final Reading reading : readings) {
            found |= reading.search();
        }
    }

    /** What a reading decodes. */
    private enum Decoding {
        AS_WRITTEN,
        FORM,
        JSON
    }

    /** One reading: its unfinished escape, and its window. */
    private static final class Reading {

        private final Decoding decoding;

        /** An escape begun and not yet ended: {@code %} or a backslash, and what came after. */
        private final StringBuilder escape = new StringBuilder();

        private final StringBuilder window = new StringBuilder(WINDOW + LONGEST);

        private boolean afterSeparator;

        private boolean found;

        Reading(final Decoding decoding) {
            this.decoding = decoding;
        }

        void decode(final char each) {
            if (decoding == Decoding.AS_WRITTEN) {
                emit(each);
                return;
            }
            if (escape.isEmpty()) {
                if (each == opener()) {
                    escape.append(each);
                } else {
                    emit(decoding == Decoding.FORM && each == '+' ? ' ' : each);
                }
                return;
            }
            escape.append(each);
            final int digits = decoding == Decoding.FORM ? 2 : 4;
            final int lead = decoding == Decoding.FORM ? 1 : 2;
            final boolean fits =
                    decoding == Decoding.JSON && escape.length() == 2 ? each == 'u' : hex(each);
            if (!fits) {
                // Not an escape after all: its opener is read as itself, and the rest again, as a
                // pattern that tries each position in turn would read it.
                final String rest = escape.substring(1);
                escape.setLength(0);
                emit(opener());
                for (int i = 0; i < rest.length(); i++) {
                    decode(rest.charAt(i));
                }
                return;
            }
            if (escape.length() == lead + digits) {
                final char decoded = (char) Integer.parseInt(escape.substring(lead), 16);
                escape.setLength(0);
                emit(decoded);
            }
        }

        /** An ASCII hex digit, as a pattern's {@code [0-9a-fA-F]} reads one. */
        private static boolean hex(final char each) {
            return each >= '0' && each <= '9'
                    || each >= 'a' && each <= 'f'
                    || each >= 'A' && each <= 'F';
        }

        private char opener() {
            return decoding == Decoding.FORM ? '%' : '\\';
        }

        void end() {
            final String rest = escape.toString();
            escape.setLength(0);
            for (int i = 0; i < rest.length(); i++) {
                emit(rest.charAt(i));
            }
        }

        private void emit(final char each) {
            final boolean separator = separator(each);
            if (separator && afterSeparator) {
                return;
            }
            afterSeparator = separator;
            window.append(each);
            if (window.length() >= WINDOW + LONGEST) {
                search();
            }
        }

        /** Searches the window, then keeps only what a match ending later could start in. */
        boolean search() {
            if (!found && MINTING_GRANT.matcher(window).find()) {
                found = true;
            }
            if (window.length() > LONGEST - 1) {
                window.delete(0, window.length() - (LONGEST - 1));
            }
            return found;
        }

        private static boolean separator(final char each) {
            return switch (each) {
                case '"', '\'', '=', ':', ' ', '\t', '\n', '\u000B', '\f', '\r' -> true;
                default -> false;
            };
        }
    }
}
