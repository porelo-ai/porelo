package org.fuin.porelo.host;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HostServiceCommandsTest {
    private final StringWriter errors = new StringWriter();

    private int run(long uid, Map<String, String> environment, String... arguments) {
        return PoreloHost.run(
                arguments,
                environment,
                () -> uid,
                new PrintWriter(new StringWriter()),
                new PrintWriter(errors, true));
    }

    @Test
    void theUnprivilegedGateRequiresItsOwnActivationAndNoSudoIdentity() {
        assertThat(run(1000, Map.of(), "forge", "gate", "--git", "--upstream", "/fixture.sock"))
                .as("the forge account reaches only the activation check")
                .isOne();
        assertThat(errors.toString())
                .as("activation failure retains the gate's safe diagnostic")
                .startsWith("porelo-host:");
        assertThat(
                        run(
                                1000,
                                Map.of("SUDO_USER", "person"),
                                "forge",
                                "gate",
                                "--web",
                                "--upstream",
                                "/fixture.sock"))
                .as("sudo is not an activation authority")
                .isEqualTo(65);
    }

    @Test
    void thePoolRootBoundaryIsNotOpenedByTheGateException() {
        assertThat(run(1000, Map.of(), "pool", "revert", "--one", "pool-1"))
                .as("ordinary users cannot run a privileged pool mutation")
                .isEqualTo(77);
        assertThat(run(0, Map.of("SUDO_USER", "person"), "pool", "revert", "--one", "pool-1"))
                .as("a person's sudo rule does not authorize the pool service")
                .isEqualTo(65);
    }

    @Test
    void tokenVerbsKeepTheirRootBoundaryBesideTheUnprivilegedGate() {
        for (String verb : List.of("issue-token", "lend-token")) {
            errors.getBuffer().setLength(0);
            assertThat(run(1000, Map.of(), "forge", verb)).isEqualTo(69);
            assertThat(errors.toString()).isEqualTo("porelo-host: runs only as root\n");
        }
    }

    @Test
    void gateModesAreRequiredAndExclusiveBeforeAnyDescriptorIsUsed() {
        assertThat(run(1000, Map.of(), "forge", "gate", "--upstream", "/fixture.sock"))
                .as("no mode is guessed")
                .isEqualTo(64);
        assertThat(
                        run(
                                1000,
                                Map.of(),
                                "forge",
                                "gate",
                                "--web",
                                "--git",
                                "--upstream",
                                "/fixture.sock"))
                .as("two trust modes cannot be combined")
                .isEqualTo(64);
    }

    @Test
    void theForgeAccountStillNeedsTheHostsOwnNamespace() {
        final int code =
                PoreloHost.run(
                        new String[] {"forge", "gate", "--git", "--upstream", "/fixture.sock"},
                        Map.of(),
                        () -> {
                            throw new HostRefusal(
                                    77, "porelo-host: runs only in the host's own user namespace");
                        },
                        new PrintWriter(new StringWriter()),
                        new PrintWriter(errors, true));
        assertThat(code)
                .as("the gate exception relaxes the uid requirement, never the namespace check")
                .isEqualTo(77);
    }

    @Test
    void aMeasurementRefusesInvalidLoadBeforeConnecting() {
        assertThat(
                        run(
                                1000,
                                Map.of(),
                                "forge",
                                "measure",
                                "--socket",
                                "/fixture.sock",
                                "--host",
                                "host.localhost:3000",
                                "--requests",
                                "0"))
                .as("an invalid measurement reaches no socket")
                .isEqualTo(64);
    }

    @Test
    void malformedTargetsAndHeadersCannotInjectUpstreamBytes() throws Exception {
        for (String raw :
                List.of(
                        "GET /?x=\rhidden HTTP/1.1\r\n\r\n",
                        "GET / HTTP/1.1\r\nHost: host.localhost:3000\r\nX-Custom: value\rhidden\r\n\r\n",
                        "GET / HTTP/1.1\r\nHost: host.localhost:3000\r\n folded: header\r\n\r\n")) {
            final var answer = new ByteArrayOutputStream();
            ForgeGatePolicyTest.gate("web")
                    .serve(
                            new ByteArrayInputStream(raw.getBytes(StandardCharsets.ISO_8859_1)),
                            answer);
            assertThat(answer.toString(StandardCharsets.US_ASCII))
                    .as("parser refusals never open an upstream or reflect the input")
                    .startsWith("HTTP/1.1 400")
                    .doesNotContain("hidden", "folded");
        }
    }
}
