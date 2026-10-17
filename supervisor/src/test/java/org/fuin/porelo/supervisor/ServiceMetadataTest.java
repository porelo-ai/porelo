package org.fuin.porelo.supervisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ServiceMetadata}, against a stand-in service on loopback publishing both
 * documents.
 *
 * <p>The harm guarded: a grant's endpoints taken from a document published for another resource or
 * another authorization server, or an endpoint that is not https, would send the client, the
 * person's code and the refresh token to a host nobody named, or in the clear.
 */
class ServiceMetadataTest {

    private HttpServer server;

    private String base;

    private final Map<String, String> documents = new ConcurrentHashMap<>();

    private final List<String> asked = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    final String path = exchange.getRequestURI().getPath();
                    asked.add(path);
                    final String body = documents.get(path);
                    answer(exchange, body == null ? 404 : 200, body == null ? "{}" : body);
                });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static void answer(HttpExchange exchange, int status, String body) throws IOException {
        final byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }

    private void publish(String resource, String server) {
        documents.put(
                "/.well-known/oauth-protected-resource/mcp",
                "{\"resource\":\"" + resource + "\",\"authorization_servers\":[\"" + base + "\"]}");
        documents.put("/.well-known/oauth-authorization-server", server);
    }

    private String serverDocument(String issuer, String token) {
        return "{\"issuer\":\""
                + issuer
                + "\",\"registration_endpoint\":\""
                + base
                + "/register\",\"device_authorization_endpoint\":\""
                + base
                + "/device\",\"authorization_endpoint\":\""
                + base
                + "/authorize\",\"token_endpoint\":\""
                + token
                + "\",\"revocation_endpoint\":\""
                + base
                + "/revoke\"}";
    }

    @Test
    void readsTheEndpointsFromTheResourceAndItsAuthorizationServer() throws Exception {
        publish(base + "/mcp", serverDocument(base, base + "/token"));

        final ServiceMetadata.Endpoints endpoints =
                ServiceMetadata.discover(HttpClient.newHttpClient(), base + "/mcp");

        assertThat(endpoints)
                .as("every endpoint, as the authorization server's metadata names it")
                .isEqualTo(
                        new ServiceMetadata.Endpoints(
                                base,
                                base + "/register",
                                base + "/device",
                                base + "/authorize",
                                base + "/token",
                                base + "/revoke"));
        assertThat(asked)
                .as("the resource's document at its path, then the server's")
                .containsExactly(
                        "/.well-known/oauth-protected-resource/mcp",
                        "/.well-known/oauth-authorization-server");
    }

    @Test
    void aResourcePublishingAtItsHostsRootIsReadThere() throws Exception {
        documents.put(
                "/.well-known/oauth-protected-resource",
                "{\"resource\":\"" + base + "\",\"authorization_servers\":[\"" + base + "\"]}");
        documents.put(
                "/.well-known/oauth-authorization-server", serverDocument(base, base + "/token"));

        assertThat(ServiceMetadata.discover(HttpClient.newHttpClient(), base + "/mcp").tokenUrl())
                .as("found through the root's document")
                .isEqualTo(base + "/token");
    }

    @Test
    void aDocumentForAnotherResourceIsRefused() {
        publish("https://elsewhere.example/mcp", serverDocument(base, base + "/token"));

        assertThatThrownBy(
                        () -> ServiceMetadata.discover(HttpClient.newHttpClient(), base + "/mcp"))
                .as("a resource's metadata must name the resource asked about")
                .isInstanceOf(TokenPurchase.Refused.class)
                .hasMessageContaining("another resource");
    }

    @Test
    void aServerDocumentForAnotherIssuerIsRefused() {
        publish(base + "/mcp", serverDocument("https://elsewhere.example", base + "/token"));

        assertThatThrownBy(
                        () -> ServiceMetadata.discover(HttpClient.newHttpClient(), base + "/mcp"))
                .as("a server's metadata must name the server asked about")
                .isInstanceOf(TokenPurchase.Refused.class)
                .hasMessageContaining("another issuer");
    }

    @Test
    void anEndpointInTheClearOffThisMachineIsRefused() {
        publish(base + "/mcp", serverDocument(base, "http://tokens.example/token"));

        assertThatThrownBy(
                        () -> ServiceMetadata.discover(HttpClient.newHttpClient(), base + "/mcp"))
                .as("a refresh token is never sent in the clear to another host")
                .isInstanceOf(TokenPurchase.Refused.class)
                .hasMessageContaining("not https");
    }

    @Test
    void aServiceWithoutMetadataSaysToSetTheEndpoints() {
        assertThatThrownBy(
                        () -> ServiceMetadata.discover(HttpClient.newHttpClient(), base + "/mcp"))
                .isInstanceOf(TokenPurchase.Refused.class)
                .hasMessageContaining("set the endpoints on the entry");
    }

    @Test
    void theWellKnownDocumentGoesBetweenTheHostAndThePath() {
        assertThat(
                        ServiceMetadata.wellKnown(
                                "https://api.example/mcp/x", ServiceMetadata.SERVER_SUFFIX))
                .isEqualTo("https://api.example/.well-known/oauth-authorization-server/mcp/x");
        assertThat(ServiceMetadata.wellKnown("https://api.example/", ServiceMetadata.SERVER_SUFFIX))
                .isEqualTo("https://api.example/.well-known/oauth-authorization-server");
    }
}
