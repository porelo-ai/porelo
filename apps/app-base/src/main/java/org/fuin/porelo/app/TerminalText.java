package org.fuin.porelo.app;

/**
 * Agent-controlled text made safe to print to a person's terminal: every control character and
 * every Unicode format character shows as its escape instead of acting.
 *
 * <p>Printed raw, a file name, a subject or a patch line carrying {@code ESC[8m} or {@code ESC[1A
 * ESC[2K} hid or erased what was printed around it - the reviewed commit and the approval command
 * among it - and the person approved what they had not seen. A bidirectional override or isolate
 * shows a line in another order than it was written, and a zero-width character hides what tells
 * two names apart.
 */
final class TerminalText {

    private TerminalText() {}

    /**
     * Returns one line of text with C0 and C1 controls and DEL written as {@code \xNN}, and format
     * characters (Unicode category Cf), line and paragraph separators and lone surrogates as a
     * backslash, {@code u} and their code point in hex, in braces above the basic plane; tabs kept.
     *
     * @param text Text nobody has cleared: a file name, a subject, a branch, a held message.
     * @return The same, safe to print.
     */
    static String line(final String text) {
        final StringBuilder safe = new StringBuilder(text.length());
        text.codePoints()
                .forEach(
                        point -> {
                            if (hidden(point)) {
                                safe.append(
                                        point > 0xffff
                                                ? String.format("\\u{%x}", point)
                                                : String.format("\\u%04x", point));
                            } else if (point == '\t'
                                    || point >= 0x20 && point < 0x7f
                                    || point > 0x9f) {
                                safe.appendCodePoint(point);
                            } else {
                                safe.append(String.format("\\x%02x", point));
                            }
                        });
        return safe.toString();
    }

    /**
     * Whether a character changes what a line shows without showing itself: a format character
     * (bidi overrides and isolates, zero-width ones), a line or paragraph separator, or half of a
     * surrogate pair standing alone.
     */
    private static boolean hidden(final int point) {
        final int type = Character.getType(point);
        return type == Character.FORMAT
                || type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR
                || type == Character.SURROGATE;
    }

    /**
     * Returns text of several lines, each escaped as {@link #line}, with the line breaks a log or a
     * patch needs kept as line feeds.
     *
     * @param text What git printed of the agent's work.
     * @return The same, safe to print.
     */
    static String lines(final String text) {
        final StringBuilder safe = new StringBuilder(text.length());
        text.lines().forEach(each -> safe.append(line(each)).append('\n'));
        return text.endsWith("\n") || safe.isEmpty()
                ? safe.toString()
                : safe.substring(0, safe.length() - 1);
    }
}
