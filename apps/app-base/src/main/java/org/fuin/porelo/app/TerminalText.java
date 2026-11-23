package org.fuin.porelo.app;

/**
 * Agent-controlled text made safe to print to a person's terminal: every control character shows as
 * its escape instead of acting.
 *
 * <p>Printed raw, a file name, a subject or a patch line carrying {@code ESC[8m} or {@code ESC[1A
 * ESC[2K} hid or erased what was printed around it - the reviewed commit and the approval command
 * among it - and the person approved what they had not seen.
 */
final class TerminalText {

    private TerminalText() {}

    /**
     * Returns one line of text with C0 and C1 controls and DEL written as {@code \xNN}; tabs kept.
     *
     * @param text Text nobody has cleared: a file name, a subject, a branch, a held message.
     * @return The same, safe to print.
     */
    static String line(final String text) {
        final StringBuilder safe = new StringBuilder(text.length());
        text.codePoints()
                .forEach(
                        point -> {
                            if (point == '\t' || point >= 0x20 && point < 0x7f || point > 0x9f) {
                                safe.appendCodePoint(point);
                            } else {
                                safe.append(String.format("\\x%02x", point));
                            }
                        });
        return safe.toString();
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
