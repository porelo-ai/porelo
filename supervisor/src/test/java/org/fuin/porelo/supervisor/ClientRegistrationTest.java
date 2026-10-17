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
import java.util.concurrent.CopyOnWriteArrayList;
import org.fuin.porelo.wire.Json;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ClientRegistration}, against a stand-in registration endpoint on loopback.
 *
 * <p>The harms guarded: a registration's secrets printed with it, a registration access token sent
 * where it was not issued for, and a registration left at the service when its deletion failed
 * silently.
 */
class ClientRegistrationTest {

    private HttpServer server;

    private String base;

    private final List<String> registered = new CopyOnWriteArrayList<>();

    private final List<String> deletions = new CopyOnWriteArrayList<>();

    private int deleteStatus = 204;

    private String registrationAnswer;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        registrationAnswer =
                "{\"client_id\":\"dyn-1\",\"client_secret\":\"cs-1\",\"registration_access_token\":\"rat-1\","
                        + "\"registration_client_uri\":\""
                        + base
                        + "/register/dyn-1\"}";
        server.createContext(
                "/register",
                exchange -> {
                    if ("DELETE".equals(exchange.getRequestMethod())) {
                        deletions.add(
                                exchange.getRequestURI().getPath()
                                        + " "
                                        + exchange.getRequestHeaders().getFirst("Authorization"));
                        exchange.sendResponseHeaders(deleteStatus, -1);
                        exchange.close();
                        return;
                    }
                    registered.add(
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8));
                    answer(exchange, 201, registrationAnswer);
                });
        server.start();
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

    @Test
    void registersAPublicClientForTheDeviceFlowAndKeepsWhatTheServiceIssued() throws Exception {
        final ClientRegistration.Registered done =
                ClientRegistration.register(
                        HttpClient.newHttpClient(),
                        base + "/register",
                        ClientRegistration.metadata(true, null, "repo offline_access"));

        assertThat(done)
                .as("everything the service issued is kept")
                .isEqualTo(
                        new ClientRegistration.Registered(
                                "dyn-1", "cs-1", "rat-1", base + "/register/dyn-1"));
        assertThat(done.managed()).isTrue();
        assertThat(Json.parse(registered.getFirst()))
                .as("a public client asking for the device code and its renewal")
                .isEqualTo(
                        Map.of(
                                "client_name",
                                "Porelo",
                                "token_endpoint_auth_method",
                                "none",
                                "grant_types",
                                List.of(
                                        "urn:ietf:params:oauth:grant-type:device_code",
                                        "refresh_token"),
                                "scope",
                                "repo offline_access"));
    }

    @Test
    void aRegistrationIsPrintedWithoutItsSecrets() {
        assertThat(
                        new ClientRegistration.Registered(
                                        "dyn-1", "cs-1", "rat-1", base + "/register/dyn-1")
                                .toString())
                .as("whatever prints a registration never prints its secret or its token")
                .contains("dyn-1")
                .doesNotContain("cs-1")
                .doesNotContain("rat-1");
    }

    @Test
    void aRegistrationUrlInTheClearOffThisMachineIsRefusedBeforeItsTokenIsKept() {
        registrationAnswer =
                "{\"client_id\":\"dyn-1\",\"registration_access_token\":\"rat-1\","
                        + "\"registration_client_uri\":\"http://elsewhere.example/register/dyn-1\"}";

        assertThatThrownBy(
                        () ->
                                ClientRegistration.register(
                                        HttpClient.newHttpClient(),
                                        base + "/register",
                                        ClientRegistration.metadata(true, null, "")))
                .as("the token would be sent there in the clear on removal")
                .isInstanceOf(TokenPurchase.Refused.class)
                .hasMessageContaining("not https");
    }

    @Test
    void deletesTheRegistrationWithItsOwnTokenAndAGoneOneIsDone() throws Exception {
        ClientRegistration.delete(HttpClient.newHttpClient(), base + "/register/dyn-1", "rat-1");
        deleteStatus = 404;
        ClientRegistration.delete(HttpClient.newHttpClient(), base + "/register/dyn-1", "rat-1");

        assertThat(deletions)
                .as("a DELETE of the registration's own URL, with its registration access token")
                .containsExactly("/register/dyn-1 Bearer rat-1", "/register/dyn-1 Bearer rat-1");
    }

    @Test
    void aRefusedDeletionIsSaidWithoutTheToken() {
        deleteStatus = 401;

        assertThatThrownBy(
                        () ->
                                ClientRegistration.delete(
                                        HttpClient.newHttpClient(),
                                        base + "/register/dyn-1",
                                        "rat-1"))
                .as("a deletion the service refused is never taken as done")
                .isInstanceOf(TokenPurchase.Refused.class)
                .hasMessageContaining("refused to delete")
                .hasMessageNotContaining("rat-1");
    }
}
