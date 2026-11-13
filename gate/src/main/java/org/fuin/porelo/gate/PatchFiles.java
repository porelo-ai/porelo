package org.fuin.porelo.gate;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Splits a patch git printed into its files, so a patch that has to be shortened loses whole files
 * and never part of one.
 *
 * <p>A file's part starts at its {@code diff --git} line. Nothing else in a patch can start a line
 * that way: every line of a hunk starts with a space, a {@code +}, a {@code -} or a {@code \}.
 */
public final class PatchFiles {

    private static final String HEADER = "diff --git ";

    private PatchFiles() {}

    /**
     * One file's part of a patch.
     *
     * @param path Its path after the change, as the header names it; for a patch without renames,
     *     which is what the gate reads, the same path before and after.
     * @param text Its whole part, from its header line up to the next file's.
     */
    public record Part(String path, String text) {}

    /**
     * A patch cut into its files.
     *
     * @param preamble What comes before the first file's header: usually nothing.
     * @param parts Each file's part, in the patch's order.
     */
    public record Split(String preamble, List<Part> parts) {}

    /**
     * Cuts a patch at its file boundaries. The preamble and the parts together are the patch,
     * character for character.
     *
     * @param patch What {@code git diff --patch} or {@code git show --patch} printed.
     * @return Its preamble and its files.
     */
    public static Split split(final String patch) {
        final List<Integer> starts = new ArrayList<>();
        int at = 0;
        while (at < patch.length()) {
            if (patch.startsWith(HEADER, at)) {
                starts.add(at);
            }
            final int end = patch.indexOf('\n', at);
            if (end < 0) {
                break;
            }
            at = end + 1;
        }
        if (starts.isEmpty()) {
            return new Split(patch, List.of());
        }
        final List<Part> parts = new ArrayList<>(starts.size());
        for (int i = 0; i < starts.size(); i++) {
            final int start = starts.get(i);
            final int end = i + 1 < starts.size() ? starts.get(i + 1) : patch.length();
            final String text = patch.substring(start, end);
            final int line = text.indexOf('\n');
            parts.add(new Part(path(line < 0 ? text : text.substring(0, line)), text));
        }
        return new Split(patch.substring(0, starts.getFirst()), List.copyOf(parts));
    }

    /**
     * Reads the path a {@code diff --git} line names after the change.
     *
     * @param header The header line, without its line break.
     * @return The path, or the header itself when it has no shape this knows: never dropped.
     */
    static String path(final String header) {
        final String names = header.substring(HEADER.length());
        if (names.startsWith("\"")) {
            // A quoted pair: "a/<path>" "b/<path>", C-style escaped, the path's bytes as git has
            // them.
            final int[] end = new int[1];
            final String before = unquoted(names, 0, end);
            if (before != null && end[0] + 1 < names.length() && names.charAt(end[0]) == ' ') {
                final String after = unquoted(names, end[0] + 1, end);
                if (after != null && after.startsWith("b/")) {
                    return after.substring(2);
                }
            }
            return header;
        }
        // Unquoted, and the same path on both sides: "a/<path> b/<path>", so its length is known
        // even when the path itself holds " b/".
        final int length = (names.length() - "a/ b/".length()) / 2;
        if (length > 0
                && names.length() == 2 * length + "a/ b/".length()
                && names.startsWith("a/")
                && names.startsWith(" b/", 2 + length)) {
            return names.substring(2 + length + 3);
        }
        return header;
    }

    /** Reads one C-quoted string starting at a quote, or {@code null} when it is not one. */
    private static @Nullable String unquoted(final String text, final int from, final int[] end) {
        if (from >= text.length() || text.charAt(from) != '"') {
            return null;
        }
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int at = from + 1;
        while (at < text.length()) {
            final char c = text.charAt(at);
            if (c == '"') {
                end[0] = at + 1;
                return bytes.toString(StandardCharsets.UTF_8);
            }
            if (c != '\\') {
                final int point = text.codePointAt(at);
                bytes.writeBytes(Character.toString(point).getBytes(StandardCharsets.UTF_8));
                at += Character.charCount(point);
                continue;
            }
            if (at + 1 >= text.length()) {
                return null;
            }
            final char escaped = text.charAt(at + 1);
            switch (escaped) {
                case 'a' -> bytes.write(7);
                case 'b' -> bytes.write('\b');
                case 't' -> bytes.write('\t');
                case 'n' -> bytes.write('\n');
                case 'v' -> bytes.write(11);
                case 'f' -> bytes.write('\f');
                case 'r' -> bytes.write('\r');
                case '"' -> bytes.write('"');
                case '\\' -> bytes.write('\\');
                default -> {
                    if (escaped < '0' || escaped > '7' || at + 3 >= text.length()) {
                        return null;
                    }
                    try {
                        bytes.write(Integer.parseInt(text.substring(at + 1, at + 4), 8));
                    } catch (NumberFormatException ex) {
                        return null;
                    }
                    at += 4;
                    continue;
                }
            }
            at += 2;
        }
        return null;
    }
}
