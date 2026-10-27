package org.fuin.porelo.acceptance;

import org.jspecify.annotations.Nullable;

/**
 * Where a scenario's agent records tool calls, relative to its home in the container.
 *
 * @param directory The directory under the agent's home.
 * @param suffix The record files' name ending.
 * @param emptyCall What a call that printed nothing looks like in the record, or {@code null}.
 */
record ToolRecords(String directory, String suffix, @Nullable String emptyCall) {

    ToolRecords(String directory, String suffix) {
        this(directory, suffix, null);
    }

    ToolRecords {
        if (directory.isBlank()
                || directory.startsWith("/")
                || directory.contains("..")
                || !directory.matches("[A-Za-z0-9._/-]+")) {
            throw new IllegalArgumentException(
                    "a tool record directory is a plain path under the agent's home, not '"
                            + directory
                            + "'");
        }
        if (emptyCall != null && emptyCall.isBlank()) {
            throw new IllegalArgumentException("an empty call that is blank is on every line");
        }
        if (suffix.isBlank() || !suffix.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException(
                    "a tool record suffix is a plain file name ending, not '" + suffix + "'");
        }
    }

    /**
     * Prints the newest record's path and how many failed tries it holds, or nothing when there is
     * none.
     *
     * <p>Each literal marker is a try, and so is the first declared empty call after it on the same
     * line, ending before the next marker: a bare call right after a refused one prints nothing, so
     * it carries no marker of its own. An empty call anywhere else is a quiet command that worked,
     * and no try. Both strings reach {@code awk} through its environment, never its program, so
     * neither is read as a pattern or an escape.
     */
    String command(String marker) {
        if (marker.isEmpty()) {
            // awk's index() finds the empty string everywhere and never moves past it.
            throw new IllegalArgumentException(
                    "a tool failure marker that is empty is on every line");
        }
        return "f=$(find \"$HOME/"
                + directory
                + "\" -type f -name '*"
                + suffix
                + "' -printf '%T@ %p\\n' 2>/dev/null | sort -nr | head -n 1 | cut -d ' ' -f 2-);"
                + " if [ -n \"$f\" ]; then n=$(M="
                + Shell.quote(marker)
                + " E="
                + Shell.quote(emptyCall == null ? "" : emptyCall)
                + " awk "
                + Shell.quote(COUNT)
                + " \"$f\"); printf '\\037%s\\t%s\\n' \"$f\" \"$n\"; fi";
    }

    /** Counts markers, and a declared empty call right after one, in every line of a record. */
    private static final String COUNT =
            "BEGIN { m = ENVIRON[\"M\"]; e = ENVIRON[\"E\"]; n = 0 }"
                    + " { s = $0;"
                    + " while ((i = index(s, m)) > 0) {"
                    + " n++; s = substr(s, i + length(m));"
                    + " if (e != \"\") { j = index(s, e); k = index(s, m);"
                    + " if (j > 0 && (k == 0 || j + length(e) <= k)) { n++; s = substr(s, j + length(e)) } } } }"
                    + " END { print n }";
}
