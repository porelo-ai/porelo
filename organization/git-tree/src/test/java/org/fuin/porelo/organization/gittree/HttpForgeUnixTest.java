package org.fuin.porelo.organization.gittree;

import static org.assertj.core.api.Assertions.*;

import java.io.*;
import java.net.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(10)
class HttpForgeUnixTest {
    static final String TOKEN = "unix-fixture-admin-token";
    Path directory;
    Path socket;
    ServerSocketChannel server;
    FutureTask<String> served;
    Thread worker;

    @BeforeEach
    void start() throws Exception {
        directory = Files.createTempDirectory(Path.of("target"), "unix-");
        socket = directory.resolve("http.sock");
        server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(UnixDomainSocketAddress.of(socket));
    }

    @AfterEach
    void stop() throws Exception {
        if (server != null) {
            server.close();
        }
        if (served != null) {
            served.cancel(true);
        }
        if (worker != null) {
            worker.interrupt();
            worker.join(2000);
        }
        if (socket != null) {
            Files.deleteIfExists(socket);
        }
        if (directory != null) {
            Files.deleteIfExists(directory);
        }
    }

    void reply(String text, long stall) {
        served =
                new FutureTask<>(
                        () -> {
                            try (SocketChannel channel = server.accept()) {
                                final InputStream in = Channels.newInputStream(channel);
                                final ByteArrayOutputStream request = new ByteArrayOutputStream();
                                int matched = 0;
                                while (matched < 4 && request.size() < 65536) {
                                    final int c = in.read();
                                    if (c < 0) {
                                        throw new EOFException();
                                    }
                                    request.write(c);
                                    matched =
                                            switch (matched) {
                                                case 0, 2 -> c == '\r' ? matched + 1 : 0;
                                                default -> c == '\n' ? matched + 1 : 0;
                                            };
                                }
                                final OutputStream out = Channels.newOutputStream(channel);
                                out.write(text.getBytes(StandardCharsets.US_ASCII));
                                out.flush();
                                if (stall > 0) {
                                    Thread.sleep(stall);
                                }
                                return request.toString(StandardCharsets.US_ASCII);
                            }
                        });
        worker = Thread.ofPlatform().start(served);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "Content-Length: 2\r\n\r\n{}",
                "Transfer-Encoding: chunked\r\n\r\n1\r\n{\r\n1\r\n}\r\n0\r\n\r\n"
            })
    void bothFixedLengthAndChunkedRepliesAreReadThroughTheUnixSocket(String framing)
            throws Exception {
        reply("HTTP/1.1 200 OK\r\n" + framing, 0);
        try (var api = new HttpForgeAdmin(socket, TOKEN.toCharArray(), Duration.ofSeconds(2))) {
            assertThat(api.request("GET", "/orgs/alpha", null, 200))
                    .as("Unix framing reaches the JSON validator whole")
                    .isEqualTo(Map.of());
        }
        assertThat(served.get(2, TimeUnit.SECONDS))
                .as("admin requests use the private Unix socket and correct API route")
                .contains(
                        "GET /api/v1/orgs/alpha HTTP/1.1\r\n",
                        "Authorization: token " + TOKEN + "\r\n");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "Content-Length: 4\r\n\r\n{}",
                "Content-Length: 2\r\nContent-Length: 2\r\n\r\n{}",
                "Content-Length: 1048577\r\n\r\n",
                "Transfer-Encoding: chunked\r\nContent-Length: 2\r\n\r\n{}",
                "Transfer-Encoding: chunked\r\n\r\n3\r\n{}"
            })
    void truncatedOversizedAndAmbiguousUnixRepliesRefuseTheOperation(String framing) {
        reply("HTTP/1.1 200 OK\r\n" + framing, 0);
        try (var api = new HttpForgeAdmin(socket, TOKEN.toCharArray(), Duration.ofSeconds(2))) {
            assertThatThrownBy(() -> api.request("GET", "/test", null, 200))
                    .as("incomplete framing never becomes a successful reply")
                    .isInstanceOfSatisfying(
                            HttpForgeAdmin.Refusal.class,
                            error ->
                                    assertThat(error.exitCode())
                                            .as("a failed transport is unavailable")
                                            .isEqualTo(69))
                    .hasMessageNotContaining(TOKEN);
        }
    }

    @Test
    void aUnixRedirectIsNeverFollowedOrEchoed() throws Exception {
        reply(
                "HTTP/1.1 302 Found\r\nLocation: https://elsewhere.invalid/"
                        + TOKEN
                        + "\r\nContent-Length: 0\r\n\r\n",
                0);
        try (var api = new HttpForgeAdmin(socket, TOKEN.toCharArray(), Duration.ofSeconds(2))) {
            assertThatThrownBy(() -> api.request("GET", "/test", null, 200))
                    .as("redirect refusal cannot expose or resend a credential")
                    .hasMessageContaining("HTTP 302")
                    .hasMessageNotContaining(TOKEN);
        }
        assertThat(served.get(2, TimeUnit.SECONDS))
                .as("exactly one request reached the Unix server")
                .startsWith("GET /api/v1/test ");
    }

    @Test
    void aStalledUnixBodyIsBoundedByTheWholeRequestDeadline() {
        reply("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{", 5000);
        final long begin = System.nanoTime();
        try (var api = new HttpForgeAdmin(socket, TOKEN.toCharArray(), Duration.ofMillis(150))) {
            assertThatThrownBy(() -> api.request("GET", "/test", null, 200))
                    .as("headers do not end the response deadline")
                    .isInstanceOf(HttpForgeAdmin.Refusal.class);
        }
        assertThat(Duration.ofNanos(System.nanoTime() - begin))
                .as("a stalled private forge cannot hold the root helper forever")
                .isLessThan(Duration.ofSeconds(3));
    }
}
