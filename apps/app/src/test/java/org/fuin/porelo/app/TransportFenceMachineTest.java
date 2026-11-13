package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.fuin.porelo.wire.SystemProgram;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Opt-in check against a reachable TCP listener outside the machine, with no credentials exchanged.
 * Enable with {@code -Dporelo.test.transport.fence=true}, and name its literal address and port in
 * {@code porelo.test.transport.fence.address} and {@code porelo.test.transport.fence.port}.
 */
@EnabledIfSystemProperty(named = "porelo.test.transport.fence", matches = "true")
class TransportFenceMachineTest {

    @Test
    void aVerbCannotConnectOutsideReachesEvenThoughTheAccountCan() {
        // Harm: a rendered fence looks restrictive but a real verb still reaches another host.
        try {
            SystemProgram.find("pasta");
        } catch (final UncheckedIOException ex) {
            if (!(ex.getCause() instanceof SystemProgram.Missing)) throw ex;
            assumeTrue(false, "pasta is not installed: " + ex.getMessage());
        }
        final String target = System.getProperty("porelo.test.transport.fence.address", "");
        assertThat(target).as("supply an external listener's literal address").isNotEmpty();
        final InetAddress address = InetAddress.ofLiteral(target);
        assertThat(address.isLoopbackAddress() || address.isAnyLocalAddress())
                .as("the listener must be outside the namespace and host loopback")
                .isFalse();
        final String port = System.getProperty("porelo.test.transport.fence.port", "443");
        final String classpath =
                System.getProperty(
                        "surefire.test.class.path", System.getProperty("java.class.path"));
        final Command probe =
                Command.of(
                                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                                "-cp",
                                classpath,
                                Connect.class.getName(),
                                target,
                                port)
                        .withTimeout(Duration.ofSeconds(15));
        final ProcessCommandRunner runner = new ProcessCommandRunner(Duration.ofSeconds(20));

        final CommandResult baseline = runner.run(probe);
        assertThat(baseline.exitCode())
                .as("the account can reach the listener; stderr: %s", baseline.standardError())
                .isZero();
        assertThat(baseline.standardOutput())
                .as("the probe actually connected")
                .isEqualTo("connected\n");

        final CommandResult allowed = new TransportFence(runner, List.of(target), false).run(probe);
        assertThat(allowed.exitCode())
                .as(
                        "the same listener is reachable through pasta when declared; stderr: %s",
                        allowed.standardError())
                .isZero();
        assertThat(allowed.standardOutput())
                .as("namespace startup alone is insufficient")
                .isEqualTo("connected\n");

        final CommandResult denied = new TransportFence(runner, List.of(), false).run(probe);
        assertThat(denied.exitCode())
                .as(
                        "the same working listener is refused outside reaches; stderr: %s",
                        denied.standardError())
                .isEqualTo(23);
        assertThat(denied.standardOutput())
                .as("the verb ran and observed the refusal")
                .isEqualTo("refused\n");

        final CommandResult offline = new TransportFence(runner, List.of(target), true).run(probe);
        assertThat(offline.exitCode())
                .as(
                        "offline refuses even a declared external address; stderr: %s",
                        offline.standardError())
                .isEqualTo(23);
        assertThat(offline.standardOutput())
                .as("the offline verb ran behind its fence")
                .isEqualTo("refused\n");
    }

    /** A fresh JVM is the verb, so the connection is made in pasta's namespace. */
    public static final class Connect {

        private Connect() {}

        /** Connects without writing application data; a refusal has its own exit code. */
        public static void main(final String[] arguments) {
            try (final Socket socket = new Socket()) {
                socket.connect(
                        new InetSocketAddress(
                                InetAddress.ofLiteral(arguments[0]),
                                Integer.parseInt(arguments[1])),
                        3000);
                System.out.println("connected");
            } catch (final IOException ex) {
                System.out.println("refused");
                System.exit(23);
            }
        }
    }
}
