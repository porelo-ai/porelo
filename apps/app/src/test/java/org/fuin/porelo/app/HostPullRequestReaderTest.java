package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.PrintWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.core.project.ProjectReader;
import org.fuin.porelo.vault.VaultEntry;
import org.fuin.porelo.wire.Json;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HostPullRequestReaderTest {
    @Test
    void aRedirectNeverReceivesThePersonsToken() throws Exception {
        // A forge redirect must not carry the person's credential to a different HTTP endpoint.
        final var requests = new java.util.concurrent.atomic.AtomicInteger();
        final var server =
                com.sun.net.httpserver.HttpServer.create(
                        new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    requests.incrementAndGet();
                    exchange.getResponseHeaders()
                            .add(
                                    "Location",
                                    "http://127.0.0.1:"
                                            + server.getAddress().getPort()
                                            + "/stolen");
                    exchange.sendResponseHeaders(307, -1);
                    exchange.close();
                });
        server.start();
        try {
            Files.writeString(
                    conf,
                    "name=forge.example\naccount=forge\ngit_port="
                            + server.getAddress().getPort()
                            + "\n");
            final var reader =
                    new HostPullRequestReader(
                            context,
                            conf,
                            path ->
                                    Map.of(
                                            "uid",
                                            0,
                                            "mode",
                                            Files.isDirectory(path) ? 0040755 : 0100644));
            assertThat(reader.poll(project, List.of()).refused())
                    .as("a redirect refuses its repository without a second request")
                    .singleElement()
                    .satisfies(
                            refusal ->
                                    assertThat(refusal.status())
                                            .as("the redirect's own status")
                                            .isEqualTo(307));
            assertThat(requests.get()).as("no credential-bearing redirect request").isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void anHttpBodyOverTheSizeBoundIsRefused() throws Exception {
        // The actual HTTP transport must bound bytes before accumulating an outside response.
        final var server =
                com.sun.net.httpserver.HttpServer.create(
                        new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    final byte[] bytes =
                            (" ".repeat(1024 * 1024) + "[]").getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, bytes.length);
                    try (var out = exchange.getResponseBody()) {
                        out.write(bytes);
                    }
                });
        server.start();
        try {
            Files.writeString(
                    conf,
                    "name=forge.example\naccount=forge\ngit_port="
                            + server.getAddress().getPort()
                            + "\n");
            final var reader =
                    new HostPullRequestReader(
                            context,
                            conf,
                            path ->
                                    Map.of(
                                            "uid",
                                            0,
                                            "mode",
                                            Files.isDirectory(path) ? 0040755 : 0100644));
            assertThatThrownBy(() -> reader.open(project))
                    .as("an oversized HTTP body is a refusal")
                    .isInstanceOf(HostPullRequestReader.Refused.class)
                    .hasMessageContaining("bounded");
        } finally {
            server.stop(0);
        }
    }

    @TempDir Path dir;
    private PoreloContext context;
    private Project project;
    private Path conf;
    private String head;
    private static final String TOKEN = "a".repeat(40);
    private static final char[] PASSWORD = "test-only-password".toCharArray();

    @BeforeEach
    void fixture() throws Exception {
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                                    case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                    default -> null;
                                },
                        dir.resolve("home"));
        context =
                new PoreloContext(
                        new ProcessCommandRunner(),
                        new PoreloPaths(xdg, dir.resolve("bin")),
                        args -> 0);
        context.vault()
                .write(Map.of("forge.example.person", VaultEntry.of(TOKEN)), PASSWORD.clone());
        context.asking(prompt -> PASSWORD.clone())
                .openIfShut(true, true, new PrintWriter(Writer.nullWriter()));
        conf = dir.resolve("forge.conf");
        Files.writeString(conf, "name=forge.example\naccount=forge\ngit_port=3001\n");
        final Path file = dir.resolve("project.yml");
        Files.writeString(
                file,
                "project:\n  name: demo\n  security_class: guarded\n  central: https://central.example/demo.git\nimage:\n  from: ubuntu:24.04\n");
        final Path carried = dir.resolve("organization/projects");
        Files.createDirectories(carried);
        Files.writeString(
                carried.resolve("demo.json"),
                Json.write(
                        Map.of(
                                "project",
                                "demo",
                                "revision",
                                "",
                                "address",
                                "https://central.example/demo.git",
                                "signing_keys",
                                List.of())));
        project = ProjectReader.read(file);
        final Path mirror = GateSupport.mirror(context, project);
        Files.createDirectories(mirror);
        git(mirror, "init", "--bare");
        final String tree = git(mirror, "mktree");
        head =
                context.runner()
                        .runOrFail(
                                Command.of("git", "--git-dir=" + mirror, "commit-tree", tree)
                                        .withEnvironment(
                                                Map.of(
                                                        "GIT_CONFIG_GLOBAL",
                                                        "/dev/null",
                                                        "GIT_CONFIG_NOSYSTEM",
                                                        "1",
                                                        "GIT_AUTHOR_NAME",
                                                        "Writer",
                                                        "GIT_AUTHOR_EMAIL",
                                                        "writer@example.org",
                                                        "GIT_COMMITTER_NAME",
                                                        "Signer",
                                                        "GIT_COMMITTER_EMAIL",
                                                        "signer@example.org"))
                                        .withInput(
                                                "Reviewed work\n\nAssisted-by: Tool\nCo-Authored-By: Helper <help@example.org>\n"))
                        .trimmedOutput();
    }

    @AfterEach
    void forget() {
        try {
            if (context != null)
                new org.fuin.porelo.vault.KernelKeyring(context.paths().vault().vaultKeyringKey())
                        .forget();
        } finally {
            PoreloContext.forgetAsked();
        }
    }

    private String git(Path mirror, String... arguments) {
        final var command = new java.util.ArrayList<>(List.of("git", "--git-dir=" + mirror));
        command.addAll(List.of(arguments));
        return context.runner()
                .runOrFail(
                        Command.of(command)
                                .withInput("")
                                .withEnvironment(
                                        Map.of(
                                                "GIT_CONFIG_GLOBAL",
                                                "/dev/null",
                                                "GIT_CONFIG_NOSYSTEM",
                                                "1")))
                .trimmedOutput();
    }

    private HostPullRequestReader reader(HostPullRequestReader.Exchange forge) {
        return new HostPullRequestReader(
                context,
                conf,
                path -> Map.of("uid", 0, "mode", Files.isDirectory(path) ? 0040755 : 0100644),
                forge);
    }

    private byte[] pull(String state, boolean merged) {
        return Json.write(
                        Map.of(
                                "number",
                                7,
                                "title",
                                "Review\nthis\u001b[31m",
                                "head",
                                Map.of("sha", head),
                                "state",
                                state,
                                "merged",
                                merged,
                                "user",
                                Map.of("name", "FORGED MAKER")))
                .getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void anOpenReviewNamesOnlyTheMakersOfItsMirrorCommit() {
        // Forge-supplied maker names must not impersonate the people whose work is reviewed.
        final var reader =
                reader(
                        (address, token) -> {
                            assertThat(address.toString())
                                    .as("only the configured host forge receives the token")
                                    .isEqualTo(
                                            "http://127.0.0.1:3001/api/v1/repos/demo/demo/pulls?state=open&limit=50&page=1");
                            assertThat(token)
                                    .as("the person's stored token is used")
                                    .isEqualTo(TOKEN);
                            return openList();
                        });

        assertThat(reader.open(project))
                .as("one review with makers measured from git")
                .singleElement()
                .satisfies(
                        row -> {
                            assertThat(row.number()).as("the forge's review number").isEqualTo(7);
                            assertThat(row.head()).as("the exact commit reviewed").isEqualTo(head);
                            assertThat(row.author())
                                    .as("git's author")
                                    .isEqualTo("Writer <writer@example.org>");
                            assertThat(row.committer())
                                    .as("git's committer")
                                    .isEqualTo("Signer <signer@example.org>");
                            assertThat(row.trailers())
                                    .as("git's help trailers")
                                    .containsExactly(
                                            "Assisted-by: Tool",
                                            "Co-Authored-By: Helper <help@example.org>");
                            assertThat(row.title())
                                    .as("outside text is one safe shown line")
                                    .doesNotContain("\n", "\u001b");
                        });
    }

    @Test
    void aReviewThatMergesBetweenPollsEndsWithMerged() {
        // A vanished open row must not leave an already merged review awaiting approval forever.
        final AtomicBoolean merged = new AtomicBoolean();
        final var reader =
                reader(
                        (address, token) ->
                                address.getPath().endsWith("/7")
                                        ? ok(pull("closed", true))
                                        : merged.get() ? none() : openList());
        final var first = reader.open(project);
        merged.set(true);

        final var second = reader.poll(project, first);

        assertThat(second.open()).as("merged reviews leave the open list").isEmpty();
        assertThat(second.ended())
                .as("one confirmed terminal event")
                .singleElement()
                .satisfies(
                        row ->
                                assertThat(row.state())
                                        .as("merged is distinct from closed")
                                        .isEqualTo("merged"));
        assertThat(reader.poll(project, second.open()).ended())
                .as("the terminal event is not repeated")
                .isEmpty();
    }

    @Test
    void anOversizedForgeAnswerIsRefused() {
        // An untrusted forge must not exhaust daemon memory with a response of unbounded size.
        final var reader =
                reader(
                        (address, token) ->
                                ok(
                                        (" ".repeat(1024 * 1024) + "[]")
                                                .getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> reader.open(project))
                .as("the response is refused before JSON parsing")
                .hasMessageContaining("size bound");
    }

    @Test
    void aVaultWithoutThePersonsTokenAnswersATypedError() {
        // Missing person credentials must not silently fall back to another identity or mint a
        // token.
        context.vault().write(Map.of(), PASSWORD.clone());
        final var reader =
                reader(
                        (address, token) -> {
                            throw new AssertionError("no request without the person's token");
                        });

        assertThatThrownBy(() -> reader.open(project))
                .as("a named missing-token outcome with the available next action")
                .satisfies(
                        ex ->
                                assertThat(ex.getClass().getSimpleName())
                                        .as("typed missing-token outcome")
                                        .isEqualTo("MissingToken"))
                .hasMessageContainingAll("forge.example.person", "porelo host forge-token");
    }

    private Project threeRepositories(String carried) throws Exception {
        final Path file = dir.resolve("three.yml");
        Files.writeString(
                file,
                """
                project:
                  name: demo
                  security_class: guarded
                  central: https://central.example/demo.git
                image:
                  from: ubuntu:24.04
                repositories:
                  shop:
                    upstream: "https://upstream.example/shop.git"
                    central: "https://central.example/shop.git"
                  docs:
                    upstream: "https://upstream.example/docs.git"
                    central: "https://central.example/docs.git"
                """);
        Files.writeString(
                dir.resolve("organization/projects/demo.json"),
                Json.write(
                        Map.of(
                                "project",
                                "demo",
                                "revision",
                                "",
                                "address",
                                "https://central.example/demo.git",
                                "signing_keys",
                                List.of(),
                                "repositories",
                                List.of(carried.split(",")))));
        return ProjectReader.read(file);
    }

    private static HostPullRequestReader.Answer ok(byte[] body) {
        return new HostPullRequestReader.Answer(200, body);
    }

    private HostPullRequestReader.Answer openList() {
        return ok(
                ("[" + new String(pull("open", false), StandardCharsets.UTF_8) + "]")
                        .getBytes(StandardCharsets.UTF_8));
    }

    private static HostPullRequestReader.Answer none() {
        return ok("[]".getBytes(StandardCharsets.UTF_8));
    }

    private static HostPullRequestReader.Answer status(int status) {
        return new HostPullRequestReader.Answer(
                status,
                ("{\"message\":\"" + TOKEN + " forge words\"}").getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void aRefusedTokenRefusesTheWholeReadAndSaysWhereItWasAsked() {
        // A token the forge refuses is shared by every repository; the person must learn to renew
        // it.
        final var reader = reader((address, token) -> status(401));

        assertThatThrownBy(() -> reader.open(project))
                .as("a 401 refuses the read as the token's, with the status and the path")
                .isInstanceOfSatisfying(
                        HostPullRequestReader.Refused.class,
                        ex ->
                                assertThat(ex.reason())
                                        .as("the token's own reason")
                                        .isEqualTo("forge-token"))
                .hasMessageContainingAll(
                        "401", "/api/v1/repos/demo/demo/pulls", "porelo host forge-token")
                .satisfies(
                        ex ->
                                assertThat(ex.getMessage())
                                        .as(
                                                "neither the token, the host's address nor the forge's words")
                                        .doesNotContain(
                                                TOKEN, "127.0.0.1", "forge words", "page="));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "403,forge-access",
        "404,forge-access",
        "503,forge"
    })
    void oneRepositorysErrorRefusesThatRepositoryAloneAndSaysWhy(int code, String reason)
            throws Exception {
        // One repository's error must not hide the reviews of the others, nor leave which one
        // unsaid.
        final Project three = threeRepositories("demo,shop,docs");
        final var reader =
                reader(
                        (address, token) ->
                                address.getPath().contains("/demo/shop/")
                                        ? status(code)
                                        : address.getPath().contains("/demo/demo/")
                                                ? openList()
                                                : none());

        final var round = reader.poll(three, List.of());

        assertThat(round.open())
                .as("the other repositories' reviews are answered")
                .singleElement()
                .satisfies(
                        row -> assertThat(row.repository()).as("its repository").isEqualTo("demo"));
        assertThat(round.refused())
                .as("the refused repository is named with its reason, status and path")
                .singleElement()
                .satisfies(
                        refusal -> {
                            assertThat(refusal.repository())
                                    .as("which repository")
                                    .isEqualTo("shop");
                            assertThat(refusal.reason())
                                    .as("the cause by its status")
                                    .isEqualTo(reason);
                            assertThat(refusal.status())
                                    .as("the status the forge answered")
                                    .isEqualTo(code);
                            assertThat(refusal.path())
                                    .as("the path asked, without host or query")
                                    .isEqualTo("/api/v1/repos/demo/shop/pulls");
                            assertThat(refusal.message())
                                    .as(
                                            "the message says status and path, never secrets or forge words")
                                    .contains(String.valueOf(code), "/api/v1/repos/demo/shop/pulls")
                                    .doesNotContain(TOKEN, "127.0.0.1", "forge words");
                        });
        assertThat(round.asMap())
                .as("the refusal crosses the wire beside the open list")
                .containsKey("refused");
    }

    @Test
    void aReviewOfARefusedRepositoryIsKeptAndEndedOnceItAnswersAgain() {
        // A review merged while its repository could not be read must neither vanish nor end twice.
        final AtomicBoolean refused = new AtomicBoolean();
        final AtomicBoolean merged = new AtomicBoolean();
        final var reader =
                reader(
                        (address, token) ->
                                refused.get()
                                        ? status(503)
                                        : address.getPath().endsWith("/7")
                                                ? ok(pull("closed", true))
                                                : merged.get() ? none() : openList());
        final var first = reader.open(project);
        refused.set(true);
        merged.set(true);

        final var during = reader.poll(project, first);

        assertThat(during.open()).as("nothing is answered open while refused").isEmpty();
        assertThat(during.ended()).as("a refusal never ends a review").isEmpty();
        assertThat(during.kept()).as("the earlier row is kept for the next round").isEqualTo(first);
        refused.set(false);
        final var after =
                reader.poll(
                        project,
                        java.util.stream.Stream.concat(
                                        during.open().stream(), during.kept().stream())
                                .toList());
        assertThat(after.ended())
                .as("the merge is reported once the repository answers")
                .singleElement()
                .satisfies(row -> assertThat(row.state()).as("merged").isEqualTo("merged"));
        assertThat(after.refused()).as("no refusal once it answers").isEmpty();
    }

    @Test
    void aConfirmingReadThatFailsKeepsTheReviewInsteadOfEndingIt() {
        // A forge failure on one review's own page must not read as that review being closed.
        final AtomicBoolean gone = new AtomicBoolean();
        final var reader =
                reader(
                        (address, token) ->
                                address.getPath().endsWith("/7")
                                        ? status(503)
                                        : gone.get() ? none() : openList());
        final var first = reader.open(project);
        gone.set(true);

        final var round = reader.poll(project, first);

        assertThat(round.ended()).as("not ended").isEmpty();
        assertThat(round.kept()).as("kept").isEqualTo(first);
        assertThat(round.refused())
                .as("the repository is refused with the review's own path")
                .singleElement()
                .satisfies(
                        refusal ->
                                assertThat(refusal.path())
                                        .as("the confirming path")
                                        .isEqualTo("/api/v1/repos/demo/demo/pulls/7"));
    }

    @Test
    void aReviewOfARepositoryNoLongerCarriedRefusesThatRepositoryAlone() throws Exception {
        // Dropping one repository from the host must not stop the reviews of the others.
        final Project three = threeRepositories("demo,docs");
        final var old =
                new HostPullRequestReader.Pull(
                        "shop", 3, "Old", head, "Writer", "Signer", List.of(), "open");
        final var reader =
                reader(
                        (address, token) -> {
                            assertThat(address.getPath())
                                    .as("a repository the host does not carry is never asked")
                                    .doesNotContain("/demo/shop/");
                            return address.getPath().contains("/demo/demo/") ? openList() : none();
                        });

        final var round = reader.poll(three, List.of(old));

        assertThat(round.open()).as("the carried repositories answer").hasSize(1);
        assertThat(round.ended()).as("not ended").isEmpty();
        assertThat(round.kept()).as("kept").containsExactly(old);
        assertThat(round.refused())
                .as("named as no longer carried")
                .singleElement()
                .satisfies(
                        refusal -> {
                            assertThat(refusal.repository()).as("which").isEqualTo("shop");
                            assertThat(refusal.reason()).as("why").isEqualTo("not-carried");
                        });
    }

    @Test
    void keptRowsCountAgainstTheBoundOfWhatAWatchCarries() throws Exception {
        // Rows a watch carries to its next round must stay within the bound it checks on entry.
        final Project three = threeRepositories("demo,shop,docs");
        final List<HostPullRequestReader.Pull> earlier =
                java.util.stream.LongStream.rangeClosed(1, 500)
                        .mapToObj(
                                n ->
                                        new HostPullRequestReader.Pull(
                                                "shop", n, "Old", head, "Writer", "Signer",
                                                List.of(), "open"))
                        .toList();
        final var reader =
                reader(
                        (address, token) ->
                                address.getPath().contains("/demo/shop/")
                                        ? status(503)
                                        : address.getPath().contains("/demo/demo/")
                                                ? openList()
                                                : none());

        assertThatThrownBy(() -> reader.poll(three, earlier))
                .as("500 kept rows and one open row exceed the 500 a watch may carry")
                .isInstanceOfSatisfying(
                        HostPullRequestReader.Refused.class,
                        ex -> assertThat(ex.reason()).as("the bound's reason").isEqualTo("bounds"));
    }

    @Test
    void refusalsFollowTheCarriedRepositoriesWhateverOrderTheRowsCameIn() throws Exception {
        // A watch sends only changed answers; a refusal list in shifting order would read as
        // changed.
        final Project three = threeRepositories("demo,shop,docs");
        final var reader =
                reader(
                        (address, token) ->
                                address.getPath().contains("/demo/demo/")
                                        ? none()
                                        : address.getPath().endsWith("/pulls/4")
                                                ? status(503)
                                                : address.getPath().contains("/demo/shop/")
                                                        ? status(503)
                                                        : none());
        final var docs =
                new HostPullRequestReader.Pull(
                        "docs", 4, "Old", head, "Writer", "Signer", List.of(), "open");
        final var gone =
                new HostPullRequestReader.Pull(
                        "gone", 5, "Old", head, "Writer", "Signer", List.of(), "open");

        assertThat(reader.poll(three, List.of(gone, docs)).refused())
                .as("carried repositories first in their order, then those no longer carried")
                .extracting(HostPullRequestReader.Refusal::repository)
                .containsExactly("shop", "docs", "gone");
    }
}
