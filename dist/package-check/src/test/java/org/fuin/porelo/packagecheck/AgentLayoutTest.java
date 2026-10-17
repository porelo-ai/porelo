package org.fuin.porelo.packagecheck;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class AgentLayoutTest {

    private final Reports out = new Reports();

    @Test
    void passesAnAgentPackagedAsAVendorsWouldBe() {
        AgentLayout.check("the agent deb", List.of("/usr/lib/porelo-agent-x/x", "/usr/share/porelo/agents.d/x.yaml",
                "/usr/share/porelo/sbom/porelo-agent-x.cdx.json"), out.report);

        assertThat(out.report.failures()).as(out.text()).isZero();
        assertThat(out.text()).contains("/usr/share/porelo/agents.d/x.yaml");
    }

    @Test
    void failsAPackageWithoutADescription() {
        AgentLayout.check("the agent rpm", List.of("/usr/lib/porelo-agent-x/x"), out.report);

        assertThat(out.text()).contains("FAIL  the agent rpm carries no description under /usr/share/porelo/agents.d/");
    }

    @Test
    void failsAPackageThatStillInstallsUnderPorelosName() {
        // Found by the old name, the package would pass the install while proving nothing about a description.
        AgentLayout.check("the agent deb", List.of("/usr/libexec/porelo/agents/porelo-agent-x",
                "/usr/share/porelo/agents.d/x.yaml"), out.report);

        assertThat(out.text()).contains("FAIL  the agent deb installs /usr/libexec/porelo/agents/porelo-agent-x");
    }
}
