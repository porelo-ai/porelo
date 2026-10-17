package org.fuin.porelo.packagecheck;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class InstallTest {

    private static final String VERSION = "0.1.0~snapshot.177";

    private static final String EVERYTHING = """
            PORELO-OK
            REPORTED:porelo 0.1.0~snapshot.177
            DESCRIBED
            NO-PORELO-NAME
            AGENT-OK
            SETUP-OK
            HOOKS-PACKAGED
            DISCOVERY-OK
            """;

    private final Reports out = new Reports();

    @Test
    void passesAnInstallThatDidEveryStep() {
        Install.verdicts("Debian", EVERYTHING, VERSION, out.report);

        assertThat(out.report.failures()).as(out.text()).isZero();
        assertThat(out.text()).contains("the binary names the build it came from (0.1.0~snapshot.177)");
    }

    @Test
    void namesTheStepThatDidNotHappenAndShowsWhatTheContainerSaid() {
        final String output = EVERYTHING.replace("AGENT-OK\n", "Depends: porelo but it is not installable\n");

        Install.verdicts("Debian", output, VERSION, out.report);

        assertThat(out.text()).contains("FAIL  AGENT-OK on Debian").contains("Depends: porelo but it is not installable");
        assertThat(out.report.failures()).isEqualTo(1);
    }

    @Test
    void saysWhichStepFailedAndWhyRatherThanOnlyThatNothingWasReported() {
        final String output = "FAILED: apt-get update\nFAILED:   E: Failed to fetch http://azure.archive.ubuntu.com/ubuntu/"
                + "dists/noble/InRelease  Could not connect\nREPORTED:\n";

        Install.verdicts("Debian", output, VERSION, out.report);

        assertThat(out.text()).contains("FAILED: apt-get update").contains("Could not connect");
    }

    @Test
    void everyInstallStepThatFailsSaysSo() {
        final Install install = new Install(Path.of("/d/porelo.deb"), Path.of("/r/porelo.rpm"), Path.of("/a/stub.deb"));

        assertThat(install.debian()).contains("|| failed \"apt-get update\"").contains("|| failed \"installing porelo\"")
                .doesNotContain(">/dev/null 2>&1\n");
        assertThat(install.fedora()).contains("|| failed \"installing porelo\"");
    }

    @Test
    void failsABinaryThatDoesNotNameTheBuildItCameFrom() {
        // Every build once answered 0.1.0-SNAPSHOT while its package carried a build number.
        Install.verdicts("Fedora", EVERYTHING.replace("porelo 0.1.0~snapshot.177", "porelo 0.1.0-SNAPSHOT"), VERSION, out.report);

        assertThat(out.text()).contains("FAIL  the package is 0.1.0~snapshot.177 but the binary says 'porelo 0.1.0-SNAPSHOT'");
    }

    @Test
    void failsABinaryThatSaysNothing() {
        Install.verdicts("Fedora", "", VERSION, out.report);

        assertThat(out.text()).contains("but the binary says 'nothing'");
        assertThat(out.report.failures()).isEqualTo(Install.MARKERS.size() + 1);
    }

    @Test
    void installsTheAgentAfterPoreloOnBothSides() {
        final Install install = new Install(Path.of("/d/porelo.deb"), Path.of("/r/porelo.rpm"), Path.of("/a/stub.deb"));

        assertThat(install.debian()).containsSubsequence("/deb/porelo_*.deb", "/agent/porelo-agent-stub_*.deb", "porelo setup");
        assertThat(install.fedora()).containsSubsequence("/rpm/porelo-0*.rpm", "/agent/porelo-agent-stub-*.rpm", "porelo setup");
    }

    @Test
    void installsDebianFromItsOwnMirrorsAndUbuntuFromTheOnePoreloWrites() {
        // Ubuntu's mirror in Debian's sources would serve it another distribution's release.
        final Install install = new Install(Path.of("/d/porelo.deb"), Path.of("/r/porelo.rpm"), Path.of("/a/stub.deb"));
        assertThat(install.ubuntu()).contains("azure.archive.ubuntu.com");
        assertThat(install.debian()).doesNotContain("ubuntu.com").contains("apt-get update");
    }

    @Test
    void findsTheAgentByItsDescriptionAndByNoFileOfPorelosName() {
        // A vendor's package names its program freely and registers it with one file under agents.d.
        final Install install = new Install(Path.of("/d/porelo.deb"), Path.of("/r/porelo.rpm"), Path.of("/a/stub.deb"));

        assertThat(Install.MARKERS).containsKeys("DESCRIBED", "NO-PORELO-NAME");
        for (final String script : java.util.List.of(install.debian(), install.ubuntu(), install.fedora())) {
            assertThat(script).contains("--verbose").contains("/usr/share/porelo/agents.d/").contains("echo DESCRIBED")
                    .contains("/usr/libexec/porelo/agents/porelo-agent-").contains("echo NO-PORELO-NAME");
        }
    }

    @Test
    void anInstallThatFoundTheAgentByItsOldNameFails() {
        Install.verdicts("Fedora", EVERYTHING.replace("NO-PORELO-NAME\n", ""), VERSION, out.report);

        assertThat(out.text()).contains("FAIL  NO-PORELO-NAME on Fedora");
    }
}
