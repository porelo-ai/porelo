package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.fuin.porelo.agent.api.Credential;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for picking the OAuth client a sign-in was granted to, among the several an agent's CLI
 * holds. Every id, secret and token here is made up.
 */
class ClientPickTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-10-28T10:00:00Z"), ZoneOffset.UTC);

    private HttpServer server;

    /** Each request's client id and secret, as "id/secret", in order. */
    private final List<String> tried = new CopyOnWriteArrayList<>();

    /** The one client that answers this sign-in's refresh token with a token. */
    private volatile String grantedId = "1071006060591-consumer.apps.googleusercontent.com";

    private volatile String grantedSecret = "secret-one";

    /** The refresh token the service takes now; an answer rotates it, as Google may. */
    private volatile String liveRefresh = "rt-1";

    /** Whether the service answers every request as busy, judging no pair. */
    private volatile boolean busy;

    /** Another client the service knows, whose secret is right but whose token this is not. */
    private static final String KNOWN_ID = "884354919052-gcp.apps.googleusercontent.com";

    private static final String KNOWN_SECRET = "secret-two";

    /** A client the service answers invalid_grant: the token was not granted to it either. */
    private static final String GRANT_ID = "555555555555-other.apps.googleusercontent.com";

    private static final String GRANT_SECRET = "secret-three";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/token",
                exchange -> {
                    final Map<String, String> form = new LinkedHashMap<>();
                    for (final String pair :
                            new String(
                                            exchange.getRequestBody().readAllBytes(),
                                            StandardCharsets.UTF_8)
                                    .split("&")) {
                        final String[] kv = pair.split("=", 2);
                        form.put(
                                URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                                kv.length > 1
                                        ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8)
                                        : "");
                    }
                    final String id = String.valueOf(form.get("client_id"));
                    final String secret = String.valueOf(form.get("client_secret"));
                    tried.add(id + "/" + secret);
                    final int code;
                    final String body;
                    if (busy) {
                        code = 503;
                        body = "{\"error\":\"temporarily_unavailable\"}";
                    } else if (grantedId.equals(id)
                            && grantedSecret.equals(secret)
                            && liveRefresh.equals(form.get("refresh_token"))) {
                        final String next =
                                "rt-" + (Integer.parseInt(liveRefresh.substring(3)) + 1);
                        liveRefresh = next;
                        code = 200;
                        body =
                                "{\"access_token\":\"at-2\",\"refresh_token\":\""
                                        + next
                                        + "\",\"expires_in\":3600}";
                    } else if (GRANT_ID.equals(id) && GRANT_SECRET.equals(secret)) {
                        code = 400;
                        body = "{\"error\":\"invalid_grant\"}";
                    } else if (KNOWN_ID.equals(id) && KNOWN_SECRET.equals(secret)) {
                        // A client the service knows, but the token was granted to another.
                        code = 400;
                        body = "{\"error\":\"unauthorized_client\"}";
                    } else {
                        code = 401;
                        body = "{\"error\":\"invalid_client\"}";
                    }
                    final byte[] payload = body.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(code, payload.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(payload);
                    }
                });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private Credential signIn(final String ids, final String secrets) {
        final Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put(Credential.REFRESH_TOKEN, "rt-1");
        attributes.put(Credential.EXPIRES_AT, "1793181600000");
        attributes.put(
                Credential.TOKEN_URL,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/token");
        attributes.put(Credential.CLIENT_ID_CANDIDATES, ids);
        attributes.put(Credential.CLIENT_SECRET_CANDIDATES, secrets);
        return new Credential("oauth", "at-1", attributes);
    }

    @Test
    void thePairTheServiceAnswersIsKeptWithTheTokenItAnswered() {

        // Measured in a CLI holding three clients: its code takes the consumer client, the second
        // id with the first secret, while the first id and the first secret were stored and
        // refused at the first renewal.
        final Credential picked =
                ClientPick.pick(
                        signIn(
                                KNOWN_ID + "\n1071006060591-consumer.apps.googleusercontent.com",
                                "secret-one\nsecret-two"),
                        HttpClient.newHttpClient(),
                        CLOCK);

        assertThat(tried)
                .as("ids outer, secrets inner, stopped at the pair answered")
                .containsExactly(
                        KNOWN_ID + "/secret-one",
                        KNOWN_ID + "/secret-two",
                        "1071006060591-consumer.apps.googleusercontent.com/secret-one");
        assertThat(picked.attributes())
                .containsEntry(
                        Credential.CLIENT_ID, "1071006060591-consumer.apps.googleusercontent.com")
                .containsEntry(Credential.CLIENT_SECRET, "secret-one")
                .containsEntry(Credential.REFRESH_TOKEN, "rt-2")
                .doesNotContainKeys(
                        Credential.CLIENT_ID_CANDIDATES, Credential.CLIENT_SECRET_CANDIDATES);
        assertThat(picked.secret()).as("the fresh access token").isEqualTo("at-2");
        assertThat(Long.parseLong(picked.attributes().get(Credential.EXPIRES_AT)))
                .isGreaterThan(Instant.parse("2026-10-28T10:00:00Z").toEpochMilli());
    }

    @Test
    void aRenewalBuiltFromThePickIsAnswered() throws Exception {
        final Credential picked =
                ClientPick.pick(
                        signIn(
                                KNOWN_ID + "\n1071006060591-consumer.apps.googleusercontent.com",
                                "secret-two\nsecret-one"),
                        HttpClient.newHttpClient(),
                        CLOCK);
        tried.clear();

        final String renewed =
                new org.fuin.porelo.supervisor.TokenPurchase(
                                new org.fuin.porelo.supervisor.TokenPurchase.Refresh(
                                        picked.attributes().get(Credential.TOKEN_URL),
                                        picked.attributes().get(Credential.CLIENT_ID),
                                        picked.attributes().get(Credential.CLIENT_SECRET),
                                        picked.attributes().get(Credential.REFRESH_TOKEN),
                                        next -> {}),
                                HttpClient.newHttpClient(),
                                CLOCK)
                        .current();

        assertThat(renewed).isEqualTo("at-2");
        assertThat(tried)
                .containsExactly("1071006060591-consumer.apps.googleusercontent.com/secret-one");
    }

    @Test
    void noPairAnsweredIsRefusedNamingCountsAndCodesNeverAValue() {
        grantedId = "an-id-nobody-listed.apps.googleusercontent.com";

        assertThatThrownBy(
                        () ->
                                ClientPick.pick(
                                        signIn(
                                                KNOWN_ID + "\n" + KNOWN_ID,
                                                "secret-one\nsecret-two"),
                                        HttpClient.newHttpClient(),
                                        CLOCK))
                .isInstanceOf(ClientPick.NoPair.class)
                .hasMessageContaining("none of the 2 clients")
                .hasMessageContaining("1 ids, 2 secrets")
                .hasMessageContaining("401 invalid_client")
                .hasMessageContaining("400 unauthorized_client")
                .hasMessageNotContaining("secret-one")
                .hasMessageNotContaining("rt-1");
        assertThat(tried).as("each pair once, the repeated id skipped").hasSize(2);
    }

    @Test
    void aClientThatSaysTheGrantIsNotItsOwnIsPassedOverToo() {
        final Credential picked =
                ClientPick.pick(
                        signIn(
                                GRANT_ID + "\n1071006060591-consumer.apps.googleusercontent.com",
                                "secret-three\nsecret-one"),
                        HttpClient.newHttpClient(),
                        CLOCK);

        assertThat(tried).first().isEqualTo(GRANT_ID + "/secret-three");
        assertThat(picked.attributes())
                .containsEntry(
                        Credential.CLIENT_ID, "1071006060591-consumer.apps.googleusercontent.com");
    }

    @Test
    void aBusyServiceStopsThePickAtTheFirstTryRatherThanSayingNoPairFits() {
        busy = true;

        assertThatThrownBy(
                        () ->
                                ClientPick.pick(
                                        signIn(
                                                KNOWN_ID + "\n" + GRANT_ID,
                                                "secret-one\nsecret-two"),
                                        HttpClient.newHttpClient(),
                                        CLOCK))
                .isInstanceOf(ClientPick.NoPair.class)
                .hasMessageContaining("answered 503 temporarily_unavailable");
        assertThat(tried).hasSize(1);
    }

    @Test
    void aTokenEndpointThatCannotBeReachedIsSaidWithoutAnythingThatWasSent() {
        final Map<String, String> attributes = new LinkedHashMap<>(signIn("x", "y").attributes());
        attributes.put(Credential.TOKEN_URL, "http://127.0.0.1:9/token");
        attributes.put(Credential.CLIENT_ID_CANDIDATES, KNOWN_ID);
        attributes.put(Credential.CLIENT_SECRET_CANDIDATES, "secret-one");

        assertThatThrownBy(
                        () ->
                                ClientPick.pick(
                                        new Credential("oauth", "at-1", attributes),
                                        HttpClient.newHttpClient(),
                                        CLOCK))
                .isInstanceOf(ClientPick.NoPair.class)
                .hasMessageContaining("could not be asked")
                .hasMessageNotContaining("rt-1")
                .hasMessageNotContaining("secret-one")
                .hasMessageNotContaining("127.0.0.1");
    }

    @Test
    void aRefreshTokenIsNeverSentToAnEndpointThatIsNotSecure() {
        final Map<String, String> attributes = new LinkedHashMap<>(signIn("x", "y").attributes());
        attributes.put(Credential.TOKEN_URL, "http://192.0.2.1:9/token");
        attributes.put(Credential.CLIENT_ID_CANDIDATES, KNOWN_ID);
        attributes.put(Credential.CLIENT_SECRET_CANDIDATES, "secret-one");

        final Credential kept =
                ClientPick.pick(
                        new Credential("oauth", "at-1", attributes),
                        HttpClient.newHttpClient(),
                        CLOCK);

        assertThat(kept.attributes())
                .as("kept with no client, so not renewable")
                .doesNotContainKeys(
                        Credential.CLIENT_ID,
                        Credential.CLIENT_SECRET,
                        Credential.CLIENT_ID_CANDIDATES,
                        Credential.CLIENT_SECRET_CANDIDATES);
        assertThat(tried).isEmpty();
    }

    @Test
    void aSignInWithoutCandidatesIsKeptAsItIs() {
        final Credential plain =
                new Credential(
                        "oauth",
                        "at-1",
                        Map.of(Credential.REFRESH_TOKEN, "rt-1", Credential.CLIENT_ID, "c"));

        assertThat(ClientPick.pick(plain, HttpClient.newHttpClient(), CLOCK)).isSameAs(plain);
        assertThat(tried).isEmpty();
    }

    @Test
    void candidatesWithNoRefreshTokenAreKeptWithNoClientSoNothingRenewsWithAGuess() {
        final Credential picked =
                ClientPick.pick(
                        new Credential(
                                "oauth",
                                "at-1",
                                Map.of(
                                        Credential.CLIENT_ID_CANDIDATES,
                                        KNOWN_ID,
                                        Credential.CLIENT_SECRET_CANDIDATES,
                                        "secret-one")),
                        HttpClient.newHttpClient(),
                        CLOCK);

        assertThat(picked.attributes()).isEmpty();
        assertThat(tried).isEmpty();
    }
}
