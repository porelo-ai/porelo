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
                            (" ".repeat(32 * 1024 * 1024) + "[]").getBytes(StandardCharsets.UTF_8);
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
        conf = context.paths().hostDirectory().resolve("forge.conf");
        Files.createDirectories(conf.getParent());
        Files.writeString(conf, "name=forge.example\naccount=forge\ngit_port=3001\n");
        final Path file = dir.resolve("project.yml");
        Files.writeString(
                file,
                "project:\n  name: demo\n  security_class: guarded\n  central: https://central.example/demo.git\nimage:\n  from: ubuntu:24.04\n");
        final Path carried = conf.resolveSibling("organization/projects");
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
        // The host's published carry state: the project carried, its repositories not named.
        hostStatus(
                "{\"project\":\"demo\",\"read_at\":\"2026-11-09T10:00:00Z\",\"repositories\":[]}");
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

    /**
     * A reader of a forge that answers its one list on the first page and an empty second page, as
     * a real forge ends a list the reader walks to its first empty page.
     */
    private HostPullRequestReader reader(HostPullRequestReader.Exchange forge) {
        return pagingReader(
                (address, token) ->
                        address.getPath().endsWith("/pulls")
                                        && address.getQuery() != null
                                        && !address.getQuery().endsWith("page=1")
                                ? none()
                                : forge.get(address, token));
    }

    /** A reader of a forge that answers each page itself. */
    private HostPullRequestReader pagingReader(HostPullRequestReader.Exchange forge) {
        return new HostPullRequestReader(
                context,
                conf,
                path -> Map.of("uid", 0, "mode", Files.isDirectory(path) ? 0040755 : 0100644),
                (address, token) -> {
                    assertThat(address.getScheme()).isEqualTo("http");
                    assertThat(address.getHost()).isEqualTo("127.0.0.1");
                    assertThat(address.getUserInfo()).isNull();
                    assertThat(address.getFragment()).isNull();
                    return forge.get(address, token);
                });
    }

    private void carriedFor(String account) throws Exception {
        Files.deleteIfExists(dir.resolve("project.yml"));
        final Path persons = conf.resolveSibling("organization/persons");
        Files.createDirectories(persons);
        Files.writeString(persons.resolve(account + ".json"), "{\"projects\":[\"demo\"]}");
        Files.writeString(
                conf.resolveSibling("organization/projects/demo.json"),
                Json.write(
                        Map.of(
                                "project", "demo",
                                "revision", "",
                                "address", "https://central.example/demo.git",
                                "signing_keys", List.of(),
                                "repositories", List.of("shop", "docs"))));
        hostStatus(
                """
                {"project":"demo","read_at":"2026-11-09T10:00:00Z","repositories":[],
                 "state":"carried","host_repositories":[
                   {"repository":"shop","state":"taken"},
                   {"repository":"docs","state":"not-carried"}]}
                """);
        final Path shop = GateSupport.mirror(context, "demo", "shop");
        Files.createDirectories(shop);
        git(shop, "init", "--bare");
        Files.writeString(
                shop.resolve("objects/info/alternates"),
                GateSupport.mirror(context, project).resolve("objects") + "\n");
        assertThat(ProjectSource.resolve(context, "demo").file())
                .as("no project file placed, written or registered")
                .isNull();
    }

    @Test
    void aCarriedProjectAnswersBeforeItsFirstStart() throws Exception {
        carriedFor(System.getProperty("user.name"));
        takeForReview();
        final List<String> asked = new java.util.ArrayList<>();
        final var reader =
                reader(
                        (address, token) -> {
                            asked.add(address.getPath());
                            return openList();
                        });

        assertThat(reader.open("demo"))
                .extracting(HostPullRequestReader.Pull::repository)
                .containsExactly("demo", "shop");
        assertThat(asked)
                .containsExactly("/api/v1/repos/demo/demo/pulls", "/api/v1/repos/demo/shop/pulls");
    }

    @Test
    void aCarriedProjectCanBePolledAgainAfterTaking() throws Exception {
        carriedFor(System.getProperty("user.name"));
        takeForReview();
        final AtomicBoolean merged = new AtomicBoolean();
        final var reader =
                reader(
                        (address, token) ->
                                address.getPath().endsWith("/7")
                                        ? ok(pull("closed", true))
                                        : merged.get() ? none() : openList());
        final var first = reader.poll("demo", List.of());
        merged.set(true);

        final var second = reader.poll("demo", first.open());

        assertThat(second.open()).isEmpty();
        assertThat(second.ended())
                .extracting(HostPullRequestReader.Pull::repository)
                .containsExactly("demo", "shop");
        assertThat(second.ended()).allMatch(row -> row.state().equals("merged"));
    }

    @Test
    void anotherPersonsCarriedProjectIsStillRefused() throws Exception {
        carriedFor(System.getProperty("user.name") + "-other");
        final var reader =
                reader(
                        (address, token) -> {
                            throw new AssertionError(
                                    "another person's repositories must not be read");
                        });

        assertThatThrownBy(() -> reader.open("demo"))
                .isInstanceOf(org.fuin.porelo.core.project.ProjectException.class)
                .hasMessage("no project 'demo' here. This machine has: default");
    }

    @Test
    void carryingTheWholeProjectUsesItsPublishedRepositoryNames() throws Exception {
        carriedFor(System.getProperty("user.name"));
        final Path record = conf.resolveSibling("organization/projects/demo.json");
        Files.writeString(record, Files.readString(record).replace("[\"shop\",\"docs\"]", "[]"));
        takeForReview();
        final var reader = reader((address, token) -> openList());

        assertThat(reader.open("demo"))
                .extracting(HostPullRequestReader.Pull::repository)
                .containsExactly("demo", "shop");
    }

    @Test
    void aProjectFileStillBoundsTheRepositoriesReadByName() throws Exception {
        carriedFor(System.getProperty("user.name"));
        final Path file = context.paths().projects().defaultProjectFile("demo");
        Files.createDirectories(file.getParent());
        Files.writeString(
                file,
                """
                project:
                  name: demo
                  security_class: guarded
                  central: https://central.example/demo.git
                image:
                  from: ubuntu:24.04
                """);
        final var reader = reader((address, token) -> openList());

        assertThat(reader.open("demo"))
                .extracting(HostPullRequestReader.Pull::repository)
                .containsExactly("demo");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void anUnplacedProjectAnswersWithFetchedMakersAfterTaking(boolean existingMirror)
            throws Exception {
        carriedFor(System.getProperty("user.name"));
        takeForReview();
        final Path mirror = GateSupport.mirror(context, project);
        final Path forge = mirror.resolveSibling("forge.git");
        Files.move(mirror, forge);
        if (existingMirror) {
            Files.createDirectories(mirror);
            git(mirror, "init", "--bare");
            Files.writeString(mirror.resolve("kept"), "the gate alone writes here");
        }
        final Map<String, String> before = snapshot(mirror);
        git(forge, "update-ref", "refs/pull/7/head", head);
        try (var server = new ReviewForge(forge)) {
            final var rows =
                    reader(
                                    (address, token) ->
                                            address.getPath().contains("/demo/shop/")
                                                    ? none()
                                                    : openList())
                            .open("demo");
            assertThat(rows)
                    .singleElement()
                    .satisfies(
                            row -> {
                                assertThat(row.author()).isEqualTo("Writer <writer@example.org>");
                                assertThat(row.committer())
                                        .isEqualTo("Signer <signer@example.org>");
                                assertThat(row.trailers())
                                        .containsExactly(
                                                "Assisted-by: Tool",
                                                "Co-Authored-By: Helper <help@example.org>");
                            });
            assertThat(server.asked).contains("/demo/demo.git/info/refs");
            assertThat(snapshot(mirror)).as("the gate's mirror is never written").isEqualTo(before);
            if (!existingMirror) assertThat(mirror).doesNotExist();
            final Path store = context.paths().xdg().state().resolve("reviews/demo/demo.git");
            assertThat(git(store, "rev-parse", "refs/porelo/review")).isEqualTo(head);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {0, 33})
    void aFetchedHeadIsKeptInOnePackAndNeverFetchedAgain(int mebibytes) throws Exception {
        // A fetch every round of a watch would grow the store and ask the forge without end.
        carriedFor(System.getProperty("user.name"));
        takeForReview();
        final Path mirror = GateSupport.mirror(context, project);
        final Path forge = mirror.resolveSibling("forge.git");
        Files.move(mirror, forge);
        if (mebibytes > 0) {
            final byte[] content = new byte[mebibytes * 1024 * 1024];
            new java.util.Random(17).nextBytes(content);
            final var env =
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
                            "signer@example.org");
            final String blob =
                    context.runner()
                            .runOrFail(
                                    Command.of(
                                                    "git",
                                                    "--git-dir=" + forge,
                                                    "hash-object",
                                                    "-w",
                                                    "--stdin")
                                            .withBinaryInput(content)
                                            .withChosenEnvironment(env))
                            .trimmedOutput();
            final String tree =
                    context.runner()
                            .runOrFail(
                                    Command.of("git", "--git-dir=" + forge, "mktree")
                                            .withInput("100644 blob " + blob + "\tlarge.bin\n")
                                            .withChosenEnvironment(env))
                            .trimmedOutput();
            head =
                    context.runner()
                            .runOrFail(
                                    Command.of("git", "--git-dir=" + forge, "commit-tree", tree)
                                            .withInput("A large review head\n")
                                            .withChosenEnvironment(env))
                            .trimmedOutput();
        }
        git(forge, "update-ref", "refs/pull/7/head", head);
        try (var server = new ReviewForge(forge)) {
            final var reader =
                    reader(
                            (address, token) ->
                                    address.getPath().contains("/demo/shop/")
                                            ? none()
                                            : openList());
            assertThat(reader.open("demo")).hasSize(1);
            assertThat(reader.open("demo")).hasSize(1);
            assertThat(server.asked.stream().filter(path -> path.endsWith("/info/refs")))
                    .as("a head kept from the first round is read again, not fetched again")
                    .hasSize(1);
            final Path store = context.paths().xdg().state().resolve("reviews/demo/demo.git");
            try (var files = Files.walk(store.resolve("objects"))) {
                assertThat(
                                files.filter(Files::isRegularFile)
                                        .map(each -> store.resolve("objects").relativize(each))
                                        .filter(each -> !each.startsWith("pack"))
                                        .toList())
                        .as("the shallow head stays packed")
                        .isEmpty();
            }
            try (var packs = Files.list(store.resolve("objects/pack"))) {
                final var files = packs.filter(each -> each.toString().endsWith(".pack")).toList();
                assertThat(files).hasSize(1);
                if (mebibytes > 0)
                    assertThat(Files.size(files.getFirst())).isGreaterThan(32L * 1024 * 1024);
            }
        }
    }

    @Test
    void aStoreOfTheOtherHashIsMadeAnewRatherThanRefusingForever() throws Exception {
        // One answer naming a 64-digit head made a sha256 store that refused every later read.
        carriedFor(System.getProperty("user.name"));
        takeForReview();
        final Path mirror = GateSupport.mirror(context, project);
        final Path forge = mirror.resolveSibling("forge.git");
        Files.move(mirror, forge);
        git(forge, "update-ref", "refs/pull/7/head", head);
        final Path store = context.paths().xdg().state().resolve("reviews/demo/demo.git");
        Files.createDirectories(store);
        git(store, "init", "--bare", "--object-format=sha256");
        try (var server = new ReviewForge(forge)) {
            assertThat(
                            reader(
                                            (address, token) ->
                                                    address.getPath().contains("/demo/shop/")
                                                            ? none()
                                                            : openList())
                                    .open("demo"))
                    .singleElement()
                    .satisfies(
                            row ->
                                    assertThat(row.author())
                                            .isEqualTo("Writer <writer@example.org>"));
        }
    }

    @Test
    void aRedirectOfTheReviewFetchNeverReceivesThePersonsToken() throws Exception {
        carriedFor(System.getProperty("user.name"));
        takeForReview();
        final Path mirror = GateSupport.mirror(context, project);
        final Path forge = mirror.resolveSibling("forge.git");
        Files.move(mirror, forge);
        git(forge, "update-ref", "refs/pull/7/head", head);
        try (var server = new ReviewForge(forge, true)) {
            assertThatThrownBy(
                            () ->
                                    reader(
                                                    (address, token) ->
                                                            address.getPath()
                                                                            .contains("/demo/shop/")
                                                                    ? none()
                                                                    : openList())
                                            .open("demo"))
                    .isInstanceOfSatisfying(
                            HostPullRequestReader.Refused.class,
                            refusal -> assertThat(refusal.reason()).isEqualTo("makers"));
            assertThat(server.asked)
                    .as("git follows no redirect with the person's token")
                    .containsExactly("/demo/demo.git/info/refs");
        }
    }

    @Test
    void onlyAnUnplacedProjectCarriedForThisPersonIsTakenFirst() throws Exception {
        assertThat(reader((address, token) -> openList()).takesFirst("demo"))
                .as("not carried for this person: left to the read's own refusal, untaken")
                .isFalse();
        carriedFor(System.getProperty("user.name"));
        assertThat(reader((address, token) -> openList()).takesFirst("demo")).isTrue();
        takeForReview();
        assertThat(reader((address, token) -> openList()).takesFirst("demo"))
                .as("placed already")
                .isFalse();
    }

    @Test
    void aFetchedHeadMustEqualTheRowAndNeverCreatesTheGateMirror() throws Exception {
        final Path mirror = GateSupport.mirror(context, project);
        final Path forge = mirror.resolveSibling("forge.git");
        Files.move(mirror, forge);
        git(forge, "update-ref", "refs/pull/7/head", head);
        try (var server = new ReviewForge(forge)) {
            head = "b".repeat(40);
            assertThatThrownBy(() -> reader((address, token) -> openList()).open(project))
                    .isInstanceOfSatisfying(
                            HostPullRequestReader.Refused.class,
                            refusal -> assertThat(refusal.reason()).isEqualTo("makers"));
            assertThat(server.asked).contains("/demo/demo.git/info/refs");
            assertThat(mirror).doesNotExist();
        }
    }

    @Test
    void anUnplacedProjectNeverGuessesCopiesFromRepositoryKeys() throws Exception {
        carriedFor(System.getProperty("user.name"));
        final List<String> asked = new java.util.ArrayList<>();
        final var reader =
                reader(
                        (address, token) -> {
                            asked.add(address.getPath());
                            return none();
                        });
        assertThatThrownBy(() -> reader.open("demo"))
                .isInstanceOfSatisfying(
                        HostPullRequestReader.Refused.class,
                        refusal -> assertThat(refusal.reason()).isEqualTo("not-carried"));
        assertThat(asked).isEmpty();
    }

    @Test
    void aRepositoryKeyUsesTheCopyNamedByItsCentralAddress() throws Exception {
        carriedFor(System.getProperty("user.name"));
        takeForReview("product");
        final List<String> asked = new java.util.ArrayList<>();
        reader(
                        (address, token) -> {
                            asked.add(address.getPath());
                            return none();
                        })
                .open("demo");
        assertThat(asked)
                .containsExactly(
                        "/api/v1/repos/demo/demo/pulls", "/api/v1/repos/demo/product/pulls");
    }

    private Map<String, String> snapshot(Path directory) throws Exception {
        final Map<String, String> result = new java.util.TreeMap<>();
        if (!Files.exists(directory)) return result;
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                result.put(
                        directory.relativize(path).toString(),
                        java.util.HexFormat.of()
                                .formatHex(
                                        java.security.MessageDigest.getInstance("SHA-256")
                                                .digest(Files.readAllBytes(path))));
            }
        }
        return result;
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {"pending", "waiting-for-token", "held", "refused", "cannot-say"})
    void anUnplacedProjectRequiresACarriedState(String state) throws Exception {
        carriedFor(System.getProperty("user.name"));
        if (state.equals("cannot-say")) hostStatus("not JSON");
        else
            hostStatus(
                    Json.write(
                            Map.of(
                                    "project",
                                    "demo",
                                    "state",
                                    state,
                                    "read_at",
                                    java.time.Instant.now().toString(),
                                    "repositories",
                                    List.of())));
        assertThat(
                        new HostCentral(
                                        conf.resolveSibling("organization/central"),
                                        path ->
                                                Map.of(
                                                        "uid",
                                                        0,
                                                        "mode",
                                                        Files.isDirectory(path)
                                                                ? 0040755
                                                                : 0100644))
                                .status("demo")
                                .state())
                .isEqualTo(state);
        assertThatThrownBy(
                        () ->
                                reader(
                                                (address, token) -> {
                                                    throw new AssertionError(
                                                            "no request without carrying");
                                                })
                                        .open("demo"))
                .isInstanceOfSatisfying(
                        HostPullRequestReader.Refused.class,
                        refusal -> assertThat(refusal.reason()).isEqualTo("not-carried"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {"pending", "waiting-for-token", "held", "refused", "cannot-say"})
    void aWithheldProjectIsRefusedBeforeItIsTaken(String state) throws Exception {
        // Taking lends this person a forge token and fetches: never for what the host withholds.
        carriedFor(System.getProperty("user.name"));
        if (state.equals("cannot-say")) hostStatus("not JSON");
        else
            hostStatus(
                    Json.write(
                            Map.of(
                                    "project",
                                    "demo",
                                    "state",
                                    state,
                                    "read_at",
                                    java.time.Instant.now().toString(),
                                    "repositories",
                                    List.of())));
        assertThatThrownBy(() -> reader((address, token) -> openList()).takesFirst("demo"))
                .isInstanceOfSatisfying(
                        HostPullRequestReader.Refused.class,
                        refusal -> assertThat(refusal.reason()).isEqualTo("not-carried"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {"organization/projects/demo.json", "organization/persons/"})
    void anUnplacedProjectRefusesMalformedHostFiles(String file) throws Exception {
        carriedFor(System.getProperty("user.name"));
        final Path malformed =
                conf.resolveSibling(
                        file.endsWith("/")
                                ? file + System.getProperty("user.name") + ".json"
                                : file);
        Files.writeString(malformed, "{not JSON");
        assertThatThrownBy(
                        () ->
                                reader(
                                                (address, token) -> {
                                                    throw new AssertionError(
                                                            "no request from a malformed record");
                                                })
                                        .open("demo"))
                .isInstanceOfSatisfying(
                        HostPullRequestReader.Refused.class,
                        refusal -> assertThat(refusal.reason()).isEqualTo("not-carried"));
    }

    private void takeForReview() throws Exception {
        takeForReview("shop");
    }

    private void takeForReview(String copyName) throws Exception {
        final var carried =
                OrganizationProjects.host(conf.resolveSibling("organization/projects"), "demo");
        assertThat(carried).isNotNull();
        try (var source =
                new RegistryCheckout(Files.createDirectory(dir.resolve("signed-project")))) {
            source.write(
                    "project.yml",
                    "project:\n  name: demo\n  security_class: guarded\n  central: https://central.example/demo.git\nimage:\n  from: ubuntu:24.04\nrepositories:\n  shop:\n    upstream: https://upstream.example/shop.git\n    central: https://central.example/shop.git\n"
                            .replace(
                                    "central.example/shop.git",
                                    "central.example/" + copyName + ".git"));
            source.commit("michael");
            Files.writeString(
                    conf.resolveSibling("organization/projects/demo.json"),
                    Json.write(
                            Map.of(
                                    "project",
                                    "demo",
                                    "revision",
                                    "",
                                    "address",
                                    "https://central.example/demo.git",
                                    "fetch_from",
                                    "file://" + source.repo,
                                    "repositories",
                                    carried.repositories(),
                                    "signing_keys",
                                    List.of(
                                            Map.of(
                                                    "login",
                                                    "admin",
                                                    "role",
                                                    "admin",
                                                    "key",
                                                    source.key("michael"),
                                                    "valid_from",
                                                    "2000-01-01",
                                                    "valid_until",
                                                    "2999-01-01")))));
            assertThat(ProjectSource.resolve(context, "demo").file()).isNull();
            ProjectTaking.takeToAsk(context, "demo");
            assertThat(ProjectSource.resolve(context, "demo").file()).isNotNull();
        }
    }

    private final class ReviewForge implements AutoCloseable {
        private final com.sun.net.httpserver.HttpServer server;
        private final List<String> asked = new java.util.concurrent.CopyOnWriteArrayList<>();

        ReviewForge(Path repository) throws Exception {
            this(repository, false);
        }

        ReviewForge(Path repository, boolean redirect) throws Exception {
            git(repository, "update-server-info");
            server =
                    com.sun.net.httpserver.HttpServer.create(
                            new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext(
                    "/",
                    exchange -> {
                        final String path = exchange.getRequestURI().getPath();
                        if (redirect) {
                            asked.add(path);
                            exchange.getResponseHeaders()
                                    .set(
                                            "Location",
                                            "http://127.0.0.1:"
                                                    + ReviewForge.this.server.getAddress().getPort()
                                                    + "/stolen"
                                                    + path);
                            exchange.sendResponseHeaders(307, -1);
                            exchange.close();
                            return;
                        }
                        assertThat(path)
                                .isIn("/demo/demo.git/info/refs", "/demo/demo.git/git-upload-pack");
                        asked.add(path);
                        final String authorization =
                                "Basic "
                                        + java.util.Base64.getEncoder()
                                                .encodeToString(
                                                        ("x-access-token:" + TOKEN)
                                                                .getBytes(StandardCharsets.UTF_8));
                        assertThat(exchange.getRequestHeaders().getFirst("Authorization"))
                                .isEqualTo(authorization);
                        assertThat(exchange.getLocalAddress().getAddress().isLoopbackAddress())
                                .isTrue();
                        final boolean advertise = path.endsWith("/info/refs");
                        final var arguments =
                                new java.util.ArrayList<>(
                                        List.of("git", "upload-pack", "--stateless-rpc"));
                        if (advertise) arguments.add("--advertise-refs");
                        arguments.add(repository.toString());
                        final var builder = new ProcessBuilder(arguments);
                        builder.environment()
                                .putAll(
                                        Map.of(
                                                "GIT_CONFIG_GLOBAL",
                                                "/dev/null",
                                                "GIT_CONFIG_NOSYSTEM",
                                                "1"));
                        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
                        final var process = builder.start();
                        try (var input = process.getOutputStream()) {
                            if (!advertise) exchange.getRequestBody().transferTo(input);
                        }
                        final byte[] bytes = process.getInputStream().readAllBytes();
                        try {
                            assertThat(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS))
                                    .isTrue();
                            assertThat(process.exitValue()).isZero();
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                            throw new java.io.IOException(ex);
                        } finally {
                            process.destroyForcibly();
                        }
                        exchange.getResponseHeaders()
                                .set(
                                        "Content-Type",
                                        "application/x-git-upload-pack-"
                                                + (advertise ? "advertisement" : "result"));
                        exchange.sendResponseHeaders(200, 0);
                        try (var out = exchange.getResponseBody()) {
                            if (advertise)
                                out.write(
                                        "001e# service=git-upload-pack\n0000"
                                                .getBytes(StandardCharsets.UTF_8));
                            out.write(bytes);
                        }
                        exchange.close();
                    });
            server.start();
            Files.writeString(
                    conf,
                    "name=forge.example\naccount=forge\ngit_port="
                            + server.getAddress().getPort()
                            + "\n");
        }

        @Override
        public void close() {
            server.stop(0);
        }
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
                                        (" ".repeat(32 * 1024 * 1024) + "[]")
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
                conf.resolveSibling("organization/projects/demo.json"),
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

    private void hostStatus(String content) throws Exception {
        final Path central = conf.resolveSibling("organization/central");
        Files.createDirectories(central);
        Files.writeString(central.resolve("demo.json"), content);
    }

    @Test
    void onlyTheRepositoriesTheHostStatusNamesTakenAreAsked() throws Exception {
        // A repository the window shows as not carried here must be neither asked nor refused.
        final StringBuilder yml =
                new StringBuilder(
                        """
                        project:
                          name: demo
                          security_class: guarded
                          central: https://central.example/demo.git
                        image:
                          from: ubuntu:24.04
                        repositories:
                        """);
        final StringBuilder rows = new StringBuilder();
        for (int i = 1; i <= 18; i++) {
            yml.append("  r")
                    .append(i)
                    .append(":\n    upstream: \"https://upstream.example/r")
                    .append(i)
                    .append(".git\"\n    central: \"https://central.example/r")
                    .append(i)
                    .append(".git\"\n");
            rows.append(i == 1 ? "" : ",")
                    .append("{\"repository\":\"r")
                    .append(i)
                    .append("\",\"state\":\"")
                    .append(i <= 3 ? "taken" : "not-carried")
                    .append("\"}");
        }
        final Path file = dir.resolve("nineteen.yml");
        Files.writeString(file, yml);
        hostStatus(
                "{\"project\":\"demo\",\"read_at\":\"2026-11-09T10:00:00Z\",\"repositories\":[],"
                        + "\"state\":\"carried\",\"host_repositories\":["
                        + rows
                        + "]}");
        final Project nineteen = ProjectReader.read(file);
        final List<String> asked = new java.util.ArrayList<>();
        final var reader =
                reader(
                        (address, token) -> {
                            asked.add(address.getPath());
                            // Only the copies taken here exist on the host's forge.
                            return address.getPath().matches("/api/v1/repos/demo/(demo|r[123])/.*")
                                    ? none()
                                    : status(404);
                        });

        final var round = reader.poll(nineteen, List.of());

        assertThat(asked)
                .as("the project's own and the three the host status names taken")
                .containsExactly(
                        "/api/v1/repos/demo/demo/pulls",
                        "/api/v1/repos/demo/r1/pulls",
                        "/api/v1/repos/demo/r2/pulls",
                        "/api/v1/repos/demo/r3/pulls");
        assertThat(round.refused()).as("no repository not carried is refused").isEmpty();
    }

    @Test
    void aHostStatusThatDoesNotReadRefusesTheWholeRead() throws Exception {
        // Asking nothing must not answer as an empty review list when the carry state is unknown.
        hostStatus("not json");
        final var reader =
                reader(
                        (address, token) -> {
                            throw new AssertionError("nothing is asked: " + address.getPath());
                        });

        assertThatThrownBy(() -> reader.poll(project, List.of()))
                .as("the read is refused as not carried, saying the carry state does not read")
                .isInstanceOfSatisfying(
                        HostPullRequestReader.Refused.class,
                        ex -> assertThat(ex.reason()).as("why").isEqualTo("not-carried"))
                .hasMessageContaining("carry state");
    }

    @Test
    void aProjectTheHostHoldsRefusesTheReadRatherThanAnsweringNoReviews() throws Exception {
        // A project held, pending or refused here skipped every repository and answered an empty
        // list, which reads as no reviews.
        hostStatus(
                "{\"project\":\"demo\",\"read_at\":\"2026-11-09T10:00:00Z\",\"repositories\":[],"
                        + "\"state\":\"held\",\"why\":\"held by the organization\"}");
        final var reader =
                reader(
                        (address, token) -> {
                            throw new AssertionError("nothing is asked: " + address.getPath());
                        });

        assertThatThrownBy(() -> reader.poll(project, List.of()))
                .as("refused as not carried, naming the host's state")
                .isInstanceOfSatisfying(
                        HostPullRequestReader.Refused.class,
                        ex -> assertThat(ex.reason()).as("why").isEqualTo("not-carried"))
                .hasMessageContaining("held");
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
    void keptRowsAreCarriedWhateverTheirNumber() throws Exception {
        // A project's open reviews are all watched, however many a refused repository keeps.
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

        final var round = reader.poll(three, earlier);

        assertThat(round.kept()).as("every kept row").isEqualTo(earlier);
        assertThat(round.open()).as("the open row besides them").hasSize(1);
    }

    @Test
    void aForgeWithMoreThanFiveHundredOpenPullsIsReadWhole() {
        // Every page is read: a project's open reviews are never cut at a count.
        final List<String> asked = new java.util.concurrent.CopyOnWriteArrayList<>();
        final var reader =
                pagingReader(
                        (address, token) -> {
                            asked.add(address.getQuery());
                            return openPage(address, 501, "");
                        });

        assertThat(reader.open(project))
                .as("all 501 open pulls")
                .extracting(HostPullRequestReader.Pull::number)
                .containsExactlyElementsOf(
                        java.util.stream.LongStream.rangeClosed(1, 501).boxed().toList());
        assertThat(asked)
                .as("eleven pages of pulls, then the empty twelfth that ends the list")
                .hasSize(12)
                .last()
                .isEqualTo("state=open&limit=50&page=12");
    }

    @Test
    @org.junit.jupiter.api.Tag("slow")
    void aReadWalksPastPageOneHundred() {
        // No page count ends a read: the forge's own last page does.
        final List<String> asked = new java.util.concurrent.CopyOnWriteArrayList<>();
        final var reader =
                pagingReader(
                        (address, token) -> {
                            asked.add(address.getQuery());
                            return address.getQuery().endsWith("page=101")
                                    ? status(503)
                                    : openPage(address, 100L * 50, "");
                        });

        assertThat(reader.poll(project, List.of()).refused())
                .as("the hundred-and-first page was asked and answered")
                .singleElement()
                .satisfies(refusal -> assertThat(refusal.status()).as("its status").isEqualTo(503));
        assertThat(asked).as("past page 100").contains("state=open&limit=50&page=101");
    }

    @Test
    void aPageOfAboutTwoMiBIsTaken() {
        // A legal page of pulls with long multibyte bodies is far above one MiB.
        final String body = "€".repeat(65_536);
        final var reader = pagingReader((address, token) -> openPage(address, 11, body));
        assertThat(openPage(java.net.URI.create("http://x/?page=1"), 11, body).body())
                .as("the page is about 2 MiB")
                .hasSizeGreaterThan(2 * 1024 * 1024);

        assertThat(reader.open(project)).as("the whole page is read").hasSize(11);
    }

    @Test
    void aForgeRepeatingAFullPageIsRefused() {
        // A forge that ignores the page asked would otherwise be read forever.
        final List<String> asked = new java.util.concurrent.CopyOnWriteArrayList<>();
        final var reader =
                pagingReader(
                        (address, token) -> {
                            asked.add(address.getQuery());
                            return openPage(java.net.URI.create("http://x/?page=1"), 50, "");
                        });

        assertThatThrownBy(() -> reader.open(project))
                .as("a page repeating pulls already read is an unusable answer")
                .isInstanceOfSatisfying(
                        HostPullRequestReader.Refused.class,
                        ex -> assertThat(ex.reason()).as("why").isEqualTo("forge-answer"));
        assertThat(asked).as("refused at the second page").hasSize(2);
    }

    private HostPullRequestReader.Answer openPage(java.net.URI address, long total, String body) {
        final int page = Integer.parseInt(address.getQuery().replaceFirst(".*page=", ""));
        final List<Object> pulls =
                java.util.stream.LongStream.rangeClosed((page - 1) * 50L + 1, total)
                        .limit(50)
                        .<Object>mapToObj(
                                n ->
                                        Map.of(
                                                "number",
                                                n,
                                                "title",
                                                "Review " + n,
                                                "body",
                                                body,
                                                "head",
                                                Map.of("sha", head),
                                                "state",
                                                "open",
                                                "merged",
                                                false))
                        .toList();
        return ok(Json.write(pulls).getBytes(StandardCharsets.UTF_8));
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
