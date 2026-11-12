package org.fuin.porelo.supervisor;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A task reads a company's package repository through its repository broker: GET and HEAD under the
 * declared prefix, with the vault's credential and never its own, and nothing credential-shaped
 * comes back.
 *
 * <p>The repository here answers plain http on loopback: what the broker decides does not depend on
 * TLS, which its client does as the agent's broker's does.
 */
class RepositoryProxyTest {

    private static final String USER_PASSWORD = "builder:s3cret-repository-password";

    private static final String BASIC =
            Base64.getEncoder().encodeToString(USER_PASSWORD.getBytes(StandardCharsets.UTF_8));

    /** Bytes no text decoder would pass unchanged. */
    private static final byte[] JAR = {
        0x50, 0x4b, 0x03, 0x04, 0x00, (byte) 0xff, (byte) 0xfe, 0x0a, 0x0d, 0x00, (byte) 0x80, 0x7f
    };

    private HttpServer repository;

    private final List<Map<String, String>> reached = new CopyOnWriteArrayList<>();

    private final List<String> logged = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startRepository() throws IOException {
        repository = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        repository.createContext(
                "/",
                exchange -> {
                    final Map<String, String> headers = new java.util.LinkedHashMap<>();
                    exchange.getRequestHeaders()
                            .forEach(
                                    (name, values) ->
                                            headers.put(
                                                    name.toLowerCase(java.util.Locale.ROOT),
                                                    values.get(0)));
                    headers.put(":method", exchange.getRequestMethod());
                    headers.put(":target", exchange.getRequestURI().getRawPath());
                    reached.add(headers);
                    final String path = exchange.getRequestURI().getRawPath();
                    if (!("Basic " + BASIC).equals(headers.get("authorization"))) {
                        answer(exchange, 401, "text/plain", "who are you".getBytes());
                        return;
                    }
                    switch (path) {
                        case "/repository/maven/org/demo/demo.jar" ->
                                answer(exchange, 200, "application/java-archive", JAR);
                        case "/repository/maven/moved.jar" -> {
                            exchange.getResponseHeaders()
                                    .set("Location", "/repository/maven/org/demo/demo.jar");
                            answer(exchange, 302, "text/plain", new byte[0]);
                        }
                        case "/repository/maven/away.jar" -> {
                            exchange.getResponseHeaders()
                                    .set("Location", base() + "/elsewhere/demo.jar");
                            answer(exchange, 302, "text/plain", new byte[0]);
                        }
                        case "/repository/maven/sibling.jar" -> {
                            exchange.getResponseHeaders()
                                    .set("Location", base() + "/repository/maven-other/demo.jar");
                            answer(exchange, 302, "text/plain", new byte[0]);
                        }
                        case "/repository/maven/clear.txt" ->
                                answer(
                                        exchange,
                                        200,
                                        "text/plain",
                                        ("before " + "x".repeat(20_000) + USER_PASSWORD + " after")
                                                .getBytes(StandardCharsets.UTF_8));
                        case "/repository/maven/encoded.xml" ->
                                answer(
                                        exchange,
                                        200,
                                        "application/xml",
                                        ("<server><auth>" + BASIC + "</auth></server>")
                                                .getBytes(StandardCharsets.UTF_8));
                        case "/repository/maven/packed.json" -> {
                            final ByteArrayOutputStream packed = new ByteArrayOutputStream();
                            try (java.util.zip.GZIPOutputStream gzip =
                                    new java.util.zip.GZIPOutputStream(packed)) {
                                gzip.write(
                                        ("{\"auth\":\"" + BASIC + "\"}")
                                                .getBytes(StandardCharsets.UTF_8));
                            }
                            exchange.getResponseHeaders().set("Content-Encoding", "gzip");
                            answer(exchange, 200, "application/json", packed.toByteArray());
                        }
                        case "/repository/maven/escaped.json" ->
                                answer(
                                        exchange,
                                        200,
                                        "application/json",
                                        ("{\"auth\":\"builder:\\u0073\\u0033cret\\u002Drepository-password\"}")
                                                .getBytes(StandardCharsets.UTF_8));
                        case "/repository/maven/percent.txt" ->
                                answer(
                                        exchange,
                                        200,
                                        "text/plain",
                                        "builder%3as3cret%2Drepository%2dpassword"
                                                .getBytes(StandardCharsets.UTF_8));
                        case "/repository/maven/clear.ndjson" ->
                                answer(
                                        exchange,
                                        200,
                                        "application/x-ndjson",
                                        ("{\"auth\":\"" + USER_PASSWORD + "\"}\n")
                                                .getBytes(StandardCharsets.UTF_8));
                        case "/repository/maven/clear.yaml" ->
                                answer(
                                        exchange,
                                        200,
                                        "application/x-yaml",
                                        ("auth: " + USER_PASSWORD + "\n")
                                                .getBytes(StandardCharsets.UTF_8));
                        case "/repository/maven/wide.json" ->
                                answer(
                                        exchange,
                                        200,
                                        "application/json; charset=UTF-16",
                                        ("{\"auth\":\"" + USER_PASSWORD + "\"}")
                                                .getBytes(StandardCharsets.UTF_16));
                        case "/repository/maven/headers.jar" -> {
                            exchange.getResponseHeaders()
                                    .set("X-Debug-Auth", "s3cret%2Drepository%2Dpassword");
                            exchange.getResponseHeaders().set("X-Anything", "harmless");
                            exchange.getResponseHeaders().set("ETag", "\"v1\"");
                            exchange.getResponseHeaders().set("X-Checksum-Sha1", "0123abcd");
                            answer(exchange, 200, "application/java-archive", JAR);
                        }
                        default -> answer(exchange, 404, "text/plain", "none".getBytes());
                    }
                });
        repository.start();
    }

    @AfterEach
    void stopRepository() {
        repository.stop(0);
    }

    private static void answer(
            com.sun.net.httpserver.HttpExchange exchange, int status, String type, byte[] body)
            throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        final boolean head = "HEAD".equals(exchange.getRequestMethod());
        exchange.sendResponseHeaders(status, head || body.length == 0 ? -1 : body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            if (!head) out.write(body);
        }
    }

    private String base() {
        return "http://127.0.0.1:" + repository.getAddress().getPort();
    }

    private RepositoryProxy proxy(Path socket) {
        final RepositoryProxy proxy =
                new RepositoryProxy(
                        socket,
                        List.of(
                                new RepositoryProxy.Repository(
                                        "corp",
                                        base() + "/repository/maven/",
                                        "Authorization",
                                        "Basic " + BASIC,
                                        List.of(USER_PASSWORD, BASIC))),
                        logged::add,
                        Path.of("/nonexistent/porelo-no-authorities"));
        Thread.ofPlatform().daemon().start(proxy);
        return proxy;
    }

    private static byte[] send(Path socket, String request) throws IOException {
        try (SocketChannel channel = SocketChannel.open(UnixDomainSocketAddress.of(socket))) {
            channel.write(java.nio.ByteBuffer.wrap(request.getBytes(StandardCharsets.UTF_8)));
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final InputStream in = Channels.newInputStream(channel);
            in.transferTo(out);
            return out.toByteArray();
        }
    }

    private static String text(byte[] answer) {
        return new String(answer, StandardCharsets.ISO_8859_1);
    }

    private static byte[] body(byte[] answer) {
        final String all = text(answer);
        final int at = all.indexOf("\r\n\r\n");
        return java.util.Arrays.copyOfRange(answer, at + 4, answer.length);
    }

    @Test
    void aGetReachesTheRepositoryWithTheVaultsCredentialAndNeverTheTasks(@TempDir Path dir)
            throws IOException {
        final Path socket = dir.resolve("repository.sock");
        final byte[] answer;
        try (RepositoryProxy proxy = proxy(socket)) {
            answer =
                    send(
                            socket,
                            "GET /corp/org/demo/demo.jar HTTP/1.1\r\n"
                                    + "Host: 127.0.0.1:9421\r\n"
                                    + "Authorization: Basic dGFzazpvd24=\r\n"
                                    + "X-Api-Key: the-tasks-own\r\n"
                                    + "Private-Token: the-tasks-own\r\n"
                                    + "Cookie: session=the-tasks-own\r\n"
                                    + "Accept: */*\r\n\r\n");
        }

        assertThat(text(answer)).startsWith("HTTP/1.1 200");
        assertThat(body(answer)).as("a binary answer passes byte for byte").isEqualTo(JAR);
        assertThat(reached)
                .singleElement()
                .satisfies(
                        request -> {
                            assertThat(request.get(":target"))
                                    .isEqualTo("/repository/maven/org/demo/demo.jar");
                            assertThat(request.get("authorization"))
                                    .as("the vault's, never the task's")
                                    .isEqualTo("Basic " + BASIC);
                            assertThat(request)
                                    .doesNotContainKeys("x-api-key", "private-token", "cookie");
                            assertThat(request.get("accept")).isEqualTo("*/*");
                        });
    }

    @Test
    void aHeadPassesAsAGetDoes(@TempDir Path dir) throws IOException {
        final Path socket = dir.resolve("repository.sock");
        final byte[] answer;
        try (RepositoryProxy proxy = proxy(socket)) {
            answer = send(socket, "HEAD /corp/org/demo/demo.jar HTTP/1.1\r\n\r\n");
        }

        assertThat(text(answer)).startsWith("HTTP/1.1 200");
        assertThat(reached).singleElement().extracting(r -> r.get(":method")).isEqualTo("HEAD");
    }

    @ParameterizedTest
    @ValueSource(strings = {"PUT", "POST", "DELETE", "PATCH", "MKCOL", "OPTIONS"})
    void everyOtherMethodIsRefusedAndNeverReachesTheRepository(String method, @TempDir Path dir)
            throws IOException {
        final Path socket = dir.resolve("repository.sock");
        final byte[] answer;
        try (RepositoryProxy proxy = proxy(socket)) {
            answer =
                    send(
                            socket,
                            method
                                    + " /corp/org/demo/demo.jar HTTP/1.1\r\n"
                                    + "Content-Length: 3\r\n\r\njar");
        }

        assertThat(text(answer)).startsWith("HTTP/1.1 405");
        assertThat(reached).as("a deploy with the company's credential").isEmpty();
        assertThat(logged).singleElement().asString().contains(method).contains("405");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/corp/../maven-other/demo.jar",
                "/corp/org/../../maven-other/demo.jar",
                "/corp/%2e%2e/maven-other/demo.jar",
                "/corp/%2E%2E/maven-other/demo.jar",
                "/corp/.%2e/maven-other/demo.jar",
                "/corp/%252e%252e/maven-other/demo.jar",
                "/corp/..%2fmaven-other/demo.jar",
                "/corp/..%2Fmaven-other/demo.jar",
                "/corp/..%5cmaven-other/demo.jar",
                "/corp/..\\maven-other/demo.jar",
                "/corp//org/demo/demo.jar",
                "/corp/org//demo/demo.jar",
                "/corp/./org/demo/demo.jar",
                "/corp/org/%00/demo.jar",
                "/corp/..;x=1/admin",
                "/corp/org;jsessionid=1/demo/demo.jar",
                "/corp/..%3bx=1/admin",
                "/corp/..%3Bx=1/admin",
                "/corp",
                "/other/org/demo/demo.jar",
                "http://127.0.0.1/corp/org/demo/demo.jar",
                "//evil.example/corp/org/demo/demo.jar",
                "*"
            })
    void aPathThatCouldLeaveThePrefixIsRefusedBeforeTheRepository(String target, @TempDir Path dir)
            throws IOException {
        final Path socket = dir.resolve("repository.sock");
        final byte[] answer;
        try (RepositoryProxy proxy = proxy(socket)) {
            answer = send(socket, "GET " + target + " HTTP/1.1\r\n\r\n");
        }

        assertThat(text(answer)).as(target).matches("(?s)HTTP/1.1 4\\d\\d .*");
        assertThat(reached).as("nothing reaches the repository for %s", target).isEmpty();
    }

    @Test
    void aRedirectInsideThePrefixIsFollowedByTheBroker(@TempDir Path dir) throws IOException {
        final Path socket = dir.resolve("repository.sock");
        final byte[] answer;
        try (RepositoryProxy proxy = proxy(socket)) {
            answer = send(socket, "GET /corp/moved.jar HTTP/1.1\r\n\r\n");
        }

        assertThat(text(answer)).startsWith("HTTP/1.1 200");
        assertThat(body(answer)).isEqualTo(JAR);
        assertThat(reached)
                .extracting(r -> r.get(":target"))
                .containsExactly(
                        "/repository/maven/moved.jar", "/repository/maven/org/demo/demo.jar");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/corp/away.jar", "/corp/sibling.jar"})
    void aRedirectOffThePrefixIsAnsweredAsAFailureAndNeverFollowed(String target, @TempDir Path dir)
            throws IOException {
        final Path socket = dir.resolve("repository.sock");
        final byte[] answer;
        try (RepositoryProxy proxy = proxy(socket)) {
            answer = send(socket, "GET " + target + " HTTP/1.1\r\n\r\n");
        }

        assertThat(text(answer)).startsWith("HTTP/1.1 502");
        assertThat(text(answer)).as("where it pointed is not repeated").doesNotContain("Location");
        assertThat(reached).as("only the first request").hasSize(1);
        assertThat(logged).anyMatch(line -> line.contains("502") && line.contains("redirect"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/corp/clear.txt",
                "/corp/encoded.xml",
                "/corp/escaped.json",
                "/corp/percent.txt",
                "/corp/clear.ndjson",
                "/corp/clear.yaml"
            })
    void aTextAnswerCarryingTheCredentialIsCutOffBeforeIt(String target, @TempDir Path dir)
            throws IOException {
        final Path socket = dir.resolve("repository.sock");
        final byte[] answer;
        try (RepositoryProxy proxy = proxy(socket)) {
            answer = send(socket, "GET " + target + " HTTP/1.1\r\n\r\n");
        }

        assertThat(text(answer))
                .doesNotContain(USER_PASSWORD)
                .doesNotContain(BASIC)
                .doesNotContain("s3cret-repository-password")
                .doesNotContain("repository-password")
                .doesNotContain("repository%2dpassword");
        assertThat(logged).anyMatch(line -> line.contains("cut"));
    }

    @Test
    void anEscapeSplitAcrossReadsIsHeldBackWhole() throws IOException {
        final byte[] sent =
                "{\"auth\":\"\\u0062uilder:s3cret-repository-password\"}"
                        .getBytes(StandardCharsets.UTF_8);
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        // One byte a read: every boundary falls inside the escape once.
        final InputStream trickle =
                new java.io.ByteArrayInputStream(sent) {
                    @Override
                    public synchronized int read(byte[] into, int offset, int length) {
                        return super.read(into, offset, Math.min(1, length));
                    }
                };

        final boolean whole =
                RepositoryProxy.scanned(
                        trickle,
                        out,
                        new RepositoryProxy.Repository(
                                "corp",
                                "https://repo.example/",
                                "Authorization",
                                "Basic " + BASIC,
                                List.of(USER_PASSWORD, BASIC)));

        assertThat(whole).isFalse();
        assertThat(out.toString(StandardCharsets.UTF_8)).isEqualTo("{\"auth\":\"");
    }

    @Test
    void aTextAnswerInAnotherCharsetIsWithheld(@TempDir Path dir) throws IOException {
        final Path socket = dir.resolve("repository.sock");
        final byte[] answer;
        try (RepositoryProxy proxy = proxy(socket)) {
            answer = send(socket, "GET /corp/wide.json HTTP/1.1\r\n\r\n");
        }

        assertThat(text(answer)).startsWith("HTTP/1.1 502");
        assertThat(new String(answer, StandardCharsets.UTF_16))
                .doesNotContain("s3cret-repository-password");
    }

    @Test
    void onlyTheAnswersAllowedHeadersReachTheTask(@TempDir Path dir) throws IOException {
        final Path socket = dir.resolve("repository.sock");
        final byte[] answer;
        try (RepositoryProxy proxy = proxy(socket)) {
            answer = send(socket, "GET /corp/headers.jar HTTP/1.1\r\n\r\n");
        }

        final String head = text(answer).substring(0, text(answer).indexOf("\r\n\r\n"));
        assertThat(head.toLowerCase(java.util.Locale.ROOT))
                .contains("content-type: application/java-archive")
                .contains("etag: \"v1\"")
                .contains("x-checksum-sha1: 0123abcd")
                .doesNotContain("x-debug-auth")
                .doesNotContain("x-anything")
                .doesNotContain("s3cret");
        assertThat(body(answer)).isEqualTo(JAR);
    }

    @Test
    void aRangeIsNeverAskedForSoEveryAnswerIsWhole(@TempDir Path dir) throws IOException {
        final Path socket = dir.resolve("repository.sock");
        try (RepositoryProxy proxy = proxy(socket)) {
            send(
                    socket,
                    "GET /corp/org/demo/demo.jar HTTP/1.1\r\nRange: bytes=0-0\r\n"
                            + "If-Range: \"v1\"\r\n\r\n");
        }

        assertThat(reached)
                .singleElement()
                .satisfies(request -> assertThat(request).doesNotContainKeys("range", "if-range"));
    }

    @Test
    void aTextAnswerTheScanCannotReadIsWithheldAndCompressionIsNeverAskedFor(@TempDir Path dir)
            throws IOException {
        final Path socket = dir.resolve("repository.sock");
        final byte[] answer;
        try (RepositoryProxy proxy = proxy(socket)) {
            answer =
                    send(socket, "GET /corp/packed.json HTTP/1.1\r\nAccept-Encoding: gzip\r\n\r\n");
        }

        assertThat(text(answer)).startsWith("HTTP/1.1 502");
        assertThat(reached)
                .singleElement()
                .satisfies(
                        request ->
                                assertThat(request)
                                        .as("the task's wish for a compressed answer is dropped")
                                        .doesNotContainKey("accept-encoding"));
    }

    @Test
    void everyRequestIsLoggedAndNoLineCarriesACredential(@TempDir Path dir) throws IOException {
        final Path socket = dir.resolve("repository.sock");
        try (RepositoryProxy proxy = proxy(socket)) {
            send(
                    socket,
                    "GET /corp/org/demo/demo.jar HTTP/1.1\r\nAuthorization: Bearer mine\r\n\r\n");
            send(socket, "PUT /corp/org/demo/demo.jar HTTP/1.1\r\nContent-Length: 0\r\n\r\n");
            send(socket, "GET /corp/../x HTTP/1.1\r\n\r\n");
            send(socket, "GET /corp/clear.txt HTTP/1.1\r\n\r\n");
            send(socket, "GET /corp/away.jar HTTP/1.1\r\n\r\n");
        }

        assertThat(
                        logged.stream().filter(line -> line.startsWith("GET ")).count()
                                + logged.stream().filter(line -> line.startsWith("PUT ")).count())
                .as("one line per request: %s", logged)
                .isGreaterThanOrEqualTo(5);
        assertThat(logged)
                .allSatisfy(
                        line ->
                                assertThat(line)
                                        .doesNotContain(USER_PASSWORD)
                                        .doesNotContain(BASIC)
                                        .doesNotContain("s3cret")
                                        .doesNotContain("mine"));
    }
}
