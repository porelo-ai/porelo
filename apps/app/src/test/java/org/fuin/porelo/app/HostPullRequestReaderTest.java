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
            assertThatThrownBy(() -> reader.open(project))
                    .as("redirects refuse without a second request")
                    .isInstanceOf(HostPullRequestReader.Refused.class);
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
                            return ("["
                                            + new String(
                                                    pull("open", false), StandardCharsets.UTF_8)
                                            + "]")
                                    .getBytes(StandardCharsets.UTF_8);
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
                                        ? pull("closed", true)
                                        : merged.get()
                                                ? "[]".getBytes(StandardCharsets.UTF_8)
                                                : ("["
                                                                + new String(
                                                                        pull("open", false),
                                                                        StandardCharsets.UTF_8)
                                                                + "]")
                                                        .getBytes(StandardCharsets.UTF_8));
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
                                (" ".repeat(1024 * 1024) + "[]").getBytes(StandardCharsets.UTF_8));

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
                .hasMessageContaining("forge.example.person", "porelo host forge-token");
    }
}
