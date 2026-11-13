package org.fuin.porelo.core.process;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Holds that a peer of another account is not taken for this one, which a test on one account
 * cannot show: it takes a second uid, so it runs only where one is at hand, on a test machine.
 *
 * <p>Tagged {@code second-account}, which no run takes unless asked for with {@code
 * -Dgroups=second-account -Dporelo.tests.excluded=}, the root pom excluding the tag otherwise.
 * {@code PORELO_TEST_AS_OTHER} names the command prefix that runs a command as the other account on
 * this machine, such as {@code ssh -o BatchMode=yes other@127.0.0.1}; without it the test is
 * skipped.
 */
@Tag("second-account")
class SocketPeerOtherAccountTest {

    /** The variable naming how a command runs as the other account. */
    static final String AS_OTHER = "PORELO_TEST_AS_OTHER";

    @Test
    void aPeerOfAnotherAccountIsThatAccountAndNotThisOne(@TempDir Path dir) throws Exception {
        final String prefix = System.getenv(AS_OTHER);
        Assumptions.assumeTrue(
                prefix != null && !prefix.isBlank(),
                AS_OTHER + " names no way to run a command as another account here");
        final List<String> asOther = List.of(prefix.strip().split("\\s+"));
        final long other = Long.parseLong(run(asOther, List.of("id", "-u")).strip());
        assertThat(other)
                .as("%s runs as another account than this one", AS_OTHER)
                .isNotEqualTo(SocketPeer.ownUid());

        // The other account must reach the socket: its directory let through, the socket written
        // to.
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx--x--x"));
        final Path socket = dir.resolve("peer.sock");
        try (ServerSocketChannel listener = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            listener.bind(UnixDomainSocketAddress.of(socket));
            Files.setPosixFilePermissions(socket, PosixFilePermissions.fromString("rw-rw-rw-"));
            // The script on standard input: an ssh prefix hands its arguments to a shell, which
            // would read the script's own syntax.
            final Process caller = start(asOther, List.of("python3", "-", socket.toString()));
            try (var script = caller.getOutputStream()) {
                script.write(
                        ("import socket, sys, time\n"
                                        + "s = socket.socket(socket.AF_UNIX)\n"
                                        + "s.connect(sys.argv[1])\n"
                                        + "time.sleep(30)\n")
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            try (SocketChannel accepted = listener.accept()) {
                final var peer = SocketPeer.read(accepted);

                // The uid of anybody else is not passed on: -1, never this account's.
                assertThat(peer.uid())
                        .as("another account's uid is not given as this one's")
                        .isEqualTo(-1L)
                        .isNotEqualTo(SocketPeer.ownUid());
                assertThat(peer.sameUser())
                        .as("a peer of another account is not this account")
                        .isFalse();
            } finally {
                caller.destroyForcibly();
            }
        }
    }

    private static Process start(final List<String> prefix, final List<String> command)
            throws Exception {
        final List<String> all = new ArrayList<>(prefix);
        all.addAll(command);
        return new ProcessBuilder(all).redirectErrorStream(true).start();
    }

    private static String run(final List<String> prefix, final List<String> command)
            throws Exception {
        final Process process = start(prefix, command);
        final String out = new String(process.getInputStream().readAllBytes());
        assertThat(
                        process.waitFor(
                                Duration.ofSeconds(30).toMillis(),
                                java.util.concurrent.TimeUnit.MILLISECONDS))
                .as("%s ends", command)
                .isTrue();
        assertThat(process.exitValue()).as("%s: %s", command, out).isZero();
        return out;
    }
}
