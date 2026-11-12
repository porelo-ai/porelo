package org.fuin.porelo.packagecheck;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.fuin.porelo.agent.api.AgentProtocol;

/**
 * Checks that both packages provide {@code porelo-agent-protocol} at version {@code LOWEST}, the
 * capability an agent's package depends on.
 *
 * <p>The number is written in the root pom for the two package descriptions, and read here from
 * {@link AgentProtocol} itself, so a raised {@code LOWEST} with the pom left behind fails here
 * instead of letting a package manager install an agent this Porelo refuses.
 */
final class AgentProtocolProvided {

    private static final String NAME = "porelo-agent-protocol";

    // A whole entry, as dpkg-deb gives one ("porelo-agent-protocol (= 3)") and as rpm gives one
    // ("porelo-agent-protocol = 3"): a plain number, no epoch, tilde or release, which would move
    // it past an agent's bounds.
    private static final Pattern DEB = Pattern.compile("porelo-agent-protocol \\(= (\\d+)\\)");

    private static final Pattern RPM = Pattern.compile("porelo-agent-protocol = (\\d+)");

    private AgentProtocolProvided() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Checks what each package provides.
     *
     * @param debProvides the deb's {@code Provides} field
     * @param rpmProvides what {@code rpm -qp --provides} prints, one capability a line
     * @param report where the verdicts go
     */
    static void check(String debProvides, String rpmProvides, Report report) {
        one("the deb", debProvides.split(","), DEB, report);
        one("the rpm", rpmProvides.split("\\R"), RPM, report);
    }

    private static void one(String label, String[] entries, Pattern exact, Report report) {
        final List<Integer> numbers = new java.util.ArrayList<>();
        final int lowest = AgentProtocol.LOWEST;
        for (final String raw : entries) {
            final String entry = raw.strip();
            if (!entry.contains(NAME)) {
                continue;
            }
            final Matcher matcher = exact.matcher(entry);
            if (!matcher.matches()) {
                // Unversioned, it meets every agent's bounds; another name or version, none sure.
                report.fail(
                        label
                                + " provides '"
                                + entry
                                + "'; only "
                                + NAME
                                + " at porelo's lowest, "
                                + lowest
                                + ", as a plain number");
                return;
            }
            numbers.add(Integer.parseInt(matcher.group(1)));
        }
        if (numbers.isEmpty()) {
            report.fail(
                    label
                            + " provides no porelo-agent-protocol at a version; porelo's lowest is "
                            + lowest);
        } else if (numbers.size() > 1) {
            report.fail(
                    label
                            + " provides agent protocols "
                            + numbers
                            + "; it must provide only porelo's lowest, "
                            + lowest);
        } else {
            final int provided = numbers.getFirst();
            report.check(
                    provided == lowest,
                    label + " provides agent protocol " + lowest + ", porelo's lowest",
                    label
                            + " provides agent protocol "
                            + provided
                            + ", but porelo's lowest is "
                            + lowest);
        }
    }
}
