package org.fuin.porelo.packagecheck;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class InstallTest {

    private static final String VERSION = "0.1.0~snapshot.177";

    private static final String EVERYTHING =
            """
            STEP: selecting noninteractive apt
            STEP: configuring apt timeouts
            STEP: apt-get update
            STEP: installing porelo
            STEP: installing the agent
            AGENT-OK
            STEP: porelo --version
            PORELO-OK
            STEP: reporting the installed version
            REPORTED:porelo 0.1.0~snapshot.177
            STEP: porelo setup
            SETUP-OK
            STEP: checking the packaged hook descriptors
            HOOKS-PACKAGED
            STEP: porelo agents
            DISCOVERY-OK
            STEP: porelo agents --verbose
            DESCRIBED
            STEP: checking the installed agent filenames
            NO-PORELO-NAME
            STEP: running the packaged setup script
            SETUP-SCRIPT-RUNS
            """;

    private final Reports out = new Reports();

    @Test
    void anInstallEndedAtItsLimitNamesTheLastStepItReached() {
        final Install install =
                new Install(
                        Path.of("/d/porelo.deb"),
                        Path.of("/r/porelo.rpm"),
                        Path.of("/a/stub.deb"),
                        (command, limit) ->
                                new Commands.Result(
                                        Commands.TIMED_OUT,
                                        "STEP: preparing apt\nSTEP: apt-get update\nwaiting for archive\n"));

        install.check("Ubuntu", Main.UBUNTU, install.ubuntu(), VERSION, out.report);

        assertThat(out.text())
                .as("a timed out install identifies the last step, even without a FAILED line")
                .contains("FAIL  Ubuntu ended at its limit during apt-get update");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ubuntu", "debian", "fedora", "fedora-oldest"})
    void onlyAptInstallsGetTenMinutesToReachTheArchive(String distribution) {
        final boolean apt = distribution.equals("ubuntu") || distribution.equals("debian");
        final Install install =
                new Install(
                        Path.of("/d/porelo.deb"),
                        Path.of("/r/porelo.rpm"),
                        Path.of("/a/stub.deb"),
                        (command, limit) -> {
                            assertThat(limit)
                                    .as("%s install passes its own limit to Commands", distribution)
                                    .isEqualTo(apt ? Duration.ofMinutes(10) : Commands.LIMIT);
                            return new Commands.Result(0, EVERYTHING);
                        });
        final String image =
                switch (distribution) {
                    case "ubuntu" -> Main.UBUNTU;
                    case "debian" -> Main.DEBIAN;
                    case "fedora" -> Main.FEDORA;
                    default -> Main.FEDORA_OLDEST;
                };
        final String script =
                switch (distribution) {
                    case "ubuntu" -> install.ubuntu();
                    case "debian" -> install.debian();
                    default -> install.fedora();
                };

        install.check(distribution, image, script, VERSION, out.report);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ubuntu", "debian"})
    void aptScriptsAnnounceEachStepBeforeRunningIt(String distribution) {
        final Install install =
                new Install(
                        Path.of("/d/porelo.deb"), Path.of("/r/porelo.rpm"), Path.of("/a/stub.deb"));
        final String script = distribution.equals("ubuntu") ? install.ubuntu() : install.debian();

        for (final String step :
                List.of(
                        "export DEBIAN_FRONTEND",
                        "printf '%s",
                        "apt-get update",
                        "apt-get install -y -qq /deb/",
                        "apt-get install -y -qq /agent/",
                        "porelo --version >/dev/null",
                        "echo \"REPORTED:",
                        "porelo setup",
                        "grep -qho",
                        "porelo agents 2>",
                        "porelo agents --verbose",
                        "ls /usr/libexec/",
                        "/usr/share/porelo/setup/porelo-setup.sh")) {
            assertThat(script)
                    .as(
                            "%s announces the step before %s on unredirected output",
                            distribution, step)
                    .containsPattern(
                            "echo \"STEP: [^\"\\n]+\"\\n" + java.util.regex.Pattern.quote(step));
        }
        if (distribution.equals("ubuntu")) {
            assertThat(script)
                    .as("Ubuntu announces the mirror change before running sed")
                    .containsPattern("echo \"STEP: [^\"\\n]+\"\\nsed -i");
        }
    }

    @Test
    void passesAnInstallThatDidEveryStep() {
        Install.verdicts("Debian", EVERYTHING, VERSION, out.report);

        assertThat(out.report.failures()).as(out.text()).isZero();
        assertThat(out.text())
                .contains("the binary names the build it came from (0.1.0~snapshot.177)");
    }

    @Test
    void anAgentInstallErrorRemainsVisibleAfterTheCommonProgressLines() {
        // With porelo unavailable, the common checks print progress without success markers.
        final String output =
                EVERYTHING
                        .replace("AGENT-OK\n", "Depends: porelo but it is not installable\n")
                        .replace("REPORTED:porelo " + VERSION, "REPORTED:")
                        .lines()
                        .filter(
                                line ->
                                        !Install.MARKERS.containsKey(line)
                                                || line.equals("NO-PORELO-NAME"))
                        .collect(java.util.stream.Collectors.joining("\n"));

        Install.verdicts("Debian", output, VERSION, out.report);

        assertThat(out.text())
                .as("later progress lines must not hide the error from the failed agent install")
                .contains("FAIL  AGENT-OK on Debian")
                .contains("Depends: porelo but it is not installable")
                .doesNotContain("STEP:");
    }

    @Test
    void saysWhichStepFailedAndWhyRatherThanOnlyThatNothingWasReported() {
        final String output =
                "FAILED: apt-get update\nFAILED:   E: Failed to fetch http://azure.archive.ubuntu.com/ubuntu/"
                        + "dists/noble/InRelease  Could not connect\nREPORTED:\n";

        Install.verdicts("Debian", output, VERSION, out.report);

        assertThat(out.text()).contains("FAILED: apt-get update").contains("Could not connect");
    }

    @Test
    void everyInstallStepThatFailsSaysSo() {
        final Install install =
                new Install(
                        Path.of("/d/porelo.deb"), Path.of("/r/porelo.rpm"), Path.of("/a/stub.deb"));

        assertThat(install.debian())
                .contains("|| failed \"apt-get update\"")
                .contains("|| failed \"installing porelo\"")
                .doesNotContain(">/dev/null 2>&1\n");
        assertThat(install.fedora()).contains("|| failed \"installing porelo\"");
    }

    @Test
    void failsABinaryThatDoesNotNameTheBuildItCameFrom() {
        // Every build once answered 0.1.0-SNAPSHOT while its package carried a build number.
        Install.verdicts(
                "Fedora",
                EVERYTHING.replace("porelo 0.1.0~snapshot.177", "porelo 0.1.0-SNAPSHOT"),
                VERSION,
                out.report);

        assertThat(out.text())
                .contains(
                        "FAIL  the package is 0.1.0~snapshot.177 but the binary says 'porelo 0.1.0-SNAPSHOT'");
    }

    @Test
    void failsABinaryThatSaysNothing() {
        Install.verdicts("Fedora", "", VERSION, out.report);

        assertThat(out.text()).contains("but the binary says 'nothing'");
        assertThat(out.report.failures()).isEqualTo(Install.MARKERS.size() + 1);
    }

    @Test
    void installsTheAgentAfterPoreloOnBothSides() {
        final Install install =
                new Install(
                        Path.of("/d/porelo.deb"), Path.of("/r/porelo.rpm"), Path.of("/a/stub.deb"));

        assertThat(install.debian())
                .containsSubsequence(
                        "/deb/porelo_*.deb", "/agent/porelo-agent-stub_*.deb", "porelo setup");
        assertThat(install.fedora())
                .containsSubsequence(
                        "/rpm/porelo-0*.rpm", "/agent/porelo-agent-stub-*.rpm", "porelo setup");
    }

    @Test
    void installsDebianFromItsOwnMirrorsAndUbuntuFromTheOnePoreloWrites() {
        // Ubuntu's mirror in Debian's sources would serve it another distribution's release.
        final Install install =
                new Install(
                        Path.of("/d/porelo.deb"), Path.of("/r/porelo.rpm"), Path.of("/a/stub.deb"));
        assertThat(install.ubuntu()).contains("azure.archive.ubuntu.com");
        assertThat(install.debian()).doesNotContain("ubuntu.com").contains("apt-get update");
    }

    @Test
    void findsTheAgentByItsDescriptionAndByNoFileOfPorelosName() {
        // A vendor's package names its program freely and registers it with one file under
        // agents.d.
        final Install install =
                new Install(
                        Path.of("/d/porelo.deb"), Path.of("/r/porelo.rpm"), Path.of("/a/stub.deb"));

        assertThat(Install.MARKERS).containsKeys("DESCRIBED", "NO-PORELO-NAME");
        for (final String script :
                java.util.List.of(install.debian(), install.ubuntu(), install.fedora())) {
            assertThat(script)
                    .contains("--verbose")
                    .contains("/usr/share/porelo/agents.d/")
                    .contains("echo DESCRIBED")
                    .contains("/usr/libexec/porelo/agents/porelo-agent-")
                    .contains("echo NO-PORELO-NAME");
        }
    }

    @Test
    void runsThePackagedSetupScriptAndAsksForTheInstalledVersion() {
        final Install install =
                new Install(
                        Path.of("/d/porelo.deb"), Path.of("/r/porelo.rpm"), Path.of("/a/stub.deb"));

        for (final String script :
                java.util.List.of(install.debian(), install.ubuntu(), install.fedora())) {
            assertThat(script)
                    .contains("/usr/share/porelo/setup/porelo-setup.sh --show")
                    .contains("as the installed porelo $(porelo --version")
                    .contains("echo SETUP-SCRIPT-RUNS");
        }
    }

    @Test
    void anInstallWhosePackagedSetupScriptDidNotRunFails() {
        Install.verdicts(
                "Ubuntu", EVERYTHING.replace("SETUP-SCRIPT-RUNS\n", ""), VERSION, out.report);

        assertThat(out.text()).contains("FAIL  SETUP-SCRIPT-RUNS on Ubuntu");
    }

    @Test
    void anInstallThatFoundTheAgentByItsOldNameFails() {
        Install.verdicts("Fedora", EVERYTHING.replace("NO-PORELO-NAME\n", ""), VERSION, out.report);

        assertThat(out.text()).contains("FAIL  NO-PORELO-NAME on Fedora");
    }
}
