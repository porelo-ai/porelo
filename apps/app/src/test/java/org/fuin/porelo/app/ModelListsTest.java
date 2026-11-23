package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.fuin.porelo.agent.api.ProviderDefinition;
import org.fuin.porelo.vault.VaultEntry;
import org.fuin.porelo.wire.LimitFactors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The models a provider offers, asked of a stand-in provider with the vault's credential. */
class ModelListsTest {

    private static final String KEY = "sk-real-key-never-shown";

    private static ProviderDefinition provider(final String yaml) {
        return org.fuin.porelo.agent.api.ProviderDefinitionReader.read(
                new java.io.StringReader(yaml), "test.yaml");
    }

    /** Pages by the last id, with the headers a key and a sign-in each take. */
    private static final ProviderDefinition ANTHROPIC =
            provider(
                    """
                    name: anthropic
                    upstream: https://api.anthropic.example
                    dialects:
                      anthropic-messages: ""
                    auth_header:
                      _default: x-api-key
                      oauth: Authorization
                    auth_prefix:
                      _default: ""
                      oauth: "Bearer "
                    models:
                      path: /v1/models
                      format: last-id-pages
                      headers:
                        _default:
                          anthropic-version: "2023-06-01"
                    """);

    /** One data list, under the path of its own dialect. */
    private static final ProviderDefinition OPENROUTER =
            provider(
                    """
                    name: openrouter
                    upstream: https://openrouter.example
                    dialects:
                      openai: /api/v1
                      anthropic-messages: /api
                    auth_header:
                      _default: Authorization
                    auth_prefix:
                      _default: "Bearer "
                    models:
                      path: /api/v1/models
                      format: data
                    """);

    /** A provider that declares no list a key reads. */
    private static final ProviderDefinition SIGN_IN_ONLY =
            provider(
                    """
                    name: subscription
                    upstream: https://subscription.example
                    dialects:
                      openai: /backend
                    auth_header:
                      _default: Authorization
                    auth_prefix:
                      _default: "Bearer "
                    unbrokerable:
                      api-key: takes a sign-in
                    """);

    private final List<String> asked = new CopyOnWriteArrayList<>();

    private final List<Map<String, String>> headers = new CopyOnWriteArrayList<>();

    private HttpServer server;

    /** A clock the test moves. */
    static final class Hands extends java.time.Clock {

        private volatile java.time.Instant now = java.time.Instant.parse("2026-11-15T12:00:00Z");

        void advance(final java.time.Duration by) {
            now = now.plus(by);
        }

        @Override
        public java.time.ZoneId getZone() {
            return java.time.ZoneOffset.UTC;
        }

        @Override
        public java.time.Clock withZone(final java.time.ZoneId zone) {
            return this;
        }

        @Override
        public java.time.Instant instant() {
            return now;
        }
    }

    private final Hands clock = new Hands();

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    private ModelLists lists(final Function<String, Answer> answers) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    final String target = exchange.getRequestURI().toString();
                    asked.add(target);
                    final Map<String, String> seen = new java.util.LinkedHashMap<>();
                    exchange.getRequestHeaders()
                            .forEach((name, values) -> seen.put(name.toLowerCase(), values.get(0)));
                    headers.add(seen);
                    final Answer answer = answers.apply(target);
                    final byte[] payload = answer.body().getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    if (answer.status() / 100 == 3) {
                        // A redirect names where it points in its body here, as Location.
                        exchange.getResponseHeaders().set("Location", answer.body());
                    }
                    exchange.sendResponseHeaders(answer.status(), payload.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(payload);
                    }
                });
        server.start();
        final int port = server.getAddress().getPort();
        // The provider's https address, sent to the stand-in instead.
        return new ModelLists(
                ModelLists.http(
                        uri ->
                                URI.create(
                                        "http://127.0.0.1:"
                                                + port
                                                + uri.getRawPath()
                                                + (uri.getRawQuery() == null
                                                        ? ""
                                                        : "?" + uri.getRawQuery())),
                        LimitFactors.DEFAULT),
                clock,
                LimitFactors.DEFAULT);
    }

    private record Answer(int status, String body) {}

    private static Optional<Map<String, VaultEntry>> stored(final String provider) {
        return Optional.of(Map.of(provider, new VaultEntry(KEY, "api-key")));
    }

    @Test
    void anAnthropicListIsReadAcrossItsPagesWithTheKeyInItsHeader() throws Exception {
        final ModelLists lists =
                lists(
                        target ->
                                target.contains("after_id=claude-b")
                                        ? new Answer(
                                                200,
                                                """
                                                {"data":[{"type":"model","id":"claude-c","display_name":"Claude C"}],
                                                 "has_more":false,"first_id":"claude-c","last_id":"claude-c"}""")
                                        : new Answer(
                                                200,
                                                """
                                                {"data":[{"type":"model","id":"claude-a","display_name":"Claude A"},
                                                         {"type":"model","id":"claude-b","display_name":"Claude B"}],
                                                 "has_more":true,"first_id":"claude-a","last_id":"claude-b"}"""));

        final ModelLists.Listing listing = lists.list(ANTHROPIC, () -> stored("anthropic"), false);

        assertThat(listing.from()).as("the provider's own list answered").isEqualTo("provider");
        assertThat(listing.models())
                .as("every page is read, in the provider's order, with its labels")
                .extracting(ModelLists.Model::id, ModelLists.Model::label)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("claude-a", "Claude A"),
                        org.assertj.core.groups.Tuple.tuple("claude-b", "Claude B"),
                        org.assertj.core.groups.Tuple.tuple("claude-c", "Claude C"));
        assertThat(listing.models())
                .as("Anthropic's list says no efforts")
                .allSatisfy(model -> assertThat(model.efforts()).isNull());
        assertThat(asked)
                .as("the declared path, then the next page after the last id")
                .hasSize(2)
                .allSatisfy(target -> assertThat(target).startsWith("/v1/models"));
        assertThat(headers.getFirst())
                .as("the key goes in the provider's api-key header, as the broker puts it")
                .containsEntry("x-api-key", KEY)
                .as("beside the headers the provider declares for a key")
                .containsEntry("anthropic-version", "2023-06-01")
                .doesNotContainKey("authorization");
    }

    @Test
    void anOpenRouterListIsReadWithLabelsAndTheEffortsAModelTakes() throws Exception {
        final ModelLists lists =
                lists(
                        target ->
                                new Answer(
                                        200,
                                        """
                                        {"data":[
                                          {"id":"vendor/thinker","name":"Vendor: Thinker",
                                           "supported_parameters":["reasoning","include_reasoning","max_tokens"],
                                           "reasoning":{"supported_efforts":["high","medium","low"],
                                                        "default_effort":"medium","mandatory":false}},
                                          {"id":"vendor/plain","name":"Vendor: Plain",
                                           "supported_parameters":["max_tokens"]},
                                          {"id":"vendor/unlisted",
                                           "supported_parameters":["reasoning"]}],
                                         "total_count":3,"links":{"next":null}}"""));

        final ModelLists.Listing listing =
                lists.list(OPENROUTER, () -> stored("openrouter"), false);

        assertThat(asked).as("the path the provider declares").containsExactly("/api/v1/models");
        assertThat(headers.getFirst())
                .as("the key goes in the provider's header with its prefix")
                .containsEntry("authorization", "Bearer " + KEY);
        assertThat(listing.models())
                .extracting(ModelLists.Model::id, ModelLists.Model::label)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("vendor/thinker", "Vendor: Thinker"),
                        org.assertj.core.groups.Tuple.tuple("vendor/plain", "Vendor: Plain"),
                        org.assertj.core.groups.Tuple.tuple("vendor/unlisted", "vendor/unlisted"));
        assertThat(listing.models().get(0).efforts())
                .as("the efforts the provider lists for the model, in its order")
                .containsExactly("high", "medium", "low");
        assertThat(listing.models().get(1).efforts())
                .as("a model without reasoning has no efforts said")
                .isNull();
        assertThat(listing.models().get(2).efforts())
                .as("reasoning without listed levels leaves the efforts unsaid, never guessed")
                .isNull();
    }

    @Test
    void aRefusedCredentialIsAFailureThatNeverShowsTheKey() throws Exception {
        final ModelLists lists =
                lists(
                        target ->
                                new Answer(
                                        401,
                                        "{\"error\":{\"message\":\"invalid x-api-key "
                                                + KEY
                                                + "\"}}"));

        assertThatThrownBy(() -> lists.list(ANTHROPIC, () -> stored("anthropic"), false))
                .as("a refusal is a failure, never an empty list")
                .isInstanceOf(ModelLists.Failed.class)
                .hasMessageContaining("401")
                .satisfies(
                        failure ->
                                assertThat(failure.getMessage())
                                        .as("neither the key nor what the provider echoed")
                                        .doesNotContain(KEY)
                                        .doesNotContain("sk-"));
    }

    @Test
    void anAnswerThatDoesNotReadIsAFailure() throws Exception {
        final ModelLists lists = lists(target -> new Answer(200, "<html>maintenance</html>"));

        assertThatThrownBy(() -> lists.list(OPENROUTER, () -> stored("openrouter"), false))
                .isInstanceOf(ModelLists.Failed.class);
    }

    @Test
    void aSecondAskWithinTheHourIsAnsweredFromWhatWasKeptAndFreshAsksAgain() throws Exception {
        final ModelLists lists =
                lists(
                        target ->
                                new Answer(
                                        200,
                                        "{\"data\":[{\"id\":\"m\",\"name\":\"M\"}],\"links\":{\"next\":null}}"));
        final AtomicInteger vaultReads = new AtomicInteger();

        final ModelLists.Listing first =
                lists.list(
                        OPENROUTER,
                        () -> {
                            vaultReads.incrementAndGet();
                            return stored("openrouter");
                        },
                        false);
        final ModelLists.Listing second =
                lists.list(
                        OPENROUTER,
                        () -> {
                            vaultReads.incrementAndGet();
                            return stored("openrouter");
                        },
                        false);

        assertThat(asked).as("the second ask is answered from what was kept").hasSize(1);
        assertThat(second.readAt()).as("with the time it was read").isEqualTo(first.readAt());
        assertThat(vaultReads).as("and without opening the vault again").hasValue(1);

        clock.advance(java.time.Duration.ofMinutes(2));
        lists.list(OPENROUTER, () -> stored("openrouter"), true);

        assertThat(asked).as("fresh asks the provider again").hasSize(2);
    }

    private static Answer page(final String next) {
        return new Answer(
                200,
                "{\"data\":[{\"id\":\"m\"}],\"links\":{\"next\":"
                        + (next == null ? "null" : "\"" + next + "\"")
                        + "}}");
    }

    @Test
    void aNextPageOnAnotherSchemePortOrHostIsRefusedBeforeTheKeyTravels() throws Exception {
        for (final String next :
                List.of(
                        "http://openrouter.example/api/v1/models?page=2",
                        "https://openrouter.example:8443/api/v1/models?page=2",
                        "https://elsewhere.example/api/v1/models?page=2")) {
            asked.clear();
            final ModelLists lists = lists(target -> page(next));

            assertThatThrownBy(() -> lists.list(OPENROUTER, () -> stored("openrouter"), false))
                    .as("'%s' is not the provider's own origin", next)
                    .isInstanceOf(ModelLists.Failed.class)
                    .hasMessageContaining("another origin");
            assertThat(asked).as("no second request carries the key to %s", next).hasSize(1);
            server.stop(0);
            server = null;
        }
    }

    @Test
    void aNextPageOnTheProvidersOwnOriginIsFollowed() throws Exception {
        final ModelLists lists =
                lists(
                        target ->
                                target.contains("page=2")
                                        ? page(null)
                                        : page("https://openrouter.example/api/v1/models?page=2"));

        assertThat(lists.list(OPENROUTER, () -> stored("openrouter"), false).models()).hasSize(2);
        assertThat(asked).hasSize(2);
    }

    @Test
    void anHttpUpstreamNeverBecomesAProviderSoNothingIsSentInTheClear() {
        assertThatThrownBy(
                        () ->
                                provider(
                                        """
                                        name: plain
                                        upstream: http://plain.example
                                        dialects:
                                          openai: /v1
                                        models:
                                          path: /v1/models
                                          format: data
                                        """))
                .isInstanceOf(org.fuin.porelo.agent.api.AgentException.class)
                .hasMessageContaining("https");
    }

    @Test
    void aProviderThatPagesWithoutEndIsCutAtFiftyPages() throws Exception {
        final ModelLists lists = lists(target -> page("/api/v1/models?page=" + (asked.size() + 1)));

        assertThatThrownBy(() -> lists.list(OPENROUTER, () -> stored("openrouter"), false))
                .isInstanceOf(ModelLists.Failed.class)
                .hasMessageContaining("50 pages");
        assertThat(asked).as("fifty asks and no more").hasSize(50);
    }

    @Test
    void aRedirectIsAFailureAndNotFollowed() throws Exception {
        final ModelLists lists =
                lists(target -> target.contains("moved") ? page(null) : new Answer(302, "/moved"));

        assertThatThrownBy(() -> lists.list(OPENROUTER, () -> stored("openrouter"), false))
                .isInstanceOf(ModelLists.Failed.class)
                .hasMessageContaining("302");
        assertThat(asked).as("the redirect is not followed with the key").hasSize(1);
    }

    @Test
    void aFreshAskWithinAMinuteOfTheLastReadIsAnsweredFromWhatWasKept() throws Exception {
        final ModelLists lists = lists(target -> page(null));

        lists.list(OPENROUTER, () -> stored("openrouter"), false);
        clock.advance(java.time.Duration.ofSeconds(30));
        lists.list(OPENROUTER, () -> stored("openrouter"), true);

        assertThat(asked).as("a fresh ask half a minute later asks nothing").hasSize(1);

        clock.advance(java.time.Duration.ofSeconds(31));
        lists.list(OPENROUTER, () -> stored("openrouter"), true);

        assertThat(asked).as("a minute after the last read it asks again").hasSize(2);
    }

    @Test
    void theSpacingFollowsThePollingFactorAndAListingsTimeTheServicesFactor() throws Exception {
        final List<java.time.Duration> given = new CopyOnWriteArrayList<>();
        final ModelLists slow =
                new ModelLists(
                        (uri, sent, timeout) -> {
                            given.add(timeout);
                            return new ModelLists.Answer(200, page(null).body());
                        },
                        clock,
                        LimitFactors.parse("services=2,polling=2"));

        slow.list(OPENROUTER, () -> stored("openrouter"), false);
        clock.advance(ModelLists.SPACING.plusSeconds(1));
        slow.list(OPENROUTER, () -> stored("openrouter"), true);

        assertThat(given).as("a minute on, polling 2 asks nothing").hasSize(1);
        assertThat(given.getFirst())
                .as("services 2: the listing has twice its 20 s")
                .isGreaterThan(ModelLists.PATIENCE)
                .isLessThanOrEqualTo(ModelLists.PATIENCE.multipliedBy(2));

        clock.advance(ModelLists.SPACING);
        slow.list(OPENROUTER, () -> stored("openrouter"), true);

        assertThat(given).as("two minutes after the last read it asks again").hasSize(2);
    }

    @Test
    void aFailureIsAnsweredAgainForAMinuteWithoutAsking() throws Exception {
        final ModelLists lists = lists(target -> new Answer(500, "{}"));

        for (int call = 0; call < 3; call++) {
            final boolean fresh = call > 0;
            assertThatThrownBy(() -> lists.list(OPENROUTER, () -> stored("openrouter"), fresh))
                    .isInstanceOf(ModelLists.Failed.class)
                    .hasMessageContaining("500");
        }
        assertThat(asked).as("a loop of asks reaches the provider once a minute").hasSize(1);
    }

    @Test
    void callsAtOnceShareOneRead() throws Exception {
        final java.util.concurrent.CountDownLatch release =
                new java.util.concurrent.CountDownLatch(1);
        final ModelLists lists =
                lists(
                        target -> {
                            try {
                                release.await(10, java.util.concurrent.TimeUnit.SECONDS);
                            } catch (InterruptedException ex) {
                                Thread.currentThread().interrupt();
                            }
                            return page(null);
                        });
        final List<java.util.concurrent.CompletableFuture<ModelLists.Listing>> calls =
                new java.util.ArrayList<>();
        for (int call = 0; call < 5; call++) {
            final boolean fresh = call % 2 == 0;
            calls.add(
                    java.util.concurrent.CompletableFuture.supplyAsync(
                            () -> {
                                try {
                                    return lists.list(
                                            OPENROUTER, () -> stored("openrouter"), fresh);
                                } catch (ModelLists.Failed ex) {
                                    throw new IllegalStateException(ex);
                                }
                            },
                            java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()));
        }
        Thread.sleep(500);
        release.countDown();

        for (final var call : calls) {
            assertThat(call.get(20, java.util.concurrent.TimeUnit.SECONDS).models()).hasSize(1);
        }
        assertThat(asked).as("five calls at once, one read").hasSize(1);
    }

    @Test
    void aShutVaultIsAFailureThatSaysSo() throws Exception {
        final ModelLists lists = lists(target -> new Answer(200, "{\"data\":[]}"));

        assertThatThrownBy(() -> lists.list(OPENROUTER, Optional::empty, false))
                .isInstanceOf(ModelLists.Failed.class)
                .hasMessageContaining("vault is shut");
        assertThat(asked).as("nothing is asked without a credential").isEmpty();
    }

    @Test
    void aProviderWithoutAStoredCredentialOrAListAKeyReadsAnswersNone() throws Exception {
        final ModelLists lists = lists(target -> new Answer(200, "{\"data\":[]}"));

        assertThat(lists.list(OPENROUTER, () -> Optional.of(Map.of()), false).from())
                .as("nothing stored to ask with")
                .isEqualTo("none");
        assertThat(lists.list(SIGN_IN_ONLY, () -> stored("subscription"), false))
                .as("a provider that takes only a sign-in has no list a key reads")
                .satisfies(listing -> assertThat(listing.from()).isEqualTo("none"))
                .satisfies(listing -> assertThat(listing.models()).isEmpty());
        assertThat(asked).as("neither is asked").isEmpty();
    }
}
