package org.fuin.porelo.packagecheck;

import static org.assertj.core.api.Assertions.assertThat;

import org.fuin.porelo.agent.api.AgentProtocol;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AgentProtocolProvidedTest {

    private static final int LOWEST = AgentProtocol.LOWEST;

    private final Reports out = new Reports();

    /** What {@code dpkg-deb -f <deb> Provides} prints for a versioned capability. */
    private static String deb(int version) {
        return "porelo-agent-protocol (= " + version + ")";
    }

    /** One line of {@code rpm -qp --provides} for a versioned capability. */
    private static String rpm(int version) {
        return "porelo-agent-protocol = " + version;
    }

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
        AgentProtocolProvided.check(deb(LOWEST), rpmProvides(rpm(LOWEST)), out.report);

        assertThat(out.report.failures()).as(out.text()).isZero();
    }

    @Test
    void failsAPackageProvidingOneBelowTheLowestAndNamesBothNumbers() {
        AgentProtocolProvided.check(deb(LOWEST - 1), rpmProvides(rpm(LOWEST)), out.report);

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
        AgentProtocolProvided.check(deb(LOWEST), rpmProvides(), out.report);

        assertThat(out.report.failures())
                .as("an rpm without the capability makes dnf refuse every agent package")
                .isEqualTo(1);
        assertThat(out.text()).contains("the rpm provides no porelo-agent-protocol at a version");
    }

    @ParameterizedTest
    @ValueSource(strings = {"porelo-agent-protocol", "porelo-agent-protocol-%d"})
    void failsACapabilityWithoutAVersion(String form) {
        // Unversioned, no agent's '>=' or '<=' is met; a number in the name is the old form.
        AgentProtocolProvided.check(
                String.format(form, LOWEST), rpmProvides(rpm(LOWEST)), out.report);

        assertThat(out.report.failures()).isEqualTo(1);
    }

    @Test
    void failsAPackageProvidingTwoAgentProtocols() {
        AgentProtocolProvided.check(
                deb(LOWEST) + ", " + deb(LOWEST + 1), rpmProvides(rpm(LOWEST)), out.report);

        assertThat(out.report.failures())
                .as("the package provides one number, its lowest, never a list to choose from")
                .isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "foo-porelo-agent-protocol (= %d)",
                "porelo-agent-protocol-foo (= %d)",
                "porelo-agent-protocol (= %d.1)"
            })
    void failsANameThatOnlyHoldsTheCapability(String form) {
        AgentProtocolProvided.check(
                String.format(form, LOWEST), rpmProvides(rpm(LOWEST)), out.report);

        assertThat(out.report.failures())
                .as("another package's capability is no agent protocol porelo provides")
                .isEqualTo(1);
    }

    @Test
    void failsAProtocolThatOnlyBeginsWithTheLowest() {
        AgentProtocolProvided.check(deb(LOWEST * 10), rpmProvides(rpm(LOWEST)), out.report);

        assertThat(out.report.failures()).isEqualTo(1);
        assertThat(out.text()).contains("the deb provides agent protocol " + LOWEST * 10);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "porelo-agent-protocol = %d:1",
                "porelo-agent-protocol = %d~rc1",
                "porelo-agent-protocol = %d-1",
                "porelo-agent-protocol"
            })
    void failsAnRpmCapabilityThatIsNoPlainNumber(String form) {
        // An epoch passes '<= 5', a tilde fails '>= 3', unversioned meets every bound.
        AgentProtocolProvided.check(
                deb(LOWEST), rpmProvides(rpm(LOWEST), String.format(form, LOWEST)), out.report);

        assertThat(out.report.failures()).as(out.text()).isEqualTo(1);
    }

    @Test
    void failsTheRightNumberTwice() {
        AgentProtocolProvided.check(
                deb(LOWEST) + ", " + deb(LOWEST), rpmProvides(rpm(LOWEST)), out.report);

        assertThat(out.report.failures())
                .as("one provide of one number, not a list, even of the right one")
                .isEqualTo(1);
    }

    @Test
    void failsAnotherNameThatEndsInTheCapability() {
        AgentProtocolProvided.check(
                "foo+porelo-agent-protocol (= " + LOWEST + ")",
                rpmProvides(rpm(LOWEST)),
                out.report);

        assertThat(out.report.failures()).isEqualTo(1);
    }
}
