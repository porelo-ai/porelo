package org.fuin.porelo.supervisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.URI;
import java.net.UnixDomainSocketAddress;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for the gateway reaching a {@code unix:} destination: a real Unix socket server stands in
 * for the MCP endpoint, and the agent's side is a real connection to the gateway's socket.
 */
class VaultProxyUnixTest {

    private static final String PHANTOM = "porelo_mcp_phantom_token";

    private static final String KEY = "mcp-real-key-0123456789abcdef0123456789";

    private static final String JSON_RPC =
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}";

    /** What the endpoint received: the head and body as bytes, read as text. */
    private final AtomicReference<String> received = new AtomicReference<>("");

    /** Writes an answer to the connection the endpoint accepted. */
    @FunctionalInterface
    private interface Answer {
        void write(OutputStream out) throws Exception;
    }

    @Test
    void relaysAJsonAnswerWithTheKeyAsABearerHeader(@TempDir Path dir) throws Exception {
        final String answer = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":[]}}";
        final String relayed;
        try (Endpoint endpoint =
                        endpoint(
                                dir,
                                out ->
                                        out.write(
                                                ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                                                                + "Mcp-Session-Id: s-1\r\nContent-Length: "
                                                                + answer.length()
                                                                + "\r\n\r\n"
                                                                + answer)
                                                        .getBytes(StandardCharsets.UTF_8)));
                VaultProxy proxy = proxy(dir, endpoint.socket)) {
            relayed = send(proxy.socket(), request());
        }

        assertThat(relayed).startsWith("HTTP/1.1 200").contains(answer).contains("s-1");
        assertThat(relayed).doesNotContain(KEY);
        assertThat(received.get())
                .startsWith("POST /mcp HTTP/1.1\r\n")
                .containsIgnoringCase("authorization: Bearer " + KEY + "\r\n")
                .endsWith("\r\n\r\n" + JSON_RPC)
                .doesNotContain(PHANTOM);
    }

    @Test
    void passesAStreamOnAsItsEventsArrive(@TempDir Path dir) throws Exception {
        final CountDownLatch firstSeen = new CountDownLatch(1);
        final AtomicBoolean secondWaited = new AtomicBoolean();
        final String first = "event: message\ndata: {\"n\":1}\n\n";
        final String second = "event: message\ndata: {\"n\":2}\n\n";
        final ByteArrayOutputStream relayed = new ByteArrayOutputStream();
        try (Endpoint endpoint =
                        endpoint(
                                dir,
                                out -> {
                                    out.write(
                                            ("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n"
                                                            + "Transfer-Encoding: chunked\r\n\r\n")
                                                    .getBytes(StandardCharsets.UTF_8));
                                    chunk(out, first);
                                    // The second event only once the agent has the first: a
                                    // gateway that waited for more would hold the first here.
                                    secondWaited.set(firstSeen.await(10, TimeUnit.SECONDS));
                                    chunk(out, second);
                                    out.write("0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                                    out.flush();
                                });
                VaultProxy proxy = proxy(dir, endpoint.socket);
                SocketChannel agent =
                        SocketChannel.open(UnixDomainSocketAddress.of(proxy.socket()))) {
            agent.write(ByteBuffer.wrap(request().getBytes(StandardCharsets.UTF_8)));
            final InputStream in = Channels.newInputStream(agent);
            final byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                relayed.write(buffer, 0, read);
                if (relayed.toString(StandardCharsets.UTF_8).contains("{\"n\":1}")) {
                    firstSeen.countDown();
                }
            }
        }

        assertThat(secondWaited).isTrue();
        assertThat(relayed.toString(StandardCharsets.UTF_8))
                .startsWith("HTTP/1.1 200")
                .containsIgnoringCase("content-type: text/event-stream")
                .contains("{\"n\":1}", "{\"n\":2}")
                .endsWith("0\r\n\r\n");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                // Neither a length nor chunks: to the close would pass a cut-off answer on whole.
                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n{\"ok\":true}",
                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 11\r\n"
                        + "Transfer-Encoding: chunked\r\n\r\n{\"ok\":true}",
                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 1x\r\n\r\n"
                        + "{\"ok\":true}",
                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 11\r\n"
                        + "Content-Length: 12\r\n\r\n{\"ok\":true}",
                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nTransfer-Encoding: gzip\r\n"
                        + "\r\n{\"ok\":true}",
                "HTTP/1.1 200 OK\nContent-Type: application/json\nContent-Length: 11\n\n{\"ok\":true}",
                "HTTP/1.1 200 OK\r\nContent-Type : application/json\r\nContent-Length: 11\r\n\r\n"
                        + "{\"ok\":true}",
                "HTTP/2 200\r\nContent-Length: 11\r\n\r\n{\"ok\":true}",
                "HTTP/1.1 101 Switching Protocols\r\n\r\n{\"ok\":true}",
                "{\"ok\":true}",
                ""
            })
    void refusesAnAnswerItCannotFrame(String answer, @TempDir Path dir) throws Exception {
        final String relayed;
        try (Endpoint endpoint =
                        endpoint(dir, out -> out.write(answer.getBytes(StandardCharsets.UTF_8)));
                VaultProxy proxy = proxy(dir, endpoint.socket)) {
            relayed = send(proxy.socket(), request());
        }

        assertThat(relayed)
                .startsWith("HTTP/1.1 502")
                .contains("cannot frame")
                .doesNotContain("\"ok\"");
    }

    @Test
    void cutsOffAChunkedAnswerThatBreaksItsFraming(@TempDir Path dir) throws Exception {
        final String relayed;
        try (Endpoint endpoint =
                        endpoint(
                                dir,
                                out -> {
                                    out.write(
                                            ("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n"
                                                            + "Transfer-Encoding: chunked\r\n\r\n")
                                                    .getBytes(StandardCharsets.UTF_8));
                                    chunk(out, "data: {\"n\":1}\n\n");
                                    out.write(
                                            "zz\r\nnot a chunk\r\n"
                                                    .getBytes(StandardCharsets.UTF_8));
                                });
                VaultProxy proxy = proxy(dir, endpoint.socket)) {
            relayed = send(proxy.socket(), request());
        }

        // Cut off: the agent never reads the last chunk that would say the answer was whole.
        assertThat(relayed).startsWith("HTTP/1.1 200").doesNotEndWith("0\r\n\r\n");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/../other",
                "/sse/../../other",
                "/./other",
                "/..",
                "/%2e%2e/other",
                "/%2E%2E/other",
                "/%2e/other",
                "/sse%2f..%2fother",
                "/sse%2F..%2Fother",
                "/..?session=1"
            })
    void refusesATargetThatLeavesTheEndpointsPathBeforeSendingAnything(
            String target, @TempDir Path dir) throws Exception {
        final String relayed;
        try (Endpoint endpoint = endpoint(dir, out -> {});
                VaultProxy proxy = proxy(dir, endpoint.socket)) {
            relayed = send(proxy.socket(), request(target));
        }

        assertThat(relayed).startsWith("HTTP/1.1 400").doesNotContain(KEY);
        assertThat(received.get()).isEmpty();
    }

    @Test
    void givesUpOnAnEndpointThatStaysSilentBeforeItsHead(@TempDir Path dir) throws Exception {
        final CountDownLatch released = new CountDownLatch(1);
        try (Endpoint endpoint = endpoint(dir, out -> released.await(60, TimeUnit.SECONDS))) {
            assertTimeoutPreemptively(
                    Duration.ofSeconds(10),
                    () ->
                            assertThatThrownBy(
                                            () ->
                                                    UnixHttp.send(
                                                            endpoint.socket,
                                                            silentAfter(Duration.ofMillis(300)),
                                                            new byte[0]))
                                    .isInstanceOf(IOException.class));
        } finally {
            released.countDown();
        }
    }

    @Test
    void cutsOffAStreamThatFallsSilent(@TempDir Path dir) throws Exception {
        final CountDownLatch released = new CountDownLatch(1);
        try (Endpoint endpoint =
                endpoint(
                        dir,
                        out -> {
                            out.write(
                                    ("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n"
                                                    + "Transfer-Encoding: chunked\r\n\r\n")
                                            .getBytes(StandardCharsets.UTF_8));
                            chunk(out, "data: {\"n\":1}\n\n");
                            released.await(60, TimeUnit.SECONDS);
                        })) {
            final HttpResponse<InputStream> answer =
                    UnixHttp.send(
                            endpoint.socket, silentAfter(Duration.ofMillis(300)), new byte[0]);
            final ByteArrayOutputStream body = new ByteArrayOutputStream();
            assertTimeoutPreemptively(
                    Duration.ofSeconds(10),
                    () ->
                            assertThatThrownBy(() -> answer.body().transferTo(body))
                                    .isInstanceOf(IOException.class));
            assertThat(body.toString(StandardCharsets.UTF_8)).isEqualTo("data: {\"n\":1}\n\n");
        } finally {
            released.countDown();
        }
    }

    @Test
    void keepsAStreamThatSpeaksMoreOftenThanItsLimitOpenLongerThanIt(@TempDir Path dir)
            throws Exception {
        try (Endpoint endpoint =
                endpoint(
                        dir,
                        out -> {
                            out.write(
                                    ("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n"
                                                    + "Transfer-Encoding: chunked\r\n\r\n")
                                            .getBytes(StandardCharsets.UTF_8));
                            for (int i = 0; i < 10; i++) {
                                chunk(out, "data: " + i + "\n\n");
                                Thread.sleep(100);
                            }
                            out.write("0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                            out.flush();
                        })) {
            final HttpResponse<InputStream> answer =
                    UnixHttp.send(
                            endpoint.socket, silentAfter(Duration.ofMillis(300)), new byte[0]);
            try (InputStream body = answer.body()) {
                assertThat(new String(body.readAllBytes(), StandardCharsets.UTF_8))
                        .contains("data: 0", "data: 9");
            }
        }
    }

    /** A request whose timeout is the longest silence the endpoint is allowed. */
    private static HttpRequest silentAfter(Duration idle) {
        return HttpRequest.newBuilder(URI.create("http://localhost/mcp"))
                .timeout(idle)
                .GET()
                .build();
    }

    @Test
    void refusesASocketThatBecameALink(@TempDir Path dir) throws Exception {
        final String relayed;
        try (Endpoint endpoint = endpoint(dir, out -> {});
                VaultProxy proxy = proxy(dir, link(dir, endpoint.socket))) {
            relayed = send(proxy.socket(), request());
        }

        assertThat(relayed).startsWith("HTTP/1.1 502");
        assertThat(received.get()).isEmpty();
    }

    private static Path link(Path dir, Path socket) throws IOException {
        final Path link = dir.resolve("porelo/mcp/linked.sock");
        Files.createSymbolicLink(link, socket);
        return link;
    }

    private static String request() {
        return request("/");
    }

    private static String request(String target) {
        return "POST "
                + target
                + " HTTP/1.1\r\nHost: 127.0.0.1:9419\r\nAuthorization: Bearer "
                + PHANTOM
                + "\r\nContent-Type: application/json\r\nAccept: application/json, text/event-stream\r\n"
                + "Content-Length: "
                + JSON_RPC.length()
                + "\r\n\r\n"
                + JSON_RPC;
    }

    private static void chunk(OutputStream out, String data) throws IOException {
        final byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
        out.write((Integer.toHexString(bytes.length) + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(bytes);
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /** The gateway, with one route to the endpoint, keyed as porelo-mcp asks. */
    private static VaultProxy proxy(Path dir, Path endpoint) {
        final TokenExchange exchange =
                presented ->
                        PHANTOM.equals(presented)
                                ? new TokenExchange.Granted(KEY, "mcp")
                                : new TokenExchange.Rejected();
        final VaultProxy proxy =
                new VaultProxy(
                        dir.resolve("gateway/vault.sock"),
                        "https://provider.invalid",
                        exchange,
                        "x-api-key",
                        "",
                        null,
                        Map.of(
                                "mcp",
                                new VaultProxy.Route(
                                        "unix:" + endpoint, "Authorization", "Bearer ", null)),
                        line -> {});
        Thread.ofPlatform().daemon().start(proxy);
        return proxy;
    }

    private static String send(Path socket, String request) throws IOException {
        try (SocketChannel channel = SocketChannel.open(UnixDomainSocketAddress.of(socket))) {
            channel.write(ByteBuffer.wrap(request.getBytes(StandardCharsets.UTF_8)));
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            Channels.newInputStream(channel).transferTo(out);
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    /** Starts an endpoint that answers one connection, recording what it received. */
    private Endpoint endpoint(Path dir, Answer answer) throws IOException {
        final Path socket = Files.createDirectories(dir.resolve("porelo/mcp")).resolve("task.sock");
        final ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(UnixDomainSocketAddress.of(socket));
        final Thread serving =
                Thread.ofPlatform()
                        .daemon()
                        .start(
                                () -> {
                                    try (SocketChannel channel = server.accept()) {
                                        final InputStream in =
                                                new java.io.BufferedInputStream(
                                                        Channels.newInputStream(channel));
                                        final HttpHead head = HttpHead.read(in);
                                        final StringBuilder text = new StringBuilder();
                                        if (head != null) {
                                            text.append(head.method())
                                                    .append(' ')
                                                    .append(head.target())
                                                    .append(" HTTP/1.1\r\n");
                                            for (int i = 0; i < head.names().size(); i++) {
                                                text.append(head.names().get(i))
                                                        .append(": ")
                                                        .append(head.values().get(i))
                                                        .append("\r\n");
                                            }
                                            text.append("\r\n");
                                            final long length = head.contentLength();
                                            if (length > 0) {
                                                text.append(
                                                        new String(
                                                                HttpHead.readExactly(
                                                                        in, (int) length),
                                                                StandardCharsets.UTF_8));
                                            }
                                        }
                                        received.set(text.toString());
                                        answer.write(Channels.newOutputStream(channel));
                                    } catch (Exception ex) {
                                        // The test's assertions say what went wrong.
                                    }
                                });
        return new Endpoint(socket, server, serving);
    }

    private record Endpoint(Path socket, ServerSocketChannel server, Thread serving)
            implements AutoCloseable {
        @Override
        public void close() throws IOException {
            server.close();
            serving.interrupt();
        }
    }
}
