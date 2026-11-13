package org.fuin.porelo.organization.gittree;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import org.fuin.porelo.organization.IdentitySource;
import org.fuin.porelo.vault.PersonCard;
import org.fuin.porelo.vault.SigningKey;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Tests for {@link HttpForgeDirectory}, against a forge on loopback. */
class HttpForgeDirectoryTest {

    private static final String TOKEN = "tok_s3cret_never_shown";

    private static final String ONE = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOne";

    private static final String TWO = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAITwo";

    private static final String THREE = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIThree==";

    private final HttpClient client =
            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    private final Map<String, Reply> replies = new ConcurrentHashMap<>();

    private final List<Seen> seen = new CopyOnWriteArrayList<>();

    private HttpServer server;

    private URI base;

    /** What the forge answers on one path. */
    private record Reply(int status, byte[] body, long stallMillis, Map<String, String> headers) {
        Reply(int status, byte[] body, long stallMillis) {
            this(status, body, stallMillis, Map.of());
        }

        static Reply with(int status, String body, String header, String value) {
            return new Reply(
                    status, body.getBytes(StandardCharsets.UTF_8), 0, Map.of(header, value));
        }

        static Reply of(int status, String body) {
            return new Reply(status, body.getBytes(StandardCharsets.UTF_8), 0);
        }

        static Reply scoped(String scopes) {
            return with(200, "{\"login\":\"mia\"}", "X-OAuth-Scopes", scopes);
        }
    }

    /** One request the forge received. */
    private record Seen(String path, @Nullable String authorization) {}

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    final URI uri = exchange.getRequestURI();
                    final String path =
                            uri.getRawPath()
                                    + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
                    seen.add(
                            new Seen(path, exchange.getRequestHeaders().getFirst("Authorization")));
                    final Reply reply = replies.getOrDefault(path, Reply.of(500, "unexpected"));
                    reply.headers().forEach(exchange.getResponseHeaders()::add);
                    try (exchange;
                            OutputStream out = exchange.getResponseBody()) {
                        if (reply.body().length == 0) {
                            exchange.sendResponseHeaders(reply.status(), -1);
                            return;
                        }
                        exchange.sendResponseHeaders(reply.status(), reply.body().length);
                        final int half = reply.body().length / 2;
                        out.write(reply.body(), 0, half);
                        out.flush();
                        if (reply.stallMillis() > 0) {
                            Thread.sleep(reply.stallMillis());
                        }
                        out.write(reply.body(), half, reply.body().length - half);
                    } catch (IOException | InterruptedException ex) {
                        // The client gave up; nothing to answer.
                    }
                });
        // A stalled answer must not hold up the others, nor stopping the server.
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private HttpForgeDirectory forgejo(final @Nullable String token) {
        return new HttpForgeDirectory(base, null, token, client, Duration.ofSeconds(10));
    }

    private HttpForgeDirectory gitHub(final @Nullable String token) {
        return new HttpForgeDirectory(
                base, base.resolve("/api"), token, client, Duration.ofSeconds(10));
    }

    @Test
    void aForgeOtherThanGithubHasNoSigningKeysAFoundingCounts() {
        replies.put(
                "/api/v1/users/mia/ssh_signing_keys", Reply.of(200, "[{\"key\":\"" + ONE + "\"}]"));

        assertThat(new HttpForgeDirectory(base, null, client).foundingSigningKeys("mia"))
                .as("keys() asks only GitHub for them, so listing one here would refuse there")
                .contains(List.of());
    }

    @Test
    void aTokenTheForgeTakesSaysWhoseItIs() {
        replies.put("/api/v1/user", Reply.of(200, "{\"login\":\"mia\"}"));

        final HttpForgeDirectory.TokenAccount account =
                new HttpForgeDirectory(base, TOKEN, client).tokenAccount();

        assertThat(account)
                .as("accepted, and whose")
                .isEqualTo(
                        new HttpForgeDirectory.TokenAccount(
                                HttpForgeDirectory.TokenState.ACCEPTED, "mia"));
        assertThat(account.toString()).as("the token is no part of it").doesNotContain(TOKEN);
    }

    private static List<String> states(final List<HttpForgeDirectory.Reach> reaches) {
        return reaches.stream().map(each -> each.need() + "=" + each.state()).toList();
    }

    /** The reads a move makes on GitHub, each granted. */
    private void gitHubReadsGranted() {
        replies.put("/api/user/memberships/orgs/acme", Reply.of(200, "{\"role\":\"member\"}"));
        replies.put("/api/repos/acme/registry/keys?per_page=1", Reply.of(200, "[]"));
        replies.put("/api/repos/acme/registry/commits?per_page=1", Reply.of(409, "{}"));
    }

    @Test
    void aClassicGitHubTokensScopesSayEachNeedAndNameWhatIsLacking() {
        gitHubReadsGranted();
        replies.put("/api/user", Reply.scoped("read:org, workflow"));

        final List<HttpForgeDirectory.Reach> reach = gitHub(TOKEN).tokenReach("acme", "registry");

        assertThat(states(reach))
                .as("without repo it can neither make a repository nor push")
                .containsExactly(
                        "members=READY",
                        "administration=MISSING",
                        "contents=MISSING",
                        "workflows=READY");
        assertThat(reach.get(1).permission()).as("GitHub's own name for it").isEqualTo("repo");

        replies.put("/api/user", Reply.scoped("repo, write:org, workflow"));
        assertThat(states(gitHub(TOKEN).tokenReach("acme", "registry")))
                .as("write:org reads members too; an empty repository's 409 is a read")
                .containsOnly(
                        "members=READY",
                        "administration=READY",
                        "contents=READY",
                        "workflows=READY");
    }

    @Test
    void aClassicTokenWhoseScopesSufficeIsNotReadyWhereTheOrganizationRefusesIt() {
        // Scopes alone say nothing of an organization that refuses the token, as single sign-on
        // does, or of another account's token that may not see the repository.
        gitHubReadsGranted();
        replies.put("/api/user", Reply.scoped("repo, read:org, workflow"));
        replies.put("/api/repos/acme/registry/keys?per_page=1", Reply.of(404, "{}"));

        assertThat(states(gitHub(TOKEN).tokenReach("acme", "registry")))
                .contains("administration=CANNOT_TELL")
                .doesNotContain("administration=READY");
    }

    @Test
    void aFineGrainedTokensRefusedReadIsMissingAndAGrantedOneLeavesTheWritingUntold() {
        gitHubReadsGranted();
        replies.put("/api/user", Reply.of(200, "{\"login\":\"mia\"}"));
        replies.put("/api/repos/acme/registry/keys?per_page=1", Reply.of(403, "{}"));

        final List<HttpForgeDirectory.Reach> reach = gitHub(TOKEN).tokenReach("acme", "registry");

        assertThat(states(reach))
                .as("members is read by the founding's own call, and reading is all it asks")
                .containsExactly(
                        "members=READY",
                        "administration=MISSING",
                        "contents=CANNOT_TELL",
                        "workflows=CANNOT_TELL");
        assertThat(reach.get(1).permission()).isEqualTo("Administration: Read and write");
        assertThat(seen)
                .as("only reads were asked, each with the token")
                .allSatisfy(each -> assertThat(each.authorization()).isEqualTo("Bearer " + TOKEN));

        replies.put("/api/repos/acme/registry/commits?per_page=1", Reply.of(403, "{}"));
        assertThat(states(gitHub(TOKEN).tokenReach("acme", "registry")))
                .as("a refused read of the commits is no push either")
                .contains("contents=MISSING");
    }

    @Test
    void aFineGrainedTokenWithoutMembersReadIsMissingThoughThePublicMemberListAnswers() {
        // The public member list answers any token; only the membership the founding asks shows it.
        gitHubReadsGranted();
        replies.put("/api/user", Reply.of(200, "{\"login\":\"mia\"}"));
        replies.put("/api/orgs/acme/members?per_page=1", Reply.of(200, "[]"));
        replies.put("/api/user/memberships/orgs/acme", Reply.of(403, "{}"));

        assertThat(states(gitHub(TOKEN).tokenReach("acme", "registry")))
                .contains("members=MISSING");
    }

    @Test
    void aRateLimitsOrASingleSignOnsRefusalIsNoMissingPermission() {
        gitHubReadsGranted();
        replies.put("/api/user", Reply.of(200, "{\"login\":\"mia\"}"));
        replies.put(
                "/api/repos/acme/registry/keys?per_page=1",
                Reply.with(403, "{}", "X-RateLimit-Remaining", "0"));
        replies.put(
                "/api/user/memberships/orgs/acme",
                Reply.with(403, "{}", "X-GitHub-SSO", "required; url=https://example"));

        assertThat(states(gitHub(TOKEN).tokenReach("acme", "registry")))
                .as("a forge that refuses for now says nothing of the token's permissions")
                .contains("members=CANNOT_TELL", "administration=CANNOT_TELL");
    }

    @Test
    void aForgejoTokensReachIsToldByTheMovesOwnReadsAndItsScopeByName() {
        replies.put("/api/v1/user", Reply.of(200, "{\"login\":\"mia\"}"));
        replies.put(
                "/api/v1/users/mia/orgs/acme/permissions",
                Reply.of(
                        403,
                        "{\"message\":\"token does not have at least one of required scope(s):"
                                + " [read:user]\"}"));
        replies.put("/api/v1/orgs/acme", Reply.of(200, "{}"));
        replies.put("/api/v1/repos/acme/registry", Reply.of(403, "{\"message\":\"forbidden\"}"));

        final List<HttpForgeDirectory.Reach> reach = forgejo(TOKEN).tokenReach("acme", "registry");

        assertThat(states(reach))
                .as("a 403 naming no scope is not the token's lack")
                .containsExactly(
                        "members=MISSING",
                        "administration=CANNOT_TELL",
                        "contents=CANNOT_TELL",
                        "workflows=CANNOT_TELL");
        assertThat(reach.get(0).permission()).as("the scope Forgejo names").isEqualTo("read:user");
        assertThat(reach.get(1).permission())
                .as("making a repository in an organization takes its write")
                .isEqualTo("write:organization");
        assertThat(forgejo(null).tokenReach("acme", "registry"))
                .as("no token, nothing asked")
                .isEmpty();
    }

    @Test
    void aGitHubThatDoesNotTakeTheTokenCannotTellAnyReach() {
        replies.put("/api/user", Reply.of(502, ""));

        assertThat(states(gitHub(TOKEN).tokenReach("acme", "registry")))
                .as("a forge down is no missing permission")
                .containsOnly(
                        "members=CANNOT_TELL", "administration=CANNOT_TELL",
                        "contents=CANNOT_TELL", "workflows=CANNOT_TELL");
    }

    @Test
    void aTokenTheForgeRefusesIsToldFromOneNotReached() throws Exception {
        replies.put("/api/v1/user", Reply.of(401, "{}"));
        assertThat(new HttpForgeDirectory(base, TOKEN, client).tokenAccount().state())
                .as("401 is the forge's own word")
                .isEqualTo(HttpForgeDirectory.TokenState.REFUSED);

        replies.put("/api/v1/user", Reply.of(403, "{}"));
        assertThat(new HttpForgeDirectory(base, TOKEN, client).tokenAccount().state())
                .as("taken, and whose it is not said")
                .isEqualTo(HttpForgeDirectory.TokenState.CANNOT_SAY);

        assertThat(new HttpForgeDirectory(base, null, client).tokenAccount().state())
                .as("none to ask with")
                .isEqualTo(HttpForgeDirectory.TokenState.NONE);

        final int closed;
        try (ServerSocket socket = new ServerSocket(0)) {
            closed = socket.getLocalPort();
        }
        assertThat(
                        new HttpForgeDirectory(
                                        URI.create("http://127.0.0.1:" + closed), TOKEN, client)
                                .tokenAccount()
                                .state())
                .as("nobody answered")
                .isEqualTo(HttpForgeDirectory.TokenState.NOT_REACHED);
    }

    @Test
    void discovery_keeps_signing_and_authentication_keys_separate() {
        replies.put("/alice.keys", Reply.of(200, ONE));
        replies.put(
                "/api/users/alice/ssh_signing_keys", Reply.of(200, "[{\"key\":\"" + TWO + "\"}]"));
        final HttpForgeDirectory directory = gitHub(null);
        assertThat(directory.signingKeys("alice")).contains(List.of(TWO));
        assertThat(directory.authenticationKeys("alice")).contains(List.of(ONE));
        assertThat(directory.keys("alice")).contains(complete(ONE, TWO));
    }

    @Test
    void discovery_asks_the_token_account_and_the_public_profile() {
        replies.put("/api/user", Reply.of(200, "{\"login\":\"alice\"}"));
        replies.put("/api/users/alice", Reply.of(200, "{\"name\":\"Alice\",\"email\":null}"));
        final HttpForgeDirectory directory = gitHub(TOKEN);
        assertThat(directory.account())
                .contains(new HttpForgeDirectory.Profile("alice", null, null));
        assertThat(directory.profile("alice"))
                .contains(new HttpForgeDirectory.Profile(null, "Alice", null));
        assertThat(seen.getFirst().authorization() != null).isTrue();
        assertThat(seen.getLast().authorization()).isNull();
        seen.clear();
        assertThat(gitHub(null).account()).isEmpty();
        assertThat(seen).isEmpty();
    }

    @Test
    void keysKeepsOnlyEd25519KeysWithoutTheirCommentInOrderOnce() {

        replies.put(
                "/alice.keys",
                Reply.of(
                        200,
                        "ssh-rsa AAAAB3NzaC1yc2E alice@old\n"
                                + TWO
                                + " alice@laptop\r\n"
                                + "ecdsa-sha2-nistp256 AAAAE2VjZHNh\n"
                                + "\n"
                                + ONE
                                + "\n"
                                + TWO
                                + "\n"
                                + "ssh-ed25519 not-base64!\n"));

        // A comment or an RSA key compared as a key would never match a card's key line.
        assertThat(forgejo(null).keys("alice")).contains(complete(TWO, ONE));
    }

    @Test
    void keysOnGitHubMergesTheSigningKeys() {

        replies.put("/alice.keys", Reply.of(200, ONE + "\n"));
        replies.put(
                "/api/users/alice/ssh_signing_keys",
                Reply.of(
                        200,
                        "[{\"id\": 1, \"key\": \""
                                + ONE
                                + "\"}, {\"id\": 2, \"key\": \""
                                + THREE
                                + " signing\"}, {\"id\": 3}, {\"key\": \"ssh-rsa AAAAB3\"}]"));

        // A person who signs with a key GitHub lists only as a signing key is still vouched for.
        assertThat(gitHub(null).keys("alice")).contains(complete(ONE, THREE));
    }

    @Test
    void keysOfALoginTheForgeDoesNotKnowAreNoneWithoutAskingTheApi() {

        replies.put("/ghost.keys", Reply.of(404, "Not Found"));

        // A definite answer: the card is then refused, not left first-seen forever. Asking the
        // API as well would spend its rate limit, which a commit of many cards could exhaust.
        assertThat(forgejo(null).keys("ghost")).contains(complete());
        assertThat(gitHub(null).keys("ghost")).contains(complete());
        assertThat(seen).extracting(Seen::path).containsOnly("/ghost.keys");
    }

    @Test
    void keysOnGitHubWithoutSigningKeysAreTheKeysPageComplete() {

        replies.put("/alice.keys", Reply.of(200, ONE + "\n"));
        replies.put("/api/users/alice/ssh_signing_keys", Reply.of(200, "[]"));

        assertThat(gitHub(null).keys("alice")).contains(complete(ONE));
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 502, 503, 429, 401, 403, 302})
    void keysCannotSayWhenTheForgeAnswersNeitherListNorNotFound(final int status) {

        replies.put("/alice.keys", Reply.of(status, ONE + "\n"));

        // A forge that is down or rate-limited must not read as "lists no key", which refuses.
        assertThat(forgejo(null).keys("alice")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 403, 500})
    void keysOnGitHubAreTheKeysPageIncompleteWhenTheSigningKeysDoNotCome(final int status) {

        replies.put("/alice.keys", Reply.of(200, ONE + "\n"));
        replies.put("/api/users/alice/ssh_signing_keys", Reply.of(status, "rate limited"));

        // The page still vouches for the keys it lists, so a card with one of them stays trusted
        // when a rate limit spent by others keeps the signing keys back; a key it lacks may be a
        // signing key, so the list is marked incomplete and proves no key missing.
        assertThat(gitHub(null).keys("alice"))
                .contains(new IdentitySource.Keys(List.of(ONE), false));
    }

    @Test
    void keysAskedAboutAKeyThePageListsLeaveGitHubsApiUnasked() {

        final String listed = PersonCard.keyLine(SigningKey.generate("listed"));
        final String other = PersonCard.keyLine(SigningKey.generate("other"));
        replies.put("/alice.keys", Reply.of(200, listed + " alice@laptop\n"));
        replies.put("/api/users/alice/ssh_signing_keys", Reply.of(429, "rate limited"));

        // The API's rate limit is anybody's to spend; a key the page lists must not depend on it.
        assertThat(gitHub(null).keys("alice", PersonCard.fingerprint(listed)))
                .contains(new IdentitySource.Keys(List.of(listed), false));
        assertThat(seen).extracting(Seen::path).containsExactly("/alice.keys");
        assertThat(gitHub(null).keys("alice", PersonCard.fingerprint(other)))
                .as("a key the page lacks is asked of the API")
                .contains(new IdentitySource.Keys(List.of(listed), false));
        assertThat(seen)
                .extracting(Seen::path)
                .containsExactly("/alice.keys", "/alice.keys", "/api/users/alice/ssh_signing_keys");
    }

    @Test
    void keysOnGitHubAreTheKeysPageIncompleteWhenTheSigningKeysDoNotParse() {

        replies.put("/alice.keys", Reply.of(200, ONE + "\n"));
        replies.put("/api/users/alice/ssh_signing_keys", Reply.of(200, "[{\"key\": "));

        assertThat(gitHub(null).keys("alice"))
                .contains(new IdentitySource.Keys(List.of(ONE), false));
    }

    @Test
    void keysCannotSayWhenTheForgeCannotBeReached() throws IOException {

        final int closed;
        try (ServerSocket socket = new ServerSocket(0)) {
            closed = socket.getLocalPort();
        }
        final HttpForgeDirectory unreachable =
                new HttpForgeDirectory(
                        URI.create("http://127.0.0.1:" + closed),
                        null,
                        null,
                        client,
                        Duration.ofSeconds(10));

        // Being offline is the ordinary case at a first read, not a refusal.
        assertThat(unreachable.keys("alice")).isEmpty();
    }

    @Test
    void keysCannotSayWhenTheAnswerStallsPastTheTimeout() {

        replies.put(
                "/alice.keys",
                new Reply(200, (ONE + "\n").getBytes(StandardCharsets.UTF_8), 3_000));
        final HttpForgeDirectory impatient =
                new HttpForgeDirectory(base, null, null, client, Duration.ofMillis(300));

        // A forge that sends its headers and then dribbles would otherwise hold the read forever.
        final long started = System.nanoTime();
        assertThat(impatient.keys("alice")).isEmpty();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(4));
    }

    @Test
    void keysReadsAnAnswerOfExactlyTheBound() {

        final byte[] body = new byte[HttpForgeDirectory.LIMIT];
        java.util.Arrays.fill(body, (byte) '\n');
        final byte[] line = (ONE + "\n").getBytes(StandardCharsets.UTF_8);
        System.arraycopy(line, 0, body, 0, line.length);
        replies.put("/alice.keys", new Reply(200, body, 0));

        assertThat(forgejo(null).keys("alice")).contains(complete(ONE));
    }

    @Test
    void keysCannotSayWhenTheAnswerIsOverTheBound() {

        final byte[] body = new byte[HttpForgeDirectory.LIMIT + 1];
        java.util.Arrays.fill(body, (byte) '\n');
        final byte[] line = (ONE + "\n").getBytes(StandardCharsets.UTF_8);
        System.arraycopy(line, 0, body, 0, line.length);
        replies.put("/alice.keys", new Reply(200, body, 0));

        // A forge, or whoever answers in its place, must not fill the reader's memory.
        assertThat(forgejo(null).keys("alice")).isEmpty();
    }

    @Test
    void keysNeverSendsTheToken() {

        replies.put("/alice.keys", Reply.of(200, ONE + "\n"));
        replies.put("/api/users/alice/ssh_signing_keys", Reply.of(200, "[]"));

        gitHub(TOKEN).keys("alice");
        forgejo(TOKEN).keys("alice");

        // Public lists need no token; sending it would hand it to whatever serves them.
        assertThat(seen).hasSize(3).allSatisfy(each -> assertThat(each.authorization()).isNull());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "..",
                "a..b",
                ".hidden",
                "a/b",
                "../admin",
                "a?b",
                "a#b",
                "a%2fb",
                "a b",
                "a\tb",
                "a\nb",
                "a\0b",
                "a\\b",
                "a:b",
                "a@b"
            })
    void refusesAHostileLoginOrOrganisationBeforeAsking(final String hostile) {

        final HttpForgeDirectory forge = gitHub(TOKEN);

        // A name that leaves its path segment would ask, with the token, a path the forge never
        // meant: another organisation's members, or an endpoint that changes something.
        assertThatThrownBy(() -> forge.keys(hostile))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageEndingWith("is not a forge login");
        assertThatThrownBy(() -> forge.owns(hostile, "alice"))
                .isInstanceOf(IllegalArgumentException.class)
                .message()
                .doesNotContain(TOKEN)
                .doesNotContainPattern("\\p{Cntrl}");
        assertThatThrownBy(() -> forge.owns("acme", hostile))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(seen).isEmpty();
    }

    @Test
    void ownsOnGitHubIsAnActiveAdminMembership() {

        replies.put(
                "/api/orgs/acme/memberships/alice",
                Reply.of(200, "{\"role\": \"admin\", \"state\": \"active\"}"));
        replies.put(
                "/api/orgs/acme/memberships/bob",
                Reply.of(200, "{\"role\": \"member\", \"state\": \"active\"}"));
        replies.put(
                "/api/orgs/acme/memberships/carol",
                Reply.of(200, "{\"role\": \"admin\", \"state\": \"pending\"}"));
        final HttpForgeDirectory forge = gitHub(TOKEN);

        // An invited admin who has not accepted, or a plain member, could otherwise seat owners.
        assertThat(forge.owns("acme", "alice")).contains(true);
        assertThat(forge.owns("acme", "bob")).contains(false);
        assertThat(forge.owns("acme", "carol")).contains(false);
        assertThat(seen)
                .allSatisfy(each -> assertThat(each.authorization()).isEqualTo("Bearer " + TOKEN));
    }

    @Test
    void ownsOnGitHubIsANoWhenAnAdminsTokenFindsNoMembership() {

        replies.put(
                "/api/user/memberships/orgs/acme",
                Reply.of(200, "{\"role\": \"admin\", \"state\": \"active\"}"));
        replies.put("/api/orgs/acme/memberships/outsider", Reply.of(404, "Not Found"));

        // An admin sees every membership, so the 404 is a plain no: a founding signed by somebody
        // outside the organisation must not stay undecided for good.
        assertThat(gitHub(TOKEN).owns("acme", "outsider")).contains(false);
        assertThat(seen)
                .allSatisfy(each -> assertThat(each.authorization()).isEqualTo("Bearer " + TOKEN));
    }

    @Test
    void ownsOnGitHubIsANoWhenTheOrganisationsInstallationTokenFindsNoMembership() {

        // An installation token has no account: GitHub answers /user with 403.
        replies.put("/api/user/memberships/orgs/acme", Reply.of(403, "Forbidden"));
        replies.put(
                "/api/installation/repositories?per_page=1",
                Reply.of(
                        200,
                        "{\"repositories\": [{\"owner\": {\"login\": \"Acme\","
                                + " \"type\": \"Organization\"}}]}"));
        replies.put("/api/orgs/acme/memberships/outsider", Reply.of(404, "Not Found"));
        replies.put(
                "/api/orgs/acme/memberships/alice",
                Reply.of(200, "{\"role\": \"admin\", \"state\": \"active\"}"));

        // The organisation's own installation, granted Members: read, sees every membership.
        assertThat(gitHub(TOKEN).owns("acme", "outsider")).contains(false);
        assertThat(gitHub(TOKEN).owns("acme", "alice")).contains(true);
        assertThat(seen)
                .allSatisfy(each -> assertThat(each.authorization()).isEqualTo("Bearer " + TOKEN));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"repositories\": [{\"owner\": {\"login\": \"elsewhere\"}}]}",
                "{\"repositories\": [{\"owner\": {\"login\": \"acme\", \"type\": \"User\"}}]}",
                "{\"repositories\": []}",
                "{\"repositories\": [{}]}",
                "not json"
            })
    void ownsOnGitHubCannotSayFromA404WhenTheInstallationIsAnotherOrganisations(
            final String installation) {

        replies.put("/api/user/memberships/orgs/acme", Reply.of(403, "Forbidden"));
        replies.put("/api/installation/repositories?per_page=1", Reply.of(200, installation));
        replies.put("/api/orgs/acme/memberships/outsider", Reply.of(404, "Not Found"));

        // Another organisation's installation sees none of acme's memberships.
        assertThat(gitHub(TOKEN).owns("acme", "outsider")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"role\": \"member\", \"state\": \"active\"}",
                "{\"role\": \"admin\", \"state\": \"pending\"}",
                "not json",
                ""
            })
    void ownsOnGitHubCannotSayFromA404WhenTheTokenIsNoActiveAdmin(final String own) {

        if (!own.isEmpty()) {
            replies.put("/api/user/memberships/orgs/acme", Reply.of(200, own));
        }
        replies.put("/api/orgs/acme/memberships/outsider", Reply.of(404, "Not Found"));

        // A member's token gets a 404 for memberships it may not see: no answer about the login.
        assertThat(gitHub(TOKEN).owns("acme", "outsider")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 503, 429, 401, 403, 404})
    void ownsOnGitHubCannotSayWhenTheForgeAnswersNeitherYesNorNo(final int status) {

        replies.put("/api/orgs/acme/memberships/alice", Reply.of(status, "{\"role\": \"admin\"}"));

        // A 404 is also what a token answers that may not see a private organisation: a no
        // would refuse a real owner's founding for what the reader's token cannot see.
        assertThat(gitHub(TOKEN).owns("acme", "alice")).isEmpty();
    }

    @Test
    void aRateLimitSaysSoAndAnAnswerAfterItSaysNothing() {

        replies.put("/alice.keys", Reply.with(403, "secret words", "X-RateLimit-Remaining", "0"));
        replies.put("/bob.keys", Reply.of(429, "slow down"));
        replies.put("/carol.keys", Reply.of(200, ONE + "\n"));
        final HttpForgeDirectory forge = forgejo(null);

        assertThat(forge.keys("alice")).isEmpty();
        assertThat(forge.cannotSay()).contains("rate limited");
        assertThat(forge.keys("bob")).isEmpty();
        assertThat(forge.cannotSay()).contains("rate limited");
        assertThat(forge.keys("carol")).isPresent();
        assertThat(forge.cannotSay()).as("the last question was answered").isEmpty();
    }

    @Test
    void eachWayAKeyListCannotBeSaidHasItsOwnKind() {

        replies.put("/alice.keys", Reply.of(500, "boom"));
        replies.put("/bob.keys", Reply.of(302, ""));
        final byte[] over = new byte[HttpForgeDirectory.LIMIT + 1];
        java.util.Arrays.fill(over, (byte) '\n');
        replies.put("/carol.keys", new Reply(200, over, 0));
        final HttpForgeDirectory forge = forgejo(null);

        assertThat(forge.keys("alice")).isEmpty();
        assertThat(forge.cannotSay()).contains("another status");
        assertThat(forge.keys("bob")).isEmpty();
        assertThat(forge.cannotSay()).as("a redirect is not followed").contains("another status");
        assertThat(forge.keys("carol")).isEmpty();
        assertThat(forge.cannotSay()).contains("an answer that does not read");
        assertThat(forge.keys("nobody-answers-this")).isEmpty();
        assertThat(forge.cannotSay()).contains("another status");
    }

    @Test
    void aForgeOutOfReachSaysNoAnswer() throws IOException {

        final int closed;
        try (ServerSocket socket = new ServerSocket(0)) {
            closed = socket.getLocalPort();
        }
        final HttpForgeDirectory unreachable =
                new HttpForgeDirectory(
                        URI.create("http://127.0.0.1:" + closed),
                        null,
                        null,
                        client,
                        Duration.ofSeconds(10));

        assertThat(unreachable.keys("alice")).isEmpty();
        assertThat(unreachable.cannotSay()).contains("no answer");
    }

    @Test
    void anOwnershipWithoutATokenOrThatTheTokenCannotSeeSaysWhich() {

        replies.put("/api/orgs/acme/memberships/outsider", Reply.of(404, "Not Found"));
        replies.put(
                "/api/orgs/acme/memberships/alice",
                Reply.with(403, "{}", "X-RateLimit-Remaining", "0"));

        final HttpForgeDirectory none = gitHub(null);
        assertThat(none.owns("acme", "alice")).isEmpty();
        assertThat(none.cannotSay()).contains("no token for the organisation's members");

        final HttpForgeDirectory member = gitHub(TOKEN);
        assertThat(member.owns("acme", "outsider")).isEmpty();
        assertThat(member.cannotSay()).contains("the token cannot see the organisation's members");
        assertThat(member.owns("acme", "alice")).isEmpty();
        assertThat(member.cannotSay()).contains("rate limited");

        replies.put(
                "/api/user/memberships/orgs/acme",
                Reply.with(403, "{}", "X-RateLimit-Remaining", "0"));
        assertThat(member.owns("acme", "outsider")).isEmpty();
        assertThat(member.cannotSay())
                .as("a 404 after the token's own membership was rate limited")
                .contains("rate limited");

        replies.put("/api/v1/users/alice/orgs/acme/permissions", Reply.of(403, "Forbidden"));
        final HttpForgeDirectory forgejo = forgejo(TOKEN);
        assertThat(forgejo.owns("acme", "alice")).isEmpty();
        assertThat(forgejo.cannotSay()).contains("the token cannot see the organisation's members");
    }

    @Test
    void ownsCannotSayWithoutAToken() {

        // Without a token GitHub hides private memberships: a no would then be wrong.
        assertThat(gitHub(null).owns("acme", "alice")).isEmpty();
        assertThat(forgejo(null).owns("acme", "alice")).isEmpty();
        assertThat(seen).isEmpty();
    }

    @Test
    void ownsOnForgejoIsTheLoginsOwnerPermissionInTheOrganisation() {

        replies.put(
                "/api/v1/users/alice/orgs/acme/permissions",
                Reply.of(200, "{\"is_owner\": true, \"is_admin\": true, \"can_read\": true}"));
        replies.put(
                "/api/v1/users/bob/orgs/acme/permissions",
                Reply.of(200, "{\"is_owner\": false, \"is_admin\": true, \"can_read\": true}"));
        final HttpForgeDirectory forge = forgejo(TOKEN);

        // An admin of the organisation who is no owner could otherwise seat owners; a member's
        // token answers this as well, so a founding does not wait for an owner's token.
        assertThat(forge.owns("acme", "alice")).contains(true);
        assertThat(forge.owns("acme", "bob")).contains(false);
        assertThat(seen)
                .allSatisfy(each -> assertThat(each.authorization()).isEqualTo("token " + TOKEN));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "404:",
                "403:",
                "500:",
                "429:",
                "200:{\"is_admin\": true}",
                "200:{\"is_owner\": \"yes\"}",
                "200:[true]",
                "200:not json"
            })
    void ownsOnForgejoCannotSayWithoutAPlainOwnerPermission(final String reply) {

        final int colon = reply.indexOf(':');
        replies.put(
                "/api/v1/users/alice/orgs/acme/permissions",
                Reply.of(Integer.parseInt(reply.substring(0, colon)), reply.substring(colon + 1)));

        // A token not let into a private organisation gets a 404 or 403: no answer about alice.
        assertThat(forgejo(TOKEN).owns("acme", "alice")).isEmpty();
    }

    @Test
    void neverShowsTheToken() {

        // Tokens reach logs and terminals through messages and toString.
        assertThat(gitHub(TOKEN).toString()).doesNotContain(TOKEN).contains("token set");
        assertThatThrownBy(
                        () ->
                                new HttpForgeDirectory(
                                        base,
                                        null,
                                        TOKEN + "\r\nX-Evil: 1",
                                        client,
                                        Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .message()
                .doesNotContain(TOKEN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"NORMAL", "ALWAYS"})
    void refusesAClientThatFollowsRedirects(final String redirect) {

        final HttpClient following =
                HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.valueOf(redirect))
                        .build();

        // A redirect would let another host, or plain HTTP, answer with the keys it likes.
        assertThatThrownBy(
                        () ->
                                new HttpForgeDirectory(
                                        base, null, TOKEN, following, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .message()
                .doesNotContain(TOKEN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"bad\u009b31m", "bad\u202eevil", "bad\u2028line", "bad\u2066x"})
    void namesAHostileLoginWithoutTerminalControls(final String hostile) {

        // A C1 escape, a bidirectional override or a line separator would reach the terminal.
        assertThatThrownBy(() -> forgejo(null).keys(hostile))
                .isInstanceOf(IllegalArgumentException.class)
                .message()
                .startsWith("'bad?")
                .doesNotContainPattern(
                        "[\\u0080-\\u009f\\u2028\\u2029\\u202a-\\u202e\\u2066-\\u2069]");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "http://forge.example",
                "http://localhost:3000",
                "ftp://forge.example",
                "https://user:pw@forge.example",
                "https://forge.example/?x=1",
                "https://forge.example/#x",
                "forge.example"
            })
    void refusesAForgeAddressThatIsNotHttps(final String address) {

        // Over plain HTTP the token and the key lists could be read or replaced on the way.
        assertThatThrownBy(() -> new HttpForgeDirectory(URI.create(address), TOKEN, client))
                .isInstanceOf(IllegalArgumentException.class)
                .message()
                .doesNotContain(TOKEN);
    }

    private static IdentitySource.Keys complete(final String... keys) {
        return new IdentitySource.Keys(List.of(keys), true);
    }

    @Test
    void tellsGitHubFromForgejoByTheHost() {

        assertThat(new HttpForgeDirectory(URI.create("https://github.com/"), null, client))
                .hasToString(
                        "HttpForgeDirectory[https://github.com, github https://api.github.com, no token]");
        assertThat(new HttpForgeDirectory(URI.create("https://codeberg.org"), null, client))
                .hasToString("HttpForgeDirectory[https://codeberg.org, forgejo, no token]");
        assertThat(new HttpForgeDirectory(URI.create("https://forge.example/git/"), null, client))
                .hasToString("HttpForgeDirectory[https://forge.example/git, forgejo, no token]");
    }
}
