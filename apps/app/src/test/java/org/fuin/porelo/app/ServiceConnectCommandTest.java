package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.SequenceInputStream;
import java.io.StringWriter;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * Tests for {@link ServiceConnectCommand}, against real Unix sockets in a directory standing in for
 * the host's {@code /run}.
 *
 * <p>The harm guarded: bytes lost or sent to another service than the role named, and a socket a
 * stranger could have placed connected to with the person's own peer credentials, which the gates
 * behind the real sockets take as the person's login.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class ServiceConnectCommandTest {

    @TempDir Path dir;

    private final StringWriter err = new StringWriter();

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    private final Map<String, Path> sockets = new LinkedHashMap<>();

    /** Paths whose owner the fixture reports as another account than root. */
    private final Map<Path, Integer> owners = new HashMap<>();

    @BeforeEach
    void layOutTheHostsSockets() throws IOException {
        Files.createDirectories(dir.resolve("run/porelo-forge"));
        Files.createDirectories(dir.resolve("run/porelo-matrix"));
        // As systemd makes them (DirectoryMode=0755), whatever the umask the build runs under.
        for (final String each : List.of("", "run", "run/porelo-forge", "run/porelo-matrix")) {
            Files.setPosixFilePermissions(
                    dir.resolve(each), PosixFilePermissions.fromString("rwxr-xr-x"));
        }
        sockets.put("forge-web", dir.resolve("run/porelo-forge/web.sock"));
        sockets.put("forge-git", dir.resolve("run/porelo-forge/git.sock"));
        sockets.put("homeserver", dir.resolve("run/porelo-matrix/client.sock"));
    }

    /**
     * The test account's own files read as root's, as the host's are, keeping their real type and
     * mode; everything above the test's directory reads as a root directory no one else may write.
     */
    private Map<String, Object> attributes(final Path path) throws IOException {
        if (!path.startsWith(dir)) {
            return Map.of("uid", 0, "mode", 040755);
        }
        final Map<String, Object> real =
                new HashMap<>(
                        Files.readAttributes(path, "unix:uid,mode", LinkOption.NOFOLLOW_LINKS));
        real.put("uid", owners.getOrDefault(path, 0));
        return real;
    }

    private int connect(final String role, final String input) {
        return connect(role, new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), out);
    }

    private int connect(final String role, final InputStream input, final OutputStream output) {
        final ServiceConnectCommand command =
                new ServiceConnectCommand(sockets, this::attributes, () -> 0, input, output);
        final CommandLine line = new CommandLine(command);
        line.setOut(new PrintWriter(new StringWriter()));
        line.setErr(new PrintWriter(err));
        return line.execute(role);
    }

    @Test
    void carriesALineToTheForgesWebAndItsAnswerBack() throws Exception {
        try (Server web = Server.echo(sockets.get("forge-web"))) {

            final int code = connect("forge-web", "GET / HTTP/1.0\r\n\r\n");

            assertThat(code).as("a carried connection ends well; err: %s", err).isZero();
            assertThat(out.toString(StandardCharsets.UTF_8))
                    .as("every byte sent comes back, after the input's end reached the service")
                    .isEqualTo("GET / HTTP/1.0\r\n\r\n");
            assertThat(web.accepted()).as("one connection per invocation").isEqualTo(1);
        }
    }

    @Test
    void reachesTheSocketOfTheRoleNamedAndNoOther() throws Exception {
        try (Server web = Server.answering(sockets.get("forge-web"), "web\n");
                Server git = Server.answering(sockets.get("forge-git"), "git\n");
                Server matrix = Server.answering(sockets.get("homeserver"), "matrix\n")) {

            assertThat(connect("forge-git", "")).as("err: %s", err).isZero();
            assertThat(out.toString(StandardCharsets.UTF_8))
                    .as("forge-git is the git socket")
                    .isEqualTo("git\n");
            out.reset();
            assertThat(connect("homeserver", "")).as("err: %s", err).isZero();
            assertThat(out.toString(StandardCharsets.UTF_8))
                    .as("homeserver is the homeserver's socket")
                    .isEqualTo("matrix\n");
            assertThat(web.accepted()).as("the web socket was never reached").isZero();
        }
    }

    @Test
    void refusesAnUnknownRoleWithItsOwnWords() {
        final StringWriter said = new StringWriter();
        final StringWriter answered = new StringWriter();
        final CommandLine cli = PoreloCli.commandLine(NoAccount.context());
        cli.setOut(new PrintWriter(answered));
        cli.setErr(new PrintWriter(said));

        assertThat(cli.execute("service", "connect", "mail"))
                .as("an unknown role is a usage error")
                .isEqualTo(64);
        assertThat(said.toString())
                .as("the refusal names the role and the roles there are")
                .contains("'mail'")
                .contains("forge-web")
                .contains("forge-git")
                .contains("homeserver");
        assertThat(answered.toString()).as("nothing but a service's bytes on stdout").isEmpty();
    }

    @Test
    void saysTheHostServesNoneWhenTheSocketIsMissing() {
        assertThat(connect("homeserver", "hello\n"))
                .as("a role this host does not serve is unavailable")
                .isEqualTo(69);
        assertThat(err.toString())
                .as("names the role and that no socket is there")
                .contains("homeserver")
                .contains("no socket");
        assertThat(out.size()).as("nothing on stdout").isZero();
    }

    @Test
    void saysTheSocketRefusesWhenNobodyAcceptsOnIt() throws Exception {
        final Path web = sockets.get("forge-web");
        try (ServerSocketChannel gone = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            gone.bind(UnixDomainSocketAddress.of(web));
        }
        assertThat(web).as("a closed listener leaves its file").exists();

        assertThat(connect("forge-web", "hello\n"))
                .as("a socket that refuses is unavailable")
                .isEqualTo(69);
        assertThat(err.toString())
                .as("names the role and that its socket refuses")
                .contains("forge-web")
                .contains("refuses");
        assertThat(out.size()).as("nothing on stdout").isZero();
    }

    @Test
    void refusesASocketInADirectoryOthersCanWrite() throws Exception {
        final Path directory = dir.resolve("run/porelo-forge");
        try (Server web = Server.echo(sockets.get("forge-web"))) {
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwxrwxrwx"));

            assertThat(connect("forge-web", "hello\n"))
                    .as("a socket anyone could have placed is refused")
                    .isEqualTo(69);
            assertThat(err.toString()).as("says why").contains(directory.toString());
            assertThat(web.accepted())
                    .as("the person's credentials never reach that socket")
                    .isZero();
        }
    }

    @Test
    void refusesASocketInADirectoryAnotherAccountOwns() throws Exception {
        final Path directory = dir.resolve("run/porelo-forge");
        owners.put(directory, 4321);
        try (Server web = Server.echo(sockets.get("forge-web"))) {

            assertThat(connect("forge-web", "hello\n"))
                    .as("a directory not root's could hold anybody's socket")
                    .isEqualTo(69);
            assertThat(err.toString()).as("says why").contains(directory.toString());
            assertThat(web.accepted())
                    .as("the person's credentials never reach that socket")
                    .isZero();
        }
    }

    @Test
    void refusesASocketOthersCanWrite() throws Exception {
        final Path socket = sockets.get("forge-web");
        try (Server web = Server.echo(socket)) {
            Files.setPosixFilePermissions(socket, PosixFilePermissions.fromString("rwxrwxrwx"));

            assertThat(connect("forge-web", "hello\n")).isEqualTo(69);
            assertThat(err.toString()).as("says why").contains(socket.toString());
            assertThat(web.accepted()).isZero();
        }
    }

    @Test
    void refusesAFileThatIsNoSocket() throws Exception {
        final Path socket = sockets.get("forge-web");
        Files.writeString(socket, "not a socket");

        assertThat(connect("forge-web", "hello\n")).isEqualTo(69);
        assertThat(err.toString()).as("says why").contains(socket.toString());
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void failsWhenStandardInputBreaksOffMidRequest() throws Exception {
        final InputStream breaking =
                new SequenceInputStream(
                        new ByteArrayInputStream(
                                "POST / HTTP/1.0\r\n".getBytes(StandardCharsets.UTF_8)),
                        new InputStream() {
                            @Override
                            public int read() throws IOException {
                                throw new IOException("the client's pipe broke");
                            }
                        });
        try (Server web = Server.echo(sockets.get("forge-web"))) {

            assertThat(connect("forge-web", breaking, out))
                    .as("a request cut short is a failure, not an end; err: %s", err)
                    .isEqualTo(70);
            assertThat(err.toString())
                    .as("names what failed")
                    .contains("forge-web")
                    .contains("standard input")
                    .contains("the client's pipe broke");
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void failsWhenStandardOutputBreaksWhileTheServiceKeepsSending() throws Exception {
        // As System.out is: a PrintStream, which keeps its failures to itself.
        final PrintStream broken =
                new PrintStream(
                        new OutputStream() {
                            @Override
                            public void write(final int b) throws IOException {
                                throw new IOException("stdout is closed");
                            }
                        });
        try (Server web = Server.flooding(sockets.get("forge-web"))) {

            assertThat(connect("forge-web", new ByteArrayInputStream(new byte[0]), broken))
                    .as("bytes lost on the way out are a failure; err: %s", err)
                    .isEqualTo(70);
            assertThat(err.toString())
                    .as("names what failed")
                    .contains("forge-web")
                    .contains("standard output");
        }
    }

    /** A service on a Unix socket, for one test. */
    private static final class Server implements AutoCloseable {

        private final ServerSocketChannel channel;

        private final AtomicInteger accepted = new AtomicInteger();

        /** What the service does with one connection. */
        @FunctionalInterface
        private interface Serving {
            void serve(SocketChannel client) throws IOException;
        }

        private Server(final Path socket, final Serving serving) throws IOException {
            channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
            channel.bind(UnixDomainSocketAddress.of(socket));
            Thread.ofVirtual()
                    .start(
                            () -> {
                                while (channel.isOpen()) {
                                    try {
                                        final SocketChannel client = channel.accept();
                                        accepted.incrementAndGet();
                                        Thread.ofVirtual().start(() -> serve(client, serving));
                                    } catch (IOException ex) {
                                        return;
                                    }
                                }
                            });
        }

        /** Echoes what arrives, after the input's end, then closes. */
        static Server echo(final Path socket) throws IOException {
            return new Server(
                    socket,
                    client -> {
                        final ByteArrayOutputStream held = new ByteArrayOutputStream();
                        final ByteBuffer buffer = ByteBuffer.allocate(1024);
                        while (client.read(buffer) >= 0) {
                            held.write(buffer.array(), 0, buffer.position());
                            buffer.clear();
                        }
                        writeAll(client, held.toByteArray());
                    });
        }

        /** Answers its own words at once and closes. */
        static Server answering(final Path socket, final String answer) throws IOException {
            return new Server(
                    socket, client -> writeAll(client, answer.getBytes(StandardCharsets.UTF_8)));
        }

        /** Sends without end, until the connection breaks. */
        static Server flooding(final Path socket) throws IOException {
            final byte[] page = new byte[64 * 1024];
            return new Server(
                    socket,
                    client -> {
                        while (true) {
                            writeAll(client, page);
                        }
                    });
        }

        /** One write may take part of the bytes only. */
        private static void writeAll(final SocketChannel client, final byte[] bytes)
                throws IOException {
            final ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                client.write(buffer);
            }
        }

        private static void serve(final SocketChannel client, final Serving serving) {
            try (client) {
                serving.serve(client);
            } catch (IOException ex) {
                // The test sees what did or did not arrive.
            }
        }

        int accepted() {
            return accepted.get();
        }

        @Override
        public void close() throws IOException {
            channel.close();
        }
    }
}
