package org.fuin.porelo.packagecheck;

import static org.assertj.core.api.Assertions.assertThat;

import org.fuin.porelo.agent.api.AgentProtocol;
import org.junit.jupiter.api.Test;

class AgentProtocolProvidedTest {

    private static final int LOWEST = AgentProtocol.LOWEST;

    private final Reports out = new Reports();

    /** What {@code rpm -qp --provides} prints: the package itself first, one capability a line. */
    private static String rpmProvides(String... more) {
        final StringBuilder lines =
                new StringBuilder(
                        "porelo = 0.5.1~snapshot.500-1\nporelo(x86-64) = 0.5.1~snapshot.500-1\n");
        for (String line : more) {
            lines.append(line).append('\n');
        }
        return lines.toString();
    }

    @Test
    void passesBothPackagesProvidingTheLowestProtocolPoreloAccepts() {
        final String provided = "porelo-agent-protocol-" + LOWEST;

        AgentProtocolProvided.check(provided, rpmProvides(provided), out.report);

        assertThat(out.report.failures()).as(out.text()).isZero();
    }

    @Test
    void failsAPackageProvidingOneBelowTheLowestAndNamesBothNumbers() {
        final String below = "porelo-agent-protocol-" + (LOWEST - 1);

        AgentProtocolProvided.check(
                below, rpmProvides("porelo-agent-protocol-" + LOWEST), out.report);

        assertThat(out.report.failures())
                .as(
                        "an agent package built for an older protocol would install beside a porelo that refuses it")
                .isEqualTo(1);
        assertThat(out.text())
                .as("the failure names what the deb provides and what porelo needs")
                .contains("the deb provides agent protocol " + (LOWEST - 1))
                .contains("porelo's lowest is " + LOWEST);
    }

    @Test
    void failsAPackageProvidingNoAgentProtocol() {
        AgentProtocolProvided.check("porelo-agent-protocol-" + LOWEST, rpmProvides(), out.report);

        assertThat(out.report.failures())
                .as("an rpm without the capability makes dnf refuse every agent package")
                .isEqualTo(1);
        assertThat(out.text()).contains("the rpm provides no porelo-agent-protocol-<n>");
    }

    @Test
    void failsAPackageProvidingTwoAgentProtocols() {
        final String two =
                "porelo-agent-protocol-" + LOWEST + ", porelo-agent-protocol-" + (LOWEST + 1);

        AgentProtocolProvided.check(
                two, rpmProvides("porelo-agent-protocol-" + LOWEST), out.report);

        assertThat(out.report.failures())
                .as("the package provides one number, its lowest, never a list to choose from")
                .isEqualTo(1);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {"foo-porelo-agent-protocol-%d", "porelo-agent-protocol-%d-foo"})
    void failsANameThatOnlyHoldsTheCapability(String form) {
        final String other = String.format(form, LOWEST);

        AgentProtocolProvided.check(
                other, rpmProvides("porelo-agent-protocol-" + LOWEST), out.report);

        assertThat(out.report.failures())
                .as("another package's capability is no agent protocol porelo provides")
                .isEqualTo(1);
    }

    @Test
    void failsAProtocolThatOnlyBeginsWithTheLowest() {
        AgentProtocolProvided.check(
                "porelo-agent-protocol-" + LOWEST + "0",
                rpmProvides("porelo-agent-protocol-" + LOWEST + " = 0.5.1"),
                out.report);

        assertThat(out.report.failures()).isEqualTo(1);
        assertThat(out.text()).contains("the deb provides agent protocol " + LOWEST + "0");
    }
}
