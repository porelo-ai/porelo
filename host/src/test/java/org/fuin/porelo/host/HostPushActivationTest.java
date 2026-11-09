package org.fuin.porelo.host;

import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Guards the socket-unit boundary and exercises the numeric peer downcall. */
class HostPushActivationTest {
    @TempDir Path directory;

    /** Isolates the JDK's one-time inherited-channel lookup from the test JVM. */
    public static final class Child {
        /** Reads the actual peer of the socket on stdin, without LISTEN_FDS. */
        public static void main(String[] args) throws Exception {
            try (var socket = HostSocketActivation.standardInput()) {
                System.out.println(HostSocketActivation.peerUid());
            }
        }
    }

    @Test
    void anInvalidDescriptorCannotProduceAnAuthenticatedPeer() {
        assertThatThrownBy(() -> HostSocketActivation.peerUid(-1))
                .as("failure to read kernel credentials cannot authenticate a hook")
                .isInstanceOf(IOException.class)
                .hasMessage("Cannot read the accepted socket's peer uid");
    }

    @Test
    void standardInputSocketActivationReadsTheKernelsNumericUidWithoutListenVariables()
            throws Exception {
        final Path path = Path.of("target", "pp.sock");
        Files.deleteIfExists(path);
        try (var listener = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            listener.bind(UnixDomainSocketAddress.of(path));
            final var builder =
                    new ProcessBuilder(
                            List.of(
                                    org.fuin.porelo.wire.TestPrograms.find("python3").toString(),
                                    "-B",
                                    "-c",
                                    "import os,socket,sys\npeer=socket.socket(socket.AF_UNIX)\npeer.connect(sys.argv[1])\n"
                                            + "os.dup2(peer.fileno(),0)\nos.set_inheritable(0,True)\nos.execv(sys.argv[2],sys.argv[2:])\n",
                                    path.toString(),
                                    Path.of(System.getProperty("java.home"), "bin", "java")
                                            .toString(),
                                    "-XX:-UsePerfData",
                                    "-Djava.io.tmpdir=" + directory.toAbsolutePath(),
                                    "--enable-native-access=ALL-UNNAMED",
                                    "-cp",
                                    System.getProperty("java.class.path"),
                                    Child.class.getName()));
            builder.environment().clear();
            builder.environment().putAll(Map.of("PATH", "/usr/bin:/bin", "LANG", "C.UTF-8"));
            final var child = builder.start();
            try (var socket = listener.accept()) {
                assertThat(child.waitFor(10, TimeUnit.SECONDS))
                        .as("peer lookup must finish without request bytes")
                        .isTrue();
                assertThat(child.exitValue())
                        .as(
                                new String(
                                        child.getErrorStream().readAllBytes(),
                                        StandardCharsets.UTF_8))
                        .isZero();
                assertThat(
                                new String(
                                                child.getInputStream().readAllBytes(),
                                                StandardCharsets.US_ASCII)
                                        .strip())
                        .as(
                                "the uid is the connecting process's, never a supplied header or NSS name")
                        .isEqualTo(Files.getAttribute(directory, "unix:uid").toString());
            } finally {
                child.destroyForcibly();
                child.waitFor(5, TimeUnit.SECONDS);
            }
        } finally {
            Files.deleteIfExists(path);
        }
    }

    @Test
    void sudoCannotEnterTheSocketServiceEvenAsRoot() {
        final var out = new StringWriter();
        final var err = new StringWriter();
        final int code =
                PoreloHost.run(
                        new String[] {"judge-push"},
                        Map.of("SUDO_USER", "alice"),
                        () -> 0,
                        new PrintWriter(out),
                        new PrintWriter(err));
        assertThat(code).as("the predecessor's sudo guard returns data error").isEqualTo(65);
        assertThat(out.toString())
                .as("a refused invocation cannot write a hook response")
                .isEmpty();
        assertThat(err.toString())
                .as("the guard retains its diagnostic")
                .isEqualTo(
                        "porelo-host-person: verify-push is the forge hook's socket unit's alone\n");
    }

    @Test
    void anOrdinaryUidCannotEnterTheSocketService() {
        final var out = new StringWriter();
        final var err = new StringWriter();
        final int code =
                PoreloHost.run(
                        new String[] {"judge-push"},
                        Map.of(),
                        () -> 1000,
                        new PrintWriter(out),
                        new PrintWriter(err));
        assertThat(code).as("the predecessor's root guard returns unavailable").isEqualTo(69);
        assertThat(out.toString()).as("no hook answer can precede the root boundary").isEmpty();
        assertThat(err.toString())
                .as("the root refusal retains the predecessor's diagnostic")
                .isEqualTo("porelo-host-person: runs only as root\n");
    }

    @Test
    void anOrdinaryPipeKeepsThePredecessorsFileErrorExitCode() throws Exception {
        final var answer = new HostReadFixture(directory).run(Map.of(), "judge-push");
        assertThat(answer.code())
                .as("a non-socket stdin is the predecessor's OSError, not a hook answer")
                .isEqualTo(73);
        assertThat(answer.out()).as("a plain pipe cannot impersonate a socket unit").isEmpty();
        assertThat(answer.err())
                .as("a failed activation remains a journal diagnostic")
                .isEqualTo("porelo-host-person: cannot read or change the host's files\n");
    }
}
