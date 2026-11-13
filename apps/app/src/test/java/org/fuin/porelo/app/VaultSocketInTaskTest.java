package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * The relay binds the vault socket inside a real rootless container, as a task's start does: the
 * agent's account reaches the broker through it, and can neither remove nor replace it.
 *
 * <p>Opt-in, since a unit test runs without a container runtime: enable with {@code
 * -Dporelo.test.vault.socket=true}. Needs podman and a local {@code ubuntu:24.04} image, and pulls
 * nothing.
 */
@EnabledIfSystemProperty(named = "porelo.test.vault.socket", matches = "true")
class VaultSocketInTaskTest {

    private static final String IMAGE = "docker.io/library/ubuntu:24.04";

    private static final String INSIDE = "/run/porelo-vault/vault.sock";

    /** The agent's account in a task image, which this image does not have by name. */
    private static final String AGENT = "1001:1001";

    private static final String ANSWER = "the broker answers";

    @Test
    void theAgentReachesTheBrokerThroughTheSocketInItsTaskAndCannotReplaceIt(@TempDir Path dir)
            throws Exception {
        assumeTrue(podmanWorks(), "podman with a local " + IMAGE + " is needed");

        final Path upstream = dir.resolve("vault.sock");
        try (ServerSocketChannel broker = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            broker.bind(UnixDomainSocketAddress.of(upstream));
            final Thread answering = Thread.ofVirtual().start(() -> answer(broker));
            final String container =
                    run("podman", "run", "-d", "--rm", "--network", "none", IMAGE, "sleep", "300")
                            .strip();
            Process relay = null;
            try {
                final String pid = run("podman", "inspect", "-f", "{{.State.Pid}}", container);
                final Path pidFile = dir.resolve("relay.pid");
                relay =
                        new ProcessBuilder(
                                        "podman",
                                        "unshare",
                                        ProcessHandle.current().info().command().orElse("java"),
                                        "-D"
                                                + org.fuin.porelo.core.config.RealAccount.REFUSED
                                                + "=true",
                                        "-cp",
                                        System.getProperty("java.class.path"),
                                        RelayMain.class.getName(),
                                        "--bind-in",
                                        pid.strip(),
                                        "--at",
                                        INSIDE,
                                        "--socket",
                                        upstream.toString(),
                                        "--pid-file",
                                        pidFile.toString())
                                .redirectErrorStream(true)
                                .redirectOutput(dir.resolve("relay.log").toFile())
                                .start();
                assertThat(
                                org.fuin.porelo.wire.HelperPid.await(
                                        pidFile,
                                        org.fuin.porelo.wire.HelperPid.deadline(
                                                Duration.ofSeconds(30))))
                        .as("the relay came up: %s", log(dir))
                        .isPresent();

                assertThat(asAgent(container, reach()))
                        .as("the agent reaches the broker through the socket in its task")
                        .contains(ANSWER);
                assertThat(
                                asAgent(
                                        container,
                                        "rm -f "
                                                + INSIDE
                                                + " || echo refused-rm; ln -sf /tmp/x "
                                                + INSIDE
                                                + " || echo refused-ln; touch "
                                                + INSIDE
                                                + ".new || echo refused-new"))
                        .as("the agent can neither remove, replace nor add beside it")
                        .contains("refused-rm", "refused-ln", "refused-new");
                assertThat(asAgent(container, reach()))
                        .as("and it still answers afterwards")
                        .contains(ANSWER);
                assertThat(asAgent(container, "test -S " + INSIDE + " && echo in-the-task"))
                        .as("the socket lies in the task's own filesystem")
                        .contains("in-the-task");
                assertThat(Path.of(INSIDE).getParent())
                        .as("and nothing was made at that path on the host")
                        .doesNotExist();
            } finally {
                if (relay != null) {
                    final List<ProcessHandle> all =
                            new ArrayList<>(relay.toHandle().descendants().toList());
                    all.add(relay.toHandle());
                    all.forEach(ProcessHandle::destroy);
                    relay.waitFor(10, TimeUnit.SECONDS);
                }
                run("podman", "rm", "-f", "-t", "0", container);
                answering.interrupt();
            }
        }
    }

    private static String reach() {
        return "perl -MIO::Socket::UNIX -e 'my $s = IO::Socket::UNIX->new(Peer => \""
                + INSIDE
                + "\") or die \"refused: $!\\n\"; print scalar <$s>'";
    }

    private static void answer(final ServerSocketChannel broker) {
        while (broker.isOpen()) {
            try (SocketChannel client = broker.accept()) {
                client.write(ByteBuffer.wrap((ANSWER + "\n").getBytes(StandardCharsets.UTF_8)));
            } catch (IOException ex) {
                return;
            }
        }
    }

    private static String asAgent(final String container, final String script) throws Exception {
        return run("podman", "exec", "--user", AGENT, container, "sh", "-c", script);
    }

    private static String log(final Path dir) {
        try {
            return Files.readString(dir.resolve("relay.log"));
        } catch (IOException ex) {
            return "(no log: " + ex.getMessage() + ")";
        }
    }

    private static boolean podmanWorks() {
        try {
            return new ProcessBuilder("podman", "image", "exists", IMAGE)
                            .redirectErrorStream(true)
                            .start()
                            .waitFor()
                    == 0;
        } catch (IOException | InterruptedException ex) {
            return false;
        }
    }

    private static String run(final String... command) throws Exception {
        final Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        final String output =
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError(String.join(" ", command) + " did not end");
        }
        return output;
    }
}
