package org.fuin.porelo.acceptance;

/** Where a scenario's agent records tool calls, relative to its home in the container. */
record ToolRecords(String directory, String suffix) {

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
        if (suffix.isBlank() || !suffix.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException(
                    "a tool record suffix is a plain file name ending, not '" + suffix + "'");
        }
    }

    /** Prints the newest record's path and marker count, or nothing when there is none. */
    String command(String marker) {
        return "f=$(find \"$HOME/"
                + directory
                + "\" -type f -name '*"
                + suffix
                + "' -printf '%T@ %p\\n' 2>/dev/null | sort -nr | head -n 1 | cut -d ' ' -f 2-);"
                + " if [ -n \"$f\" ]; then n=$(grep -oF -- "
                + Shell.quote(marker)
                + " \"$f\" | wc -l); printf '\\037%s\\t%s\\n' \"$f\" \"$n\"; fi";
    }
}
