package org.fuin.porelo.organization.gittree;

import static org.assertj.core.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HttpForgeAdminTest {
    static final String SECRET = "fixture-admin-credential";
    HttpServer server;
    ExecutorService workers;
    URI base;
    final List<String> seen = new CopyOnWriteArrayList<>();
    final Map<String, Reply> replies = new ConcurrentHashMap<>();

    record Reply(int status, String body, long stall) {}

    @BeforeEach
    void start() throws Exception {
        workers = Executors.newVirtualThreadPerTaskExecutor();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(workers);
        server.createContext(
                "/",
                exchange -> {
                    final String path = exchange.getRequestURI().toString();
                    seen.add(path);
                    assertThat(exchange.getRequestHeaders().getFirst("Authorization"))
                            .as("only the intended forge receives its credential")
                            .isEqualTo("token " + SECRET);
                    final Reply reply = replies.getOrDefault(path, new Reply(500, SECRET, 0));
                    final byte[] bytes = reply.body.getBytes(StandardCharsets.UTF_8);
                    if (reply.status == 302) {
                        exchange.getResponseHeaders().add("Location", base + "/elsewhere");
                    }
                    try (exchange) {
                        exchange.sendResponseHeaders(
                                reply.status, bytes.length == 0 ? -1 : bytes.length);
                        if (bytes.length != 0) {
                            exchange.getResponseBody().write(bytes, 0, 1);
                            exchange.getResponseBody().flush();
                            if (reply.stall != 0) {
                                Thread.sleep(reply.stall);
                            }
                            exchange.getResponseBody().write(bytes, 1, bytes.length - 1);
                        }
                    } catch (IOException ex) {
                        // The client canceled an oversized or overdue response.
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                });
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() throws Exception {
        if (server != null) {
            server.stop(0);
        }
        if (workers != null) {
            workers.shutdownNow();
            assertThat(workers.awaitTermination(5, TimeUnit.SECONDS))
                    .as("fixture threads stop")
                    .isTrue();
        }
    }

    HttpForgeAdmin api(Duration timeout) {
        return new HttpForgeAdmin(URI.create(base + "/api/v1"), SECRET.toCharArray(), timeout);
    }

    @Test
    void aRedirectIsRefusedWithoutSendingTheTokenAgain() {
        replies.put("/api/v1/orgs/alpha", new Reply(302, SECRET, 0));
        try (var api = api(Duration.ofSeconds(2))) {
            assertThatThrownBy(() -> api.request("GET", "/orgs/alpha", null, 200, 404))
                    .as("redirects cannot move an administrator credential")
                    .isInstanceOf(HttpForgeAdmin.Refusal.class)
                    .hasMessageContaining("HTTP 302")
                    .hasMessageNotContaining(SECRET);
        }
        assertThat(seen)
                .as("only the original request was sent")
                .containsExactly("/api/v1/orgs/alpha");
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 403, 500, 503})
    void refusalCodesDistinguishDataFromUnavailableDependencies(int status) {
        replies.put("/api/v1/test", new Reply(status, SECRET, 0));
        try (var api = api(Duration.ofSeconds(2))) {
            assertThatThrownBy(() -> api.request("GET", "/test", null, 200))
                    .as("response body and credential remain secret")
                    .isInstanceOfSatisfying(
                            HttpForgeAdmin.Refusal.class,
                            error ->
                                    assertThat(error.exitCode())
                                            .as("server faults are unavailable dependencies")
                                            .isEqualTo(status >= 500 ? 69 : 65))
                    .hasMessageNotContaining(SECRET);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "not JSON", "", "{broken"})
    void anAmbiguousSuccessNeverMeansAnIdentityIsAbsent(String body) {
        replies.put("/api/v1/test", new Reply(200, body, 0));
        try (var api = api(Duration.ofSeconds(2))) {
            assertThatThrownBy(() -> api.request("GET", "/test", null, 200, 404))
                    .as("only 404 proves absence")
                    .isInstanceOf(HttpForgeAdmin.Refusal.class);
        }
    }

    @Test
    void aMissingIdentityAndAnEmptyMutationHaveTheirOwnMeaning() {
        replies.put("/api/v1/missing", new Reply(404, "", 0));
        replies.put("/api/v1/deleted", new Reply(204, "", 0));
        try (var api = api(Duration.ofSeconds(2))) {
            assertThat(api.request("GET", "/missing", null, 200, 404))
                    .as("404 is an explicit absence")
                    .isNull();
            assertThat(api.request("DELETE", "/deleted", null, 204))
                    .as("successful deletion may carry no JSON")
                    .isNull();
        }
    }

    @Test
    void everyPageIsReadUntilAnEmptyOne() {
        replies.put("/api/v1/list?limit=50&page=1", new Reply(200, "[{\"id\":1}]", 0));
        replies.put("/api/v1/list?limit=50&page=2", new Reply(200, "[{\"id\":2}]", 0));
        replies.put("/api/v1/list?limit=50&page=3", new Reply(200, "[]", 0));
        try (var api = api(Duration.ofSeconds(2))) {
            assertThat(api.listing("/list")).as("later pages cannot hide identities").hasSize(2);
        }
        assertThat(seen).as("the empty page completes the listing").hasSize(3);
    }

    @Test
    void aRepeatedPageWithReorderedFieldsIsRefused() {
        replies.put(
                "/api/v1/list?limit=50&page=1", new Reply(200, "[{\"id\":1,\"name\":\"one\"}]", 0));
        replies.put(
                "/api/v1/list?limit=50&page=2", new Reply(200, "[{\"name\":\"one\",\"id\":1}]", 0));
        try (var api = api(Duration.ofSeconds(2))) {
            assertThatThrownBy(() -> api.listing("/list"))
                    .as("map order cannot hide a repeated page")
                    .hasMessageContaining("repeated a page");
        }
    }

    @Test
    void aBodyOverOneMegabyteIsRefusedWhileReading() {
        replies.put("/api/v1/test", new Reply(200, "x".repeat(1024 * 1024 + 1), 0));
        try (var api = api(Duration.ofSeconds(2))) {
            assertThatThrownBy(() -> api.request("GET", "/test", null, 200))
                    .as("an unbounded reply cannot exhaust the host")
                    .isInstanceOf(HttpForgeAdmin.Refusal.class);
        }
    }

    @Test
    void aStalledBodyIsBoundedAfterTheHeadersArrive() {
        replies.put("/api/v1/test", new Reply(200, "{}", 5000));
        final long start = System.nanoTime();
        try (var api = api(Duration.ofMillis(150))) {
            assertThatThrownBy(() -> api.request("GET", "/test", null, 200))
                    .as("headers alone do not finish a request")
                    .isInstanceOf(HttpForgeAdmin.Refusal.class);
        }
        assertThat(Duration.ofNanos(System.nanoTime() - start))
                .as("the whole exchange has a deadline")
                .isLessThan(Duration.ofSeconds(3));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "//elsewhere/test",
                "/test\r\nInjected: yes",
                "https://elsewhere/test",
                "/test#fragment"
            })
    void anApiRouteCannotChooseAnotherOriginOrInjectHeaders(String path) {
        try (var api = api(Duration.ofSeconds(2))) {
            assertThatThrownBy(() -> api.request("GET", path, null, 200))
                    .as("the caller supplies a relative API route")
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(seen).as("invalid routes cause no request").isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "http://example.org",
                "https://user:secret@example.org",
                "https://example.org/path",
                "https://example.org?query",
                "https://example.org#fragment"
            })
    void centralOnlyAcceptsACredentialFreeSecureOrigin(String origin) {
        assertThatThrownBy(() -> HttpForgeAdmin.central(URI.create(origin), SECRET.toCharArray()))
                .as("a token belongs to a single secure origin")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(SECRET);
    }

    @Test
    void closingTheClientClearsItsOwnedTokenWithoutChangingTheCallersBuffer() {
        final char[] token = SECRET.toCharArray();
        final var api =
                new HttpForgeAdmin(URI.create(base + "/api/v1"), token, Duration.ofSeconds(2));
        api.close();
        assertThat(token)
                .as("the caller retains responsibility for its own buffer")
                .containsExactly(SECRET.toCharArray());
        assertThat(api.toString())
                .as("debug output never reveals credentials")
                .doesNotContain(SECRET);
    }
}
