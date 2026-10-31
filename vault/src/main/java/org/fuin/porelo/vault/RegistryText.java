package org.fuin.porelo.vault;

import java.util.regex.Pattern;

/**
 * Makes text from a registry, a forge or Git safe to print: every diagnostic of the registry's
 * reader and writer passes through here before it reaches a terminal or JSON.
 */
public final class RegistryText {

    /**
     * Every control character, C0 and C1 alike, every format character (the bidirectional overrides
     * and isolates among them) and the line and paragraph separators.
     */
    private static final Pattern UNPRINTABLE =
            Pattern.compile("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]", Pattern.UNICODE_CHARACTER_CLASS);

    private RegistryText() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Replaces what could move a terminal's cursor, reorder what it shows or hide text.
     *
     * @param text The text.
     * @return The text, each such character a {@code ?}.
     */
    public static String printable(String text) {
        return UNPRINTABLE.matcher(text).replaceAll("?");
    }
}
