package org.fuin.porelo.host;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HostPoolCliTest {
    private record Answer(int exit, String error) {}

    private Answer execute(Map<String, String> environment, String... args) {
        final var error = new StringWriter();
        final int exit =
                PoreloHost.run(
                        args,
                        environment,
                        () -> 0,
                        new PrintWriter(new StringWriter(), true),
                        new PrintWriter(error, true));
        return new Answer(exit, error.toString());
    }

    @Test
    void poolAddressIsAnInstalledVerbAndRejectsInvalidNamesBeforeLibvirt() {
        final var answer = execute(Map.of(), "pool", "address", "../bad", "300");
        assertThat(answer.exit()).isOne();
        assertThat(answer.error()).isEqualTo("FAILED: '../bad' is no VM name\n");
    }

    @Test
    void poolDeleteIsAnInstalledVerbAndRejectsInvalidNamesBeforeLibvirt() {
        final var answer = execute(Map.of(), "pool", "delete", "../bad");
        assertThat(answer.exit()).isOne();
        assertThat(answer.error()).isEqualTo("FAILED: '../bad' is no VM name\n");
    }

    @Test
    void theGiveBackRefusalHasTheScriptsPrefixAndExitCode() {
        final var answer = execute(Map.of(), "pool", "give-back");
        assertThat(answer.exit()).isEqualTo(2);
        assertThat(answer.error())
                .isEqualTo("porelo-pool-give-back runs through sudo, as the run account\n");
    }

    @Test
    void anInvalidRunAccountDoesNotGetThePersonToolsPrefix() {
        final var answer = execute(Map.of("SUDO_USER", "../other"), "pool", "give-back");
        assertThat(answer.exit()).isEqualTo(2);
        assertThat(answer.error()).isEqualTo("invalid pool run account\n");
    }

    @Test
    void aMissingListIsReportedByTheServiceAtTheCliEntry() {
        final Path list = Path.of("target", "absent-pool-list").toAbsolutePath();
        final var answer = execute(Map.of(), "pool", "revert", list.toString());
        assertThat(answer.exit()).isOne();
        assertThat(answer.error()).isEqualTo("no list of pool VMs at " + list + "\n");
    }

    @Test
    void aRelativeListDoesNotGetThePersonToolsFileErrorOrExitCode() {
        final var answer = execute(Map.of(), "pool", "revert", "relative-list");
        assertThat(answer.exit()).isOne();
        assertThat(answer.error()).doesNotContain("porelo-host-person:");
    }

    @Test
    void measurementParametersUseTheMeasurePrefix() {
        final var answer =
                execute(
                        Map.of(),
                        "forge",
                        "measure",
                        "--socket",
                        "/unused",
                        "--host",
                        "invalid host");
        assertThat(answer.exit()).isEqualTo(64);
        assertThat(answer.error())
                .isEqualTo("porelo-host forge measure: invalid gate measurement parameters\n");
    }

    @Test
    void measurementConnectionErrorsExitOne() {
        final var answer =
                execute(
                        Map.of(),
                        "forge",
                        "measure",
                        "--socket",
                        "/unused",
                        "--host",
                        "host.localhost:3000",
                        "--requests",
                        "1");
        assertThat(answer.exit()).isOne();
        assertThat(answer.error())
                .startsWith("porelo-host forge measure:")
                .doesNotContain("porelo-host-person:");
    }

    @Test
    void missingSocketActivationExitsOne() {
        final var answer = execute(Map.of(), "forge", "gate", "--web", "--upstream", "/unused");
        assertThat(answer.exit()).isOne();
        assertThat(answer.error()).startsWith("porelo-forge-gate:");
    }
}
