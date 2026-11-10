package org.fuin.porelo.host;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ForgeGatePolicyTest {
    static final Set<String> HOSTS = Set.of("host.localhost:3000", "127.0.0.1:3000");

    static GateHttp.Request request(String method, String target, String... headers) {
        final List<GateHttp.Header> fields = new ArrayList<>();
        fields.add(new GateHttp.Header("Host", "host.localhost:3000"));
        for (int i = 0; i < headers.length; i += 2)
            fields.add(new GateHttp.Header(headers[i], headers[i + 1]));
        return new GateHttp.Request(method, target, "HTTP/1.1", new GateHttp.Headers(fields));
    }

    static ForgeGate gate(String mode) {
        return new ForgeGate(
                mode,
                () -> "person",
                () -> HOSTS,
                () -> {
                    throw new AssertionError("a refused request cannot reach the forge");
                });
    }

    private static void status(
            int expected, String mode, String method, String target, String... headers) {
        final var refusal = gate(mode).refusal(request(method, target, headers), "person");
        assertThat(refusal == null ? 200 : refusal.status())
                .as("%s %s %s", mode, method, target)
                .isEqualTo(expected);
    }

    @Test
    void authorizationNamedInAnyConnectionHeaderIsRefused() {
        status(
                400,
                "git",
                "GET",
                "/",
                "Authorization",
                "token fixture",
                "Connection",
                "keep-alive",
                "Connection",
                "authorization");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/user/settings/account",
                "/user/settings/applications?tab=tokens",
                "/user/settings/%61pplications",
                "/api/v1/users/person/tokens",
                "/api/v1/user",
                "/api/v1/admin/users/person",
                "/user/forgot_password",
                "/user/reset_password",
                "/user/change_password",
                "/admin/users/1"
            })
    void theBrowserCannotSetPasswordsOrMintTokens(String path) {
        status(403, "web", "POST", path);
    }

    @Test
    void noCallerGetsAForgeCredentialThroughAnySocket() {
        for (String[] route : MINTING)
            for (String mode : List.of("web", "git", "runner")) {
                status(403, mode, route[1], route[0]);
                status(403, mode, route[1], route[0], "Authorization", "token fixture");
            }
    }

    @Test
    void aPathReadDifferentlyByForgejoIsRefused() {
        for (String path : UNSAFE)
            for (String mode : List.of("web", "git", "runner"))
                status(400, mode, "POST", path, "Authorization", "token fixture");
    }

    @Test
    void theRunnersOwnProtocolIsTheRunnerSocketsAlone() {
        for (String mode : List.of("web", "git"))
            status(
                    403,
                    mode,
                    "POST",
                    "/api/actions/runner.v1.RunnerService/Register",
                    "Authorization",
                    "token fixture");
        status(200, "runner", "POST", "/api/actions/runner.v1.RunnerService/Register");
    }

    @Test
    void theBrowserGetsGitAndPackagesOnlyWithAToken() {
        for (String path : TOKEN_ONLY) {
            final var challenge = gate("web").refusal(request("POST", path), "person");
            assertThat(challenge)
                    .as("a client waits for its token challenge at %s", path)
                    .isNotNull();
            assertThat(challenge.challenge()).as("Basic challenge at %s", path).isTrue();
            status(401, "web", "POST", path);
            status(403, "web", "POST", path, "Sec-Fetch-Mode", "navigate");
            for (String mode : List.of("web", "git"))
                status(200, mode, "POST", path, "Authorization", "token fixture");
        }
    }

    @Test
    void aDecodedNewlineNeverEndsARouteMatch() {
        assertThat(ForgeRoutes.MINTING.matcher("/api/v1/users/alice/tokens/a\nb").matches())
                .as("minting pattern includes every suffix byte")
                .isTrue();
        assertThat(ForgeRoutes.TOKEN_EXCHANGE.matcher("/v2/token/a\nb").matches())
                .as("exchange pattern includes every suffix byte")
                .isTrue();
        for (String path :
                List.of(
                        "/api/packages/project/maven/a\nb",
                        "/project/repo.git/info/lfs/objects/a\nb"))
            assertThat(ForgeRoutes.TOKEN_ONLY.matcher(path).matches())
                    .as("token route includes every suffix byte")
                    .isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/user/avatar/head/72",
                "/user/avatar/objects/72",
                "/project/repo/src/branch/head"
            })
    void pagesTheBrowserShowsAreNoGitRoute(String path) {
        status(200, "web", "GET", path);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/api/packages/project/maven/org/example/lib/1.0-SNAPSHOT/maven-metadata.xml",
                "/project/repo.git/info/refs?service=git-upload-pack"
            })
    void theBrowserSocketChallengesAClientThatWaitsForOne(String path) {
        final var refusal = gate("web").refusal(request("GET", path), "person");
        assertThat(refusal.status()).as("a client can retry with its token").isEqualTo(401);
        assertThat(refusal.challenge()).as("the retry is a Basic challenge").isTrue();
    }

    @Test
    void noTokenIsExchangedForOneARevocationDoesNotEnd() {
        for (String path :
                List.of(
                        "/v2/token",
                        "/v2/token?scope=repository:project/image:push",
                        "/api/packages/person/conan/v2/users/authenticate"))
            for (String mode : List.of("web", "git", "runner")) {
                status(403, mode, "GET", path);
                status(403, mode, "GET", path, "Authorization", "token fixture");
            }
    }

    @Test
    void namesLikeARefusedRouteElsewhereStillPass() {
        for (String path : PASSING) {
            status(200, "web", "GET", path);
            status(200, "git", "GET", path, "Authorization", "token fixture");
        }
    }

    @Test
    void gitChallengesBeforeAnyUpstreamRequest() {
        final var refusal =
                gate("git").refusal(request("GET", "/", "Cookie", "session=fixture"), null);
        assertThat(refusal.status()).as("a cookie is no Git credential").isEqualTo(401);
        assertThat(refusal.challenge()).as("Git retries with Basic").isTrue();
    }

    @Test
    void ambiguousFramingIsRefusedBeforeForwarding() {
        status(400, "web", "POST", "/", "Transfer-Encoding", "chunked", "Content-Length", "0");
    }

    @Test
    void aConnectionHeaderCannotRemoveGitCredentials() {
        status(
                400,
                "git",
                "GET",
                "/",
                "Authorization",
                "token fixture",
                "Connection",
                "Authorization");
    }

    @Test
    void theRunnerProtocolPassesWithoutATokenButNothingElseDoes() {
        status(
                200,
                "runner",
                "POST",
                "/api/actions/ping.v1.PingService/Ping",
                "x-runner-uuid",
                "fixture",
                "Cookie",
                "s=fixture");
        for (String path :
                List.of(
                        "/api/v1/user",
                        "/owner/repo.git/info/refs",
                        "/api/actions/../v1/user",
                        "/api/actions/%2e%2e/v1/user",
                        "/api/actionsx/ping",
                        "/api/actions/")) {
            final var refusal = gate("runner").refusal(request("GET", path), null);
            assertThat(refusal.status())
                    .as("only the runner's canonical protocol is exempt: %s", path)
                    .isIn(400, 401);
        }
        status(403, "git", "POST", "/api/actions/ping.v1.PingService/Ping");
    }

    @Test
    void theRunnerAcceptsAJobsBearerTokenButGitDoesNot() {
        final String path = "/api/actions_pipeline/_apis/pipelines/workflows/1/artifacts";
        status(200, "runner", "GET", path, "Authorization", "Bearer job.fixture-token");
        status(401, "git", "GET", path, "Authorization", "Bearer job.fixture-token");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "evil.example:3000",
                "host.localhost:3001",
                "localhost:3000",
                "host.localhost",
                "127.0.0.1:3001",
                ""
            })
    void theBrowserSocketServesItsOwnSiteAlone(String host) {
        final var request =
                new GateHttp.Request(
                        "GET",
                        "/",
                        "HTTP/1.1",
                        new GateHttp.Headers(
                                host.isEmpty()
                                        ? List.of()
                                        : List.of(new GateHttp.Header("Host", host))));
        assertThat(gate("web").refusal(request, "person").status())
                .as("foreign or missing Host cannot sign in")
                .isEqualTo(403);
    }

    @Test
    void aForeignHostRefusalNamesOnlyTheConfiguredAddresses() throws Exception {
        for (String conf :
                List.of("name=forge.example\nport=3000\n", "name=other.example\nport=3100\n")) {
            final var hosts = ForgeRoutes.hosts(conf);
            final byte[] body =
                    ForgeRoutes.guidance(
                            new GateHttp.Headers(
                                    List.of(new GateHttp.Header("Host", "localhost:3000"))),
                            "GET",
                            "/",
                            hosts);
            assertThat(new String(body, java.nio.charset.StandardCharsets.UTF_8))
                    .as("guidance contains root's addresses alone")
                    .isEqualTo(
                            "Open this forge at "
                                    + String.join(
                                            " or ",
                                            hosts.stream()
                                                    .sorted()
                                                    .map(host -> "http://" + host + "/")
                                                    .toList())
                                    + ".\n");
            final var output = new java.io.ByteArrayOutputStream();
            GateHttp.reply(output, "GET", 403, false, body);
            assertThat(output.toString(java.nio.charset.StandardCharsets.UTF_8))
                    .as("Host guidance is a bounded text response which closes the connection")
                    .startsWith("HTTP/1.1 403 ")
                    .contains(
                            "Content-Type: text/plain; charset=utf-8\r\n",
                            "Content-Length: " + body.length + "\r\n",
                            "Connection: close\r\n")
                    .endsWith(new String(body, java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    @Test
    void otherRefusalsKeepTheirEmptyResponse() throws Exception {
        final List<GateHttp.Request> requests =
                List.of(
                        request("POST", "/", "Origin", "http://evil.example"),
                        request("POST", "/", "Sec-Fetch-Site", "cross-site"),
                        request("GET", "/user/settings/account"),
                        request("GET", "/owner/repo.git/info/refs", "Sec-Fetch-Site", "none"));
        for (var request : requests) {
            final var refusal = gate("web").refusal(request, "person");
            assertThat(refusal.body())
                    .as("authentication, route and cross-site refusals have no guidance")
                    .isEmpty();
            final var output = new java.io.ByteArrayOutputStream();
            GateHttp.reply(
                    output,
                    request.method(),
                    refusal.status(),
                    refusal.challenge(),
                    refusal.body());
            assertThat(output.toString(java.nio.charset.StandardCharsets.US_ASCII))
                    .as("a non-Host refusal has neither guidance nor an ambiguous body")
                    .contains("Content-Length: 0\r\n", "Connection: close\r\n")
                    .doesNotContain("Content-Type:")
                    .endsWith("\r\n\r\n");
            final var foreign =
                    new GateHttp.Request(
                            request.method(),
                            request.target(),
                            request.version(),
                            new GateHttp.Headers(
                                    java.util.stream.Stream.concat(
                                                    request.headers().entries().stream()
                                                            .filter(h -> !h.name().equals("Host")),
                                                    java.util.stream.Stream.of(
                                                            new GateHttp.Header(
                                                                    "Host", "localhost:3000")))
                                            .toList()));
            assertThat(gate("web").refusal(foreign, "person").body())
                    .as("foreign Host does not widen another refusal")
                    .isEmpty();
        }
        for (String mode : List.of("git", "runner"))
            assertThat(gate(mode).refusal(request("GET", "/user/settings/account"), null).body())
                    .as("token gates give no browser guidance")
                    .isEmpty();
        assertThat(gate("web").refusal(request("GET", "/"), null).body())
                .as("no peer login gives no guidance")
                .isEmpty();
        assertThat(
                        ForgeRoutes.guidance(
                                request("GET", "http://[").headers(), "GET", "http://[", HOSTS))
                .as("invalid target gives no guidance")
                .isEmpty();
    }

    @Test
    void anUnreadableForgeConfigurationKeepsTheHostRefusalEmpty() {
        final var gate =
                new ForgeGate(
                        "web",
                        () -> "person",
                        Set::of,
                        () -> {
                            throw new AssertionError("no upstream");
                        });
        final var refusal = gate.refusal(request("GET", "/"), "person");
        assertThat(refusal.status()).as("no configured site fails closed").isEqualTo(403);
        assertThat(refusal.body()).as("no addresses are invented").isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"host.localhost:3000", "HOST.localhost:3000", "127.0.0.1:3000"})
    void theBrowserSocketServesItsOwnSiteByEitherName(String host) {
        assertThat(
                        gate("web")
                                .refusal(
                                        new GateHttp.Request(
                                                "GET",
                                                "/",
                                                "HTTP/1.1",
                                                new GateHttp.Headers(
                                                        List.of(
                                                                new GateHttp.Header(
                                                                        "Host", host)))),
                                        "person"))
                .as("configured Host is case insensitive")
                .isNull();
    }

    @Test
    void aChangeFromAnotherSiteIsRefusedBeforeAnyUpstreamRequest() {
        for (String method : List.of("POST", "PUT", "PATCH", "DELETE", "OPTIONS"))
            for (String[] headers : FOREIGN) status(403, "web", method, "/", headers);
    }

    @Test
    void thePersonsOwnBrowserAndToolsStillChangeThings() {
        for (String method : List.of("POST", "DELETE")) {
            status(200, "web", method, "/");
            status(
                    200,
                    "web",
                    method,
                    "/",
                    "Origin",
                    "http://host.localhost:3000",
                    "Sec-Fetch-Site",
                    "same-origin");
            status(200, "web", method, "/", "Sec-Fetch-Site", "none");
            status(200, "web", method, "/", "Authorization", "token abc");
            final var headers =
                    new GateHttp.Headers(
                            List.of(
                                    new GateHttp.Header("Host", "127.0.0.1:3000"),
                                    new GateHttp.Header("Origin", "http://127.0.0.1:3000"),
                                    new GateHttp.Header("Sec-Fetch-Site", "same-origin")));
            assertThat(
                            gate("web")
                                    .refusal(
                                            new GateHttp.Request(method, "/", "HTTP/1.1", headers),
                                            "person"))
                    .as("loopback's own origin also passes")
                    .isNull();
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "name=host.localhost\n",
                "port=3000\n",
                "name=evil example\nport=3000\n",
                "name=host.localhost\nport=3000x\n"
            })
    void aForgeConfigurationThatCannotSayTheSiteRefusesEveryBrowserRequest(String conf) {
        assertThat(ForgeRoutes.hosts(conf)).as("only a complete site is admitted").isEmpty();
    }

    @Test
    void theGitSocketTakesAnyHostOrOrigin() {
        status(
                200,
                "git",
                "POST",
                "/",
                "Host",
                "localhost:3001",
                "Authorization",
                "token abc",
                "Origin",
                "http://evil.example");
    }

    @Test
    void aSecondHostOrSiteHeaderIsRefused() {
        status(403, "web", "GET", "/", "Host", "evil.example:3000");
        status(
                403,
                "web",
                "POST",
                "/",
                "Sec-Fetch-Site",
                "same-origin",
                "Sec-Fetch-Site",
                "cross-site");
    }

    @Test
    void theBrowsersOwnSpellingOfItsSitePasses() {
        final var headers =
                new GateHttp.Headers(
                        List.of(
                                new GateHttp.Header("Host", "HOST.localhost:3000"),
                                new GateHttp.Header("Origin", "http://host.localhost:3000"),
                                new GateHttp.Header("Sec-Fetch-Site", " Same-Origin")));
        assertThat(
                        gate("web")
                                .refusal(
                                        new GateHttp.Request("POST", "/", "HTTP/1.1", headers),
                                        "person"))
                .as("origin case and site whitespace follow the browser")
                .isNull();
    }

    @Test
    void aLinkFromAnotherSiteStillOpensAPage() {
        status(
                200,
                "web",
                "GET",
                "/",
                "Sec-Fetch-Site",
                "cross-site",
                "Origin",
                "http://evil.example");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "name=host.localhost\r\nport=3000\r\n",
                "name=evil example\rname=host.localhost\nport=3000\n",
                "name=evil.example\nname=host.localhost\nport=3000\n",
                "name=host.localhost\nport=3000\nstray\n",
                "stray"
            })
    void aForgeConfigurationNotAsTheRoleWritesItRefusesEveryBrowserRequest(String conf) {
        assertThat(ForgeRoutes.hosts(conf))
                .as("no duplicate key, stray line, CR or broken encoding")
                .isEmpty();
    }

    private static final String[][] MINTING = {
        {"/api/v1/users/person/tokens", "POST"},
        {"/api/v1/users/person/TOKENS", "POST"},
        {"/api/v1/users/person/tokens/7", "DELETE"},
        {"/login/oauth/authorize?client_id=fixture", "GET"},
        {"/login/oauth/grant", "POST"},
        {"/login/oauth/access_token", "POST"},
        {"/login/%6fauth/access_token", "POST"},
        {"/user/recover_account", "GET"},
        {"/user/recover_account/", "POST"},
        {"/user/settings/applications", "POST"},
        {"/api/v1/orgs/project/actions/runners/registration-token", "GET"},
        {"/api/v1/repos/project/repo/actions/runners/registration-token", "GET"},
        {"/api/v1/repos/project/repo/actions/runners", "GET"},
        {"/api/v1/admin/runners/registration-token", "GET"},
        {"/api/v1/user/actions/runners", "POST"},
        {"/api/v1/admin/actions/runners", "POST"},
        {"/api/v1/admin/users/person", "PATCH"},
        {"/org/project/settings/actions/runners", "GET"},
        {"/org/project/settings/actions/runners/new", "POST"},
        {"/org/project/settings/actions/runners/1/edit", "POST"},
        {"/project/repo/settings/actions/runners/reset_registration_token", "GET"},
        {"/admin/actions/runners/new", "GET"},
        {"/api/v1/admin/users", "POST"},
        {"/api/v1/user", "PATCH"},
        {"/user/forgot_password", "POST"},
        {"/user/reset_password", "POST"},
        {"/user/change_password", "POST"},
        {"/admin/users/1", "POST"},
        {"/api/v1/users/person%2fx/tokens", "POST"}
    };
    private static final String[] UNSAFE = {
        "/%2fuser/recover_account",
        "/api/v1/users/person/tokens/..%2ffixture",
        "/api/v1/users/person/x/%2e%2e/tokens",
        "/project/repo/./settings/actions/runners",
        "/api/v1/users/person%2f%2ftokens",
        "/api/v1/user%2f",
        "/project/repo%2f%2e%2e/settings",
        "/api/v1/users/person%2fx^/tokens",
        "/api/packages/project/generic/pkg/1.0/a%2f{b}",
        "/project/repo%2fx|/info/refs",
        "/project/repo/src/%zz",
        "/api/v1/users/alice/tokens/tok%0a",
        "/api/v1/users/alice/tokens/tok%0D",
        "/project/repo/info/lfs/objects/0123/file%0a",
        "/api/packages/project/maven/com/example/app%09/1.0/app-1.0.jar",
        "/v2/token%0a",
        "/api/v1/users/alice/tokens/tok%00",
        "/api/v1/users/alice/tokens/tok%7f"
    };
    private static final String[] TOKEN_ONLY = {
        "/v2/project/image/manifests/1",
        "/api/packages/project/generic/pkg/1.0/file",
        "/project/repo.git/info/refs?service=git-receive-pack",
        "/project/repo/git-upload-pack",
        "/project/repo.git/info/lfs/objects/batch",
        "/project/repo.git/HEAD",
        "/project/repo.git/objects/info/packs",
        "/project/repo%2fx/info/refs"
    };
    private static final String[] PASSING = {
        "/project/repo/src/branch/main/src/tokens/Store.java",
        "/project/repo/src/branch/main/src/runners/new",
        "/project/repo/src/branch/main/settings/actions/runners.md",
        "/api/v1/repos/project/repo/contents/src/tokens/Store.java",
        "/api/v1/repos/project/tokens",
        "/api/v1/repos/project/repo/branches/tokens",
        "/api/v1/user",
        "/api/v1/repos/project/repo/branches/porelo%2Ftask",
        "/project/repo/src/branch/main/a^b|c.txt"
    };
    private static final String[][] FOREIGN = {
        {"Origin", "http://evil.example"},
        {"Origin", "null"},
        {"Origin", "https://host.localhost:3000"},
        {"Origin", "http://127.0.0.1:3000"},
        {"Origin", "http://host.localhost:3000", "Origin", "http://evil.example"},
        {"Sec-Fetch-Site", "cross-site"},
        {"Sec-Fetch-Site", "same-site"},
        {"Origin", "http://host.localhost:3000", "Sec-Fetch-Site", "cross-site"}
    };
}
