package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.fuin.porelo.gate.GitGate;
import org.fuin.porelo.gate.review.ForgeCredential;
import org.fuin.porelo.gate.review.ForgePages;
import org.fuin.porelo.gate.review.ForgeReview;
import org.fuin.porelo.gate.review.PlaceUnreadable;
import org.fuin.porelo.gate.review.PullRequestFacts;
import org.fuin.porelo.gate.review.PullRequestRef;
import org.fuin.porelo.gate.review.PullRequestState;
import org.fuin.porelo.gate.review.Push;
import org.fuin.porelo.gate.review.WriteOutcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * A pull request is fetched into a reviewer's mirror by real git from a forge on loopback that
 * serves its repository through {@code git http-backend} over https to the lent token alone, with
 * real {@code porelo vault credential} answering git: the run pins exactly the commits the forge's
 * adapter named, fetched by commit id as a forge's smart http serves them.
 */
@Timeout(180)
class PullRequestFetchGitTest {

    private static final String TOKEN = "lent-read-test-value";

    private static final String PASSWORD = "test-password";

    private static final String TASK = "0123456789abcdef0123";

    private static final String TASK_NAME = "review-shop";

    @TempDir static Path certificates;

    @TempDir Path dir;

    private final List<String> seen = new CopyOnWriteArrayList<>();

    private final List<CommandResult> printed = new CopyOnWriteArrayList<>();

    private final Forge adapter = new Forge();

    private HttpsServer forge;

    private Path served;

    private Path mirror;

    private Path state;

    private Path record;

    private PullRequestRef ref;

    private PullRequestPins pins;

    @BeforeAll
    static void createTheForgesCertificate() throws Exception {
        final ProcessBuilder builder =
                new ProcessBuilder(
                                Path.of(System.getProperty("java.home"), "bin", "keytool")
                                        .toString(),
                                "-genkeypair",
                                "-alias",
                                "forge",
                                "-keyalg",
                                "EC",
                                "-dname",
                                "CN=127.0.0.1",
                                "-ext",
                                "bc:c",
                                "-ext",
                                "san=ip:127.0.0.1",
                                "-keystore",
                                "forge.p12",
                                "-storepass:env",
                                "PORELO_TEST_STORE_PASSWORD")
                        .directory(certificates.toFile())
                        .redirectErrorStream(true);
        builder.environment().put("PORELO_TEST_STORE_PASSWORD", PASSWORD);
        final Process keytool = builder.start();
        final String output =
                new String(keytool.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(keytool.waitFor()).as("keytool must make the certificate: %s", output).isZero();
        final String pem =
                "-----BEGIN CERTIFICATE-----\n"
                        + Base64.getMimeEncoder(64, new byte[] {'\n'})
                                .encodeToString(store().getCertificate("forge").getEncoded())
                        + "\n-----END CERTIFICATE-----\n";
        Files.writeString(certificates.resolve("forge.pem"), pem, StandardCharsets.US_ASCII);
    }

    @BeforeEach
    void aForgeAndAReviewersMirror() throws Exception {
        System.setProperty(PoreloBinary.NAMED, ownPorelo().toString());
        served = dir.resolve("served");
        Files.createDirectories(served.resolve("o"));
        git(dir, "init", "-q", "--bare", "-b", "main", repository().toString());
        mirror = dir.resolve("mirror.git");
        git(dir, "init", "-q", "--bare", mirror.toString());
        state = Files.createDirectory(dir.resolve("state"));
        record = Files.createDirectory(dir.resolve("record"));
        new RunPlace(RunPlace.REVIEWER, null, "shop-2").writeTo(state);
        forge = forge();
        final String origin = "https://127.0.0.1:" + forge.getAddress().getPort();
        ref = PullRequestRef.of(URI.create(origin + "/o/shop/pull/7"));
        adapter.repository = origin + "/o/shop.git";
        final CommandRunner real = new ProcessCommandRunner(Duration.ofMinutes(2));
        pins =
                new PullRequestPins(
                        adapter,
                        entry -> new ForgeCredential(entry, TOKEN),
                        task ->
                                new PullRequestPins.TaskGate(
                                        mirror,
                                        state,
                                        record,
                                        dir.resolve("pinned").resolve(TASK_NAME + ".git"),
                                        TASK_NAME,
                                        "shop",
                                        "shop",
                                        "shop-2"),
                        command -> {
                            final CommandResult result = real.run(command);
                            printed.add(result);
                            return result;
                        },
                        url ->
                                Map.of(
                                        "GIT_SSL_CAINFO",
                                        certificates.resolve("forge.pem").toString()),
                        Duration.ofMinutes(2));
    }

    @AfterEach
    void stopTheForge() {
        if (forge != null) {
            forge.stop(0);
        }
        System.clearProperty(PoreloBinary.NAMED);
    }

    @Test
    void aRunPinsTheHeadAndItsMergeBaseTheForgeServesByCommitId() throws Exception {
        final String base = commit("One");
        final String head = commit("Two");
        // No ref's tip, so only a fetch by its commit id names it, as for a fork's head.
        commit("Three");
        adapter.facts(head, base);

        final Push push = pins.pin(ref, TASK);

        assertThat(push.head()).as("the pinned head").isEqualTo(head);
        assertThat(push.base()).as("the merge base").isEqualTo(base);
        assertThat(inMirror("rev-parse", GitGate.PINNED + TASK_NAME))
                .as("the reviewer's pinned ref in its mirror")
                .isEqualTo(head);
        assertThat(inMirror("cat-file", "-t", base))
                .as("the merge base in the mirror")
                .isEqualTo("commit");
        assertThat(
                        git(
                                        dir,
                                        "--git-dir",
                                        dir.resolve("pinned")
                                                .resolve(TASK_NAME + ".git")
                                                .toString(),
                                        "rev-parse",
                                        GitGate.PINNED + TASK_NAME)
                                .strip())
                .as("the pinned head in the reviewer's own repository, where its gate serves it")
                .isEqualTo(head);
        assertThat(RunPlace.readFrom(state).pinned())
                .as("the run's record")
                .isEqualTo(new RunPlace.Pin(head, base, adapter.repository));
        assertThat(seen)
                .as("the forge was sent the lent token")
                .contains("x-access-token:" + TOKEN);
    }

    @Test
    void aLaterRunAfterANewHeadPinsTheNewOne() throws Exception {
        final String base = commit("One");
        final String first = commit("Two");
        adapter.facts(first, base);
        pins.pin(ref, TASK);
        final String later = commit("Three");
        adapter.facts(later, base);

        final Push push = pins.pin(ref, TASK);

        assertThat(push.head()).as("the new head").isEqualTo(later);
        assertThat(inMirror("rev-parse", GitGate.PINNED + TASK_NAME))
                .as("the pinned ref moved on")
                .isEqualTo(later);
    }

    @Test
    void theTokenIsInNoArgumentNoOutputAndNoFileOfTheMirror() throws Exception {
        final String base = commit("One");
        final String head = commit("Two");
        adapter.facts(head, base);

        pins.pin(ref, TASK);

        assertThat(printed).as("git was run").isNotEmpty();
        for (final CommandResult result : printed) {
            assertThat(String.join(" ", result.command().arguments()))
                    .as("arguments of %s", result.command().arguments())
                    .doesNotContain(TOKEN);
            assertThat(result.standardOutput() + result.standardError())
                    .as("what git and its helper printed for %s", result.command().arguments())
                    .doesNotContain(TOKEN)
                    .doesNotContain(basic());
        }
        final List<Path> files;
        try (Stream<Path> walked = Files.walk(mirror)) {
            files = walked.filter(Files::isRegularFile).toList();
        }
        for (final Path file : files) {
            assertThat(new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1))
                    .as("%s", file)
                    .doesNotContain(TOKEN)
                    .doesNotContain(basic());
        }
        assertThat(Files.readString(state.resolve(RunPlace.FILE)))
                .as("the run's record")
                .doesNotContain(TOKEN);
    }

    @Test
    void aHeadTheForgeDoesNotServeIsRefusedAsTheForgeRefusedItAndNothingIsPinned()
            throws Exception {
        final String base = commit("One");
        final String missing = "4e1d2c3b4a5f6e7d8c9b0a1f2e3d4c5b6a7f8e9d";
        adapter.facts(missing, base);

        assertThatThrownBy(() -> pins.pin(ref, TASK))
                .as("the forge's repository lacks the head")
                .isInstanceOfSatisfying(
                        PlaceUnreadable.class,
                        refused -> {
                            assertThat(refused.reason())
                                    .as("reason")
                                    .isEqualTo(PlaceUnreadable.Reason.HEAD_NOT_FETCHED);
                            assertThat(refused.getMessage())
                                    .as("detail")
                                    .contains(missing)
                                    .doesNotContain(TOKEN);
                        });
        assertThat(printed.stream().map(CommandResult::standardError).toList())
                .as("the forge's own refusal, measured")
                .anySatisfy(error -> assertThat(error).as("git's error").contains("not our ref"));
        assertThat(RunPlace.readFrom(state).pinned()).as("nothing pinned").isNull();
    }

    private Path repository() {
        return served.resolve("o").resolve("shop.git");
    }

    /** Commits on the served repository's main branch. */
    private String commit(final String message) throws Exception {
        final Path work = dir.resolve("work");
        if (!Files.isDirectory(work)) {
            git(dir, "clone", "-q", repository().toString(), work.toString());
        }
        git(
                work,
                "-c",
                "user.name=Test",
                "-c",
                "user.email=test@example.org",
                "commit",
                "-q",
                "--allow-empty",
                "-m",
                message);
        git(work, "push", "-q", "origin", "HEAD:refs/heads/main");
        return git(work, "rev-parse", "HEAD").strip();
    }

    private String inMirror(final String... question) throws Exception {
        final List<String> arguments = new ArrayList<>(List.of("--git-dir", mirror.toString()));
        arguments.addAll(List.of(question));
        return git(dir, arguments.toArray(String[]::new)).strip();
    }

    private static String basic() {
        return Base64.getEncoder()
                .encodeToString(("x-access-token:" + TOKEN).getBytes(StandardCharsets.UTF_8));
    }

    /** This build's own command line, which is what git starts as the helper. */
    private Path ownPorelo() throws IOException {
        final Path launcher = dir.resolve("own").resolve("porelo");
        Files.createDirectories(launcher.getParent());
        Files.writeString(
                launcher,
                "#!/bin/sh\nexec '"
                        + ProcessHandle.current().info().command().orElse("java")
                        + "' --enable-native-access=ALL-UNNAMED '-Dporelo.keyring.prefix="
                        + System.getProperty("porelo.keyring.prefix", "porelo")
                        + "' -cp '"
                        + System.getProperty("java.class.path")
                        + "' "
                        + PoreloCli.class.getName()
                        + " \"$@\"\n");
        Files.setPosixFilePermissions(launcher, PosixFilePermissions.fromString("rwx------"));
        return launcher;
    }

    /**
     * A forge on loopback: git's own smart http, {@code git http-backend}, behind the token, noting
     * each credential it was sent.
     */
    private HttpsServer forge() throws Exception {
        final KeyManagerFactory managers =
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        managers.init(store(), PASSWORD.toCharArray());
        final SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(managers.getKeyManagers(), null, null);
        final HttpsServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(tls));
        server.createContext(
                "/",
                exchange -> {
                    try (exchange) {
                        final String sent = exchange.getRequestHeaders().getFirst("Authorization");
                        final String credential =
                                sent == null || !sent.startsWith("Basic ")
                                        ? "none"
                                        : new String(
                                                Base64.getDecoder().decode(sent.substring(6)),
                                                StandardCharsets.UTF_8);
                        seen.add(credential);
                        if (!credential.equals("x-access-token:" + TOKEN)) {
                            exchange.getResponseHeaders()
                                    .add("WWW-Authenticate", "Basic realm=\"forge\"");
                            exchange.sendResponseHeaders(401, -1);
                            return;
                        }
                        backend(exchange);
                    }
                });
        server.start();
        return server;
    }

    /** Hands one request to {@code git http-backend} as CGI and its answer back. */
    private void backend(final HttpExchange exchange) throws IOException {
        final byte[] body = exchange.getRequestBody().readAllBytes();
        final ProcessBuilder builder = new ProcessBuilder("git", "http-backend");
        final Map<String, String> environment = builder.environment();
        environment.clear();
        environment.put("PATH", System.getenv().getOrDefault("PATH", "/usr/bin:/bin"));
        environment.put("GIT_CONFIG_GLOBAL", "/dev/null");
        environment.put("GIT_CONFIG_NOSYSTEM", "1");
        environment.put("GIT_PROJECT_ROOT", served.toString());
        environment.put("GIT_HTTP_EXPORT_ALL", "1");
        environment.put("REQUEST_METHOD", exchange.getRequestMethod());
        environment.put("PATH_INFO", exchange.getRequestURI().getRawPath());
        final String query = exchange.getRequestURI().getRawQuery();
        environment.put("QUERY_STRING", query == null ? "" : query);
        environment.put("CONTENT_LENGTH", Integer.toString(body.length));
        environment.put("REMOTE_USER", "x-access-token");
        environment.put("REMOTE_ADDR", "127.0.0.1");
        header(exchange, "Content-Type", "CONTENT_TYPE", environment);
        header(exchange, "Content-Encoding", "HTTP_CONTENT_ENCODING", environment);
        header(exchange, "Git-Protocol", "GIT_PROTOCOL", environment);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        final Process backend = builder.start();
        try (OutputStream in = backend.getOutputStream()) {
            in.write(body);
        }
        final byte[] answer = backend.getInputStream().readAllBytes();
        try {
            backend.waitFor();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("the backend was interrupted", ex);
        }
        final String text = new String(answer, StandardCharsets.ISO_8859_1);
        final int crlf = text.indexOf("\r\n\r\n");
        final int lf = text.indexOf("\n\n");
        final boolean crlfFirst = crlf >= 0 && (lf < 0 || crlf < lf);
        final int end = crlfFirst ? crlf : lf;
        if (end < 0) {
            exchange.sendResponseHeaders(502, -1);
            return;
        }
        int status = 200;
        for (final String line : text.substring(0, end).split("\r?\n")) {
            final int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            final String name = line.substring(0, colon).strip();
            final String value = line.substring(colon + 1).strip();
            if (name.equalsIgnoreCase("Status")) {
                status = Integer.parseInt(value.split(" ")[0]);
            } else {
                exchange.getResponseHeaders().add(name, value);
            }
        }
        final byte[] content = Arrays.copyOfRange(answer, end + (crlfFirst ? 4 : 2), answer.length);
        exchange.sendResponseHeaders(status, content.length == 0 ? -1 : content.length);
        if (content.length > 0) {
            exchange.getResponseBody().write(content);
        }
    }

    private static void header(
            final HttpExchange exchange,
            final String name,
            final String variable,
            final Map<String, String> environment) {
        final String value = exchange.getRequestHeaders().getFirst(name);
        if (value != null) {
            environment.put(variable, value);
        }
    }

    private static KeyStore store() throws Exception {
        final KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(certificates.resolve("forge.p12"))) {
            keys.load(in, PASSWORD.toCharArray());
        }
        return keys;
    }

    /** Runs git for the test's own setup, apart from this machine's configuration. */
    private static String git(final Path where, final String... arguments) throws Exception {
        final List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(arguments));
        final ProcessBuilder builder =
                new ProcessBuilder(command).directory(where.toFile()).redirectErrorStream(true);
        final String path = System.getenv("PATH");
        builder.environment().clear();
        builder.environment()
                .putAll(
                        Map.of(
                                "PATH", path == null ? "" : path,
                                "HOME", where.toString(),
                                "GIT_CONFIG_GLOBAL", "/dev/null",
                                "GIT_CONFIG_NOSYSTEM", "1",
                                "GIT_TERMINAL_PROMPT", "0",
                                "LC_ALL", "C"));
        final Process git = builder.start();
        git.getOutputStream().close();
        final String output =
                new String(git.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(git.waitFor()).as("git %s: %s", command, output).isZero();
        return output;
    }

    /** The forge's adapter: the facts the test names. */
    private static final class Forge implements ForgeReview {

        private String repository = "";

        private String head = "";

        private String base = "";

        void facts(final String newHead, final String mergeBase) {
            this.head = newHead;
            this.base = mergeBase;
        }

        @Override
        public PullRequestFacts facts(final PullRequestRef ref, final ForgeCredential credential) {
            return new PullRequestFacts(
                    ref,
                    repository,
                    head,
                    base,
                    base,
                    PullRequestState.OPEN,
                    false,
                    null,
                    null,
                    null,
                    null);
        }

        @Override
        public ForgePages conversation(final PullRequestRef ref, final ForgeCredential credential) {
            throw new UnsupportedOperationException("a pin reads no conversation");
        }

        @Override
        public WriteOutcome post(
                final PullRequestRef ref, final byte[] document, final ForgeCredential credential) {
            throw new UnsupportedOperationException("a pin posts nothing");
        }
    }
}
