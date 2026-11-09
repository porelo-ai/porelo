package org.fuin.porelo.host;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.fuin.porelo.wire.TestPrograms;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ForgeGateActivationTest {
    @TempDir Path directory;

    /** Isolates Java's one-time inherited-channel lookup from the test JVM. */
    public static final class Child {
        /** Serves the socket inherited at descriptor 3, using the production adapter. */
        public static void main(String[] args) throws Exception {
            try {
                final var peer = HostSocketActivation.accepted(System.getenv(), args[0]);
                final ForgeGate gate;
                if (args.length > 1) {
                    final var root = Path.of(args[1]);
                    final var files =
                            new ProjectFiles(
                                    root, (int) java.nio.file.Files.getAttribute(root, "unix:uid"));
                    final String credential =
                            peer.getOption(jdk.net.ExtendedSocketOptions.SO_PEERCRED)
                                    .user()
                                    .getName();
                    final var runtime =
                            new ProjectRuntime(
                                    files,
                                    (argv, input, env, timeout) -> {
                                        if (!argv.equals(
                                                List.of("/usr/bin/getent", "passwd", credential)))
                                            throw new AssertionError(
                                                    "lookup must name the inherited Unix peer");
                                        return new HostPrograms.Result(
                                                credential.matches("[a-z][a-z0-9_-]{0,31}")
                                                        ? 2
                                                        : 64,
                                                "");
                                    },
                                    Path::of);
                    gate =
                            ForgeGate.installed(
                                    args[0], peer, root.resolve("absent.sock"), files, runtime);
                } else
                    gate =
                            new ForgeGate(
                                    args[0],
                                    () -> {
                                        throw new AssertionError("TCP cannot supply a login");
                                    },
                                    Set::of,
                                    () -> {
                                        throw new AssertionError(
                                                "no upstream before authentication");
                                    });
                try (var connection = new GateConnection(peer, false)) {
                    gate.serve(connection.input, connection.output);
                }
                System.exit(0);
            } catch (HostRefusal ex) {
                System.err.println(ex.getMessage());
                System.exit(ex.exitCode());
            }
        }
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    @Test
    void anInheritedUnixConnectionUsesPeerCredentialsAndReturnsForbiddenForAnUnknownPeer()
            throws Exception {
        final Path socket = Path.of("target", "activation-unix.sock");
        try (var listener =
                java.nio.channels.ServerSocketChannel.open(java.net.StandardProtocolFamily.UNIX)) {
            listener.bind(java.net.UnixDomainSocketAddress.of(socket));
            final List<String> args =
                    List.of(
                            org.fuin.porelo.wire.TestPrograms.find("python3").toString(),
                            "-B",
                            "-c",
                            "import os,socket,sys\n"
                                    + "peer=socket.socket(socket.AF_UNIX)\npeer.connect(sys.argv[1])\n"
                                    + "os.dup2(peer.fileno(),3)\nos.set_inheritable(3,True)\n"
                                    + "os.environ['LISTEN_FDS']='1'\nos.environ['LISTEN_PID']=str(os.getpid())\n"
                                    + "os.execv(sys.argv[2],sys.argv[2:])\n",
                            socket.toAbsolutePath().toString(),
                            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                            "-XX:-UsePerfData",
                            "-Djava.io.tmpdir=" + directory.toAbsolutePath(),
                            "--enable-native-access=ALL-UNNAMED",
                            "-cp",
                            System.getProperty("java.class.path"),
                            Child.class.getName(),
                            "web",
                            directory.toAbsolutePath().toString());
            final var builder = new ProcessBuilder(args);
            builder.environment().clear();
            builder.environment().putAll(Map.of("PATH", "/usr/bin:/bin", "LANG", "C.UTF-8"));
            final var child = builder.start();
            final var answer =
                    new java.util.concurrent.FutureTask<String>(
                            () -> {
                                try (var connection = new GateConnection(listener.accept())) {
                                    GateHttp.write(
                                            connection.output,
                                            "GET / HTTP/1.1\r\nHost: host.localhost:3000\r\n\r\n");
                                    return new String(
                                            connection.input.readAllBytes(),
                                            StandardCharsets.US_ASCII);
                                }
                            });
            Thread.ofPlatform().daemon().start(answer);
            try {
                assertThat(answer.get(15, TimeUnit.SECONDS))
                        .startsWith("HTTP/1.1 403 Forbidden\r\n");
                assertThat(child.waitFor(5, TimeUnit.SECONDS)).isTrue();
                assertThat(child.exitValue())
                        .as(
                                new String(
                                        child.getErrorStream().readAllBytes(),
                                        StandardCharsets.UTF_8))
                        .isZero();
            } finally {
                child.destroyForcibly();
                child.waitFor(5, TimeUnit.SECONDS);
            }
        } finally {
            java.nio.file.Files.deleteIfExists(socket);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"git", "web", "runner"})
    void descriptorThreeIsTheSameAcceptedConnectionTheGateChecksAndServes(String mode)
            throws Exception {
        try (var listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            listener.setSoTimeout(10000);
            final List<String> java =
                    List.of(
                            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                            "-XX:-UsePerfData",
                            "-Djava.io.tmpdir=" + directory.toAbsolutePath(),
                            "--enable-native-access=ALL-UNNAMED",
                            "-cp",
                            System.getProperty("java.class.path"),
                            Child.class.getName(),
                            mode);
            // Only the fixture's shell opens fd 3 before exec; production never uses a shell.
            final String script =
                    "exec 3<>/dev/tcp/127.0.0.1/"
                            + listener.getLocalPort()
                            + "\nLISTEN_FDS=1 LISTEN_PID=$$ exec "
                            + String.join(
                                    " ",
                                    java.stream().map(ForgeGateActivationTest::quote).toList());
            final var builder =
                    new ProcessBuilder(TestPrograms.find("bash").toString(), "-c", script);
            builder.environment().clear();
            builder.environment().putAll(Map.of("PATH", "/usr/bin:/bin", "LANG", "C.UTF-8"));
            final var child = builder.start();
            try {
                final var answer = new ByteArrayOutputStream();
                try (var socket = listener.accept()) {
                    socket.setSoTimeout(10000);
                    try {
                        socket.getOutputStream()
                                .write(
                                        "GET /owner/repo.git/info/refs HTTP/1.1\r\nHost: x\r\n\r\n"
                                                .getBytes(StandardCharsets.US_ASCII));
                        socket.getInputStream().transferTo(answer);
                    } catch (IOException ex) {
                        if (mode.equals("git")) throw ex;
                    }
                }
                assertThat(child.waitFor(10, TimeUnit.SECONDS))
                        .as("the one-connection service must exit")
                        .isTrue();
                final String diagnostic =
                        new String(child.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                if (mode.equals("git")) {
                    assertThat(child.exitValue()).as(diagnostic).isZero();
                    assertThat(answer.toString(StandardCharsets.US_ASCII))
                            .as("the adapter serves fd 3 rather than opening another socket")
                            .startsWith("HTTP/1.1 401")
                            .contains("WWW-Authenticate: Basic");
                } else {
                    assertThat(child.exitValue())
                            .as("a peer-checked gate cannot serve TCP")
                            .isOne();
                    assertThat(answer.size())
                            .as("the refused family receives no HTTP identity")
                            .isZero();
                    assertThat(diagnostic)
                            .as("family refusal remains visible on stderr")
                            .contains(mode + " mode serves a Unix socket alone");
                }
            } finally {
                child.destroyForcibly();
                child.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }
}
