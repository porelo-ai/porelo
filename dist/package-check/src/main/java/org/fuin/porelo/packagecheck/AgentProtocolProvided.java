package org.fuin.porelo.packagecheck;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.fuin.porelo.agent.api.AgentProtocol;

/**
 * Checks that both packages provide {@code porelo-agent-protocol-<LOWEST>}, the capability an
 * agent's package depends on.
 *
 * <p>The number is written in the root pom for the two package descriptions, and read here from
 * {@link AgentProtocol} itself, so a raised {@code LOWEST} with the pom left behind fails here
 * instead of letting a package manager install an agent this Porelo refuses.
 */
final class AgentProtocolProvided {

    private static final Pattern PROVIDED = Pattern.compile("\\bporelo-agent-protocol-(\\d+)\\b");

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
        one("the deb", debProvides, report);
        one("the rpm", rpmProvides, report);
    }

    private static void one(String label, String provides, Report report) {
        final List<Integer> numbers = numbers(provides);
        final int lowest = AgentProtocol.LOWEST;
        if (numbers.isEmpty()) {
            report.fail(
                    label + " provides no porelo-agent-protocol-<n>; porelo's lowest is " + lowest);
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

    private static List<Integer> numbers(String provides) {
        final Matcher matcher = PROVIDED.matcher(provides);
        return matcher.results().map(found -> Integer.parseInt(found.group(1))).toList();
    }
}
