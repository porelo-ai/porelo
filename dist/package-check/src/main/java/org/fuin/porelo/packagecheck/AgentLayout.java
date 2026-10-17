package org.fuin.porelo.packagecheck;

import java.util.List;

/**
 * Checks that an agent's package installs as a vendor's would: its program under a name of its own,
 * registered by one description under {@code agents.d}, and nothing in the directory Porelo scans
 * by file name.
 *
 * <p>The stub is packaged that way so the install in a clean container proves the description finds
 * it. A package that still put a {@code porelo-agent-*} file into the scanned directory would be
 * found by that name and prove nothing.
 */
final class AgentLayout {

    /** Where a package puts the one file that registers its agent. */
    static final String DESCRIPTIONS = "/usr/share/porelo/agents.d/";

    /** Where Porelo still finds an agent by its file name alone. */
    static final String SCANNED = "/usr/libexec/porelo/agents/";

    private AgentLayout() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Checks what one agent package installs.
     *
     * @param label the package, for the report
     * @param files every file the package installs, by absolute path
     * @param report where the verdicts go
     */
    static void check(String label, List<String> files, Report report) {
        final List<String> described =
                files.stream()
                        .filter(file -> file.startsWith(DESCRIPTIONS) && file.endsWith(".yaml"))
                        .toList();
        report.check(
                !described.isEmpty(),
                label + " registers its agent with " + String.join(", ", described),
                label + " carries no description under " + DESCRIPTIONS);
        final List<String> named = files.stream().filter(file -> file.startsWith(SCANNED)).toList();
        report.check(
                named.isEmpty(),
                label + " installs nothing under " + SCANNED,
                label
                        + " installs "
                        + String.join(", ", named)
                        + ", where the agent is found by its name");
    }
}
