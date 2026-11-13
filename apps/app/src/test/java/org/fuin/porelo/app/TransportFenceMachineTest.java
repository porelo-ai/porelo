package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
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
 * {@code porelo.test.transport.fence.address} and {@code porelo.test.transport.fence.port}. The
 * name lookup case also takes its host name in {@code porelo.test.transport.fence.name}.
 */
@EnabledIfSystemProperty(named = "porelo.test.transport.fence", matches = "true")
class TransportFenceMachineTest {

    @Test
    void aVerbCanReachADeclaredHostByName() throws IOException {
        // Harm: addresses are allowed but the real verb cannot resolve its declared host behind
        // pasta.
        requirePasta();
        final String name = System.getProperty("porelo.test.transport.fence.name", "");
        assertThat(name).as("supply the external listener's host name").isNotEmpty();
        assertThat(name.contains(":") || name.matches("[0-9.]+"))
                .as("this case must exercise a host name, not a literal address")
                .isFalse();
        final List<InetAddress> addresses = List.of(InetAddress.getAllByName(name));
        assertThat(addresses)
                .as(
                        "the listener must resolve to addresses outside the namespace and host"
                                + " loopback")
                .isNotEmpty()
                .noneMatch(address -> address.isLoopbackAddress() || address.isAnyLocalAddress());
        final int port =
                Integer.parseInt(System.getProperty("porelo.test.transport.fence.port", "443"));
        final Command probe = probe(name, port);
        final ProcessCommandRunner runner = new ProcessCommandRunner(Duration.ofSeconds(20));

        final CommandResult baseline = runner.run(probe);
        assertThat(baseline.exitCode())
                .as("the account can resolve and reach the listener: %s", baseline.standardError())
                .isZero();
        assertThat(baseline.standardOutput()).as("the baseline connected").isEqualTo("connected\n");

        final CommandResult reached = new TransportFence(runner, List.of(name), false).run(probe);
        assertThat(reached.exitCode())
                .as(
                        "the fresh verb resolves inside pasta and reaches its pinned destination:"
                                + " %s",
                        reached.standardError())
                .isZero();
        assertThat(reached.standardOutput())
                .as("the fenced verb connected by name")
                .isEqualTo("connected\n");
    }

    @Test
    void aMappedHomeserverIsReachableWhileAnotherHostLoopbackListenerIsRefused()
            throws IOException {
        // Harm: mapping a homeserver either fails offline messaging or exposes other host services.
        requirePasta();
        final InetAddress loopback = InetAddress.ofLiteral("127.0.0.1");
        try (final ServerSocket homeserver = new ServerSocket(0, 8, loopback);
                final ServerSocket other = new ServerSocket(0, 8, loopback)) {
            final ProcessCommandRunner runner = new ProcessCommandRunner(Duration.ofSeconds(20));
            for (final ServerSocket listener : List.of(homeserver, other)) {
                final CommandResult baseline =
                        runner.run(probe("127.0.0.1", listener.getLocalPort()));
                assertThat(baseline.exitCode())
                        .as("both host listeners work before fencing: %s", baseline.standardError())
                        .isZero();
                assertThat(baseline.standardOutput())
                        .as("the baseline really connected")
                        .isEqualTo("connected\n");
            }
            final TransportFence fence =
                    new TransportFence(
                            runner, List.of("127.0.0.1:" + homeserver.getLocalPort()), true);

            final CommandResult reached = fence.run(probe("127.0.0.1", homeserver.getLocalPort()));
            final CommandResult refused = fence.run(probe("127.0.0.1", other.getLocalPort()));

            assertThat(reached.exitCode())
                    .as("the mapped homeserver works: %s", reached.standardError())
                    .isZero();
            assertThat(reached.standardOutput())
                    .as("the verb connected through the mapped port")
                    .isEqualTo("connected\n");
            assertThat(refused.exitCode())
                    .as("the other listener stays outside the mapping: %s", refused.standardError())
                    .isEqualTo(23);
            assertThat(refused.standardOutput())
                    .as("the fenced verb observed the refusal")
                    .isEqualTo("refused\n");
        }
    }

    @Test
    void aVerbCannotConnectOutsideReachesEvenThoughTheAccountCan() {
        // Harm: a rendered fence looks restrictive but a real verb still reaches another host.
        requirePasta();
        final String target = System.getProperty("porelo.test.transport.fence.address", "");
        assertThat(target).as("supply an external listener's literal address").isNotEmpty();
        final InetAddress address = InetAddress.ofLiteral(target);
        assertThat(address.isLoopbackAddress() || address.isAnyLocalAddress())
                .as("the listener must be outside the namespace and host loopback")
                .isFalse();
        final String port = System.getProperty("porelo.test.transport.fence.port", "443");
        final Command probe = probe(target, Integer.parseInt(port));
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

    @Test
    void aFencedVerbCannotLiftItsOwnFence() {
        // Harm: a transport flushes the namespace's ruleset and then reaches any host.
        requirePasta();
        final String target = System.getProperty("porelo.test.transport.fence.address", "");
        assertThat(target).as("supply an external listener's literal address").isNotEmpty();
        final String port = System.getProperty("porelo.test.transport.fence.port", "443");
        final Command probe = probe(target, Integer.parseInt(port));
        final List<String> lifting = new ArrayList<>();
        lifting.addAll(
                List.of(
                        SystemProgram.find("sh").toString(),
                        "-c",
                        "\"$0\" flush ruleset && echo flushed; exec \"$@\"",
                        SystemProgram.find("nft").toString()));
        lifting.addAll(probe.arguments());
        final ProcessCommandRunner runner = new ProcessCommandRunner(Duration.ofSeconds(20));

        final CommandResult result =
                new TransportFence(runner, List.of(), false)
                        .run(Command.of(lifting).withTimeout(Duration.ofSeconds(15)));

        assertThat(result.standardOutput())
                .as("the verb could not flush its fence; stderr: %s", result.standardError())
                .doesNotContain("flushed")
                .isEqualTo("refused\n");
        assertThat(result.exitCode()).as("the connection was refused").isEqualTo(23);
    }

    private static void requirePasta() {
        try {
            SystemProgram.find("pasta");
        } catch (final UncheckedIOException ex) {
            if (!(ex.getCause() instanceof SystemProgram.Missing)) throw ex;
            assumeTrue(false, "pasta is not installed: " + ex.getMessage());
        }
    }

    private static Command probe(final String address, final int port) {
        final String classpath =
                System.getProperty(
                        "surefire.test.class.path", System.getProperty("java.class.path"));
        return Command.of(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-cp",
                        classpath,
                        Connect.class.getName(),
                        address,
                        Integer.toString(port))
                .withTimeout(Duration.ofSeconds(15));
    }

    /** A fresh JVM is the verb, so the connection is made in pasta's namespace. */
    public static final class Connect {

        private Connect() {}

        /** Connects without writing application data; a refusal has its own exit code. */
        public static void main(final String[] arguments) {
            try (final Socket socket = new Socket()) {
                socket.connect(
                        new InetSocketAddress(
                                InetAddress.getByName(arguments[0]),
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
