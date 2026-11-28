package org.fuin.porelo.acceptance.suite;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.stream.Stream;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;

/**
 * A test user's scratch repository on Forgejo, made, answered and removed by the suite's host.
 * Requests trust only the fixture's CA and keep hostname verification enabled. The token reaches
 * HTTP through a header and git through its environment, never through an argument or a URL.
 */
final class ForgejoScratch {

    private static final Duration CALL = Duration.ofSeconds(30);
    private static final int TRIES = 15;
    private static final int MAX_PAGES = 100;
    // A release decided on the command line goes out at the daemon's next timed pass, every 60
    // seconds: the thread is waited for past one whole pass.
    private static final int THREAD_TRIES = 45;
    private final Identity identity;
    private final Path work;
    private final Exchange exchange;
    private final Git runner;
    private final @Nullable HttpClient http;
    private @Nullable String repository;
    private @Nullable String login;
    private @Nullable String base;
    private long number;

    /** Creates the helper with the fixture's CA; no network call is made yet. */
    ForgejoScratch(Identity identity, Path work) throws IOException {
        this.identity = Objects.requireNonNull(identity, "identity");
        this.work = Objects.requireNonNull(work, "work");
        final HttpClient client = client(identity);
        this.http = client;
        this.exchange =
                request -> {
                    try {
                        final HttpResponse<String> response =
                                client.send(request, HttpResponse.BodyHandlers.ofString());
                        return new Reply(response.statusCode(), response.body());
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new IOException("interrupted while calling Forgejo", ex);
                    }
                };
        this.runner = this::git;
    }

    /** Creates the helper over recorded HTTP answers and git results, without sockets. */
    ForgejoScratch(Identity identity, Path work, Exchange exchange, Git runner) {
        this.identity = identity;
        this.work = work;
        this.exchange = exchange;
        this.runner = runner;
        this.http = null;
    }

    /** A transport seam so the requests can be checked without a listening server. */
    @FunctionalInterface
    interface Exchange {
        Reply send(HttpRequest request) throws IOException;
    }

    /** One HTTP answer; failures pass through token redaction before they are shown. */
    record Reply(int status, String body) {}

    /** The local checkout's git operations, replaced by recorded results in unit tests. */
    @FunctionalInterface
    interface Git {
        String run(List<String> arguments) throws IOException;
    }

    /** The HTTPS origin, test user and its token, and the one CA that signs the fixture. */
    record Identity(URI origin, String token, String owner, Path ca) {
        Identity {
            if (!"https".equals(origin.getScheme())
                    || origin.getHost() == null
                    || origin.getRawUserInfo() != null
                    || origin.getRawQuery() != null
                    || origin.getRawFragment() != null
                    || !(origin.getRawPath().isEmpty() || origin.getRawPath().equals("/"))) {
                throw new AssertionError(
                        "PORELO_FORGEJO_URL must be an HTTPS origin without credentials, query or"
                                + " fragment");
            }
            origin = origin.resolve("/");
            if (token.isBlank()) {
                throw new AssertionError("PORELO_FORGEJO_TEST_TOKEN is blank");
            }
            if (!owner.matches("[A-Za-z0-9][A-Za-z0-9_.-]*")) {
                throw new AssertionError("PORELO_FORGEJO_TEST_OWNER is not a Forgejo login");
            }
            ca = ca.toAbsolutePath();
        }

        /** Fails, never skips, when any required variable is missing or blank. */
        static Identity of(Function<String, @Nullable String> environment) {
            final String address = required(environment, "PORELO_FORGEJO_URL");
            final String token = required(environment, "PORELO_FORGEJO_TEST_TOKEN");
            final String owner = required(environment, "PORELO_FORGEJO_TEST_OWNER");
            final String ca = required(environment, "PORELO_FORGEJO_CA");
            final URI origin;
            try {
                origin = URI.create(address);
            } catch (IllegalArgumentException ex) {
                throw new AssertionError("PORELO_FORGEJO_URL is not an HTTPS origin");
            }
            return new Identity(origin, token, owner, Path.of(ca));
        }

        private static String required(
                Function<String, @Nullable String> environment, String variable) {
            final String value = environment.apply(variable);
            if (value == null || value.isBlank()) {
                throw new AssertionError(
                        "The environment variable "
                                + variable
                                + " is not set on the machine running this suite; the scenario on"
                                + " Forgejo needs it");
            }
            return value.strip();
        }

        @Override
        public String toString() {
            return "Identity[origin=" + origin + ", owner=" + owner + ", ca=" + ca + "]";
        }
    }

    /** Forgejo groups line comments by their path and new-side line. */
    record Anchor(String path, long line) {}

    /** One review comment; Forgejo has no line-thread reply endpoint or reply id here. */
    record Comment(long id, Anchor anchor, String login, String body) {}

    /** Makes the repository, pushes the same files as the GitHub fixture, and opens its PR. */
    void open() throws IOException {
        login = text(map(Json.parseExact(call("GET", "user", null, 200)), "the user"), "login");
        if (!identity.owner().equals(login)) {
            throw new AssertionError("the Forgejo token's account is not the test owner");
        }
        final byte[] random = new byte[6];
        new SecureRandom().nextBytes(random);
        final String name = "porelo-acceptance-" + HexFormat.of().formatHex(random);
        // Named before the call, so a repository made although its answer was lost is still
        // deleted.
        repository = identity.owner() + "/" + name;
        call(
                "POST",
                "user/repos",
                Json.write(
                        Map.of(
                                "name",
                                name,
                                "private",
                                true,
                                "auto_init",
                                false,
                                "default_branch",
                                GitHubScratch.BASE_BRANCH,
                                "description",
                                "A scratch repository of Porelo's acceptance suite")),
                201);
        Files.createDirectories(work);
        runner.run(List.of("init", "-q", "-b", GitHubScratch.BASE_BRANCH, "."));
        write(GitHubScratch.CHANGED, GitHubScratch.lines(false));
        write("src/main/java/org/example/shop/Session.java", GitHubScratch.lines(false));
        runner.run(List.of("add", "-A"));
        commit("acceptance: the base");
        base = runner.run(List.of("rev-parse", "HEAD")).strip();
        push(GitHubScratch.BASE_BRANCH);
        runner.run(List.of("checkout", "-q", "-b", GitHubScratch.HEAD_BRANCH));
        write(GitHubScratch.CHANGED, GitHubScratch.lines(true));
        runner.run(List.of("add", "-A"));
        commit("acceptance: a change to review");
        push(GitHubScratch.HEAD_BRANCH);
        // Forgejo records a pushed branch through a queue: a pull request opened before it does
        // answers 404.
        untilKnown(GitHubScratch.HEAD_BRANCH);
        number =
                number(
                        map(
                                        Json.parseExact(
                                                call(
                                                        "POST",
                                                        repo() + "/pulls",
                                                        Json.write(
                                                                Map.of(
                                                                        "title",
                                                                        "A change to review",
                                                                        "head",
                                                                        GitHubScratch.HEAD_BRANCH,
                                                                        "base",
                                                                        GitHubScratch.BASE_BRANCH,
                                                                        "body",
                                                                        "Opened by Porelo's"
                                                                                + " acceptance"
                                                                                + " suite.")),
                                                        201)),
                                        "the pull request")
                                .get("number"),
                        "the pull request's number");
    }

    /** Commits what the stub should do next to the pull request's head. */
    void commitToHead(String file, String text) throws IOException {
        repo();
        write(file, text);
        runner.run(List.of("add", file));
        commit("acceptance: what the stub does in its next run");
        push(GitHubScratch.HEAD_BRANCH);
        // Forgejo moves the pull request's head through a queue: a run that read the old head, or
        // a release against the new one, would find its item moved since the review.
        untilHead(runner.run(List.of("rev-parse", "HEAD")).strip());
    }

    /** Waits until the pull request names a commit as its head, at most a minute. */
    private void untilHead(String commit) throws IOException {
        final String path = repo() + "/pulls/" + number;
        @Nullable Object seen = null;
        for (int attempt = 0; attempt < 30; attempt++) {
            final Map<String, Object> pull =
                    map(Json.parseExact(call("GET", path, null, 200)), "the pull request");
            seen = map(pull.get("head"), "its head").get("sha");
            if (commit.equals(seen)) {
                return;
            }
            pause();
        }
        throw new AssertionError(
                "Forgejo's pull request did not move its head to "
                        + commit
                        + " (it names "
                        + seen
                        + ")");
    }

    String url() {
        repo();
        return identity.origin().resolve(repository + "/pulls/" + number).toString();
    }

    String base() {
        return Objects.requireNonNull(base, "no pull request on Forgejo yet");
    }

    String login() {
        return Objects.requireNonNull(login, "no pull request on Forgejo yet");
    }

    /**
     * Reads all pages of reviews and then each review's comments, in id order. A review's comments
     * come in one answer: Forgejo 17 ignores page and limit there and answers every page alike.
     */
    List<Comment> comments() throws IOException {
        final List<Comment> found = new ArrayList<>();
        for (final Object review : pages(repo() + "/pulls/" + number + "/reviews")) {
            final long id = number(map(review, "a review").get("id"), "a review's id");
            for (final Object comment :
                    array(
                            call(
                                    "GET",
                                    repo() + "/pulls/" + number + "/reviews/" + id + "/comments",
                                    null,
                                    200))) {
                found.add(comment(comment));
            }
        }
        found.sort(Comparator.comparingLong(Comment::id));
        return List.copyOf(found);
    }

    /**
     * Names every review of the pull request with its state and comment count, for a failure's
     * message: a review the adapter posted may hold no comment Forgejo lists.
     */
    String reviews() throws IOException {
        final StringBuilder text = new StringBuilder();
        for (final Object one : pages(repo() + "/pulls/" + number + "/reviews")) {
            final Map<?, ?> review = map(one, "a review");
            text.append("\n  review ")
                    .append(review.get("id"))
                    .append(" state=")
                    .append(review.get("state"))
                    .append(" comments=")
                    .append(review.get("comments_count"))
                    .append(" commit=")
                    .append(review.get("commit_id"));
        }
        return text.toString();
    }

    /** Waits for the gate and adapter to deliver the expected number of comments. */
    List<Comment> thread(int count) throws IOException {
        List<Comment> found = List.of();
        for (int attempt = 0; attempt < THREAD_TRIES; attempt++) {
            final List<Comment> all = comments();
            if (!all.isEmpty()) {
                found = thread(all, root(all));
                if (found.size() >= count) {
                    return found;
                }
            }
            pause();
        }
        return found;
    }

    /** Answers by opening a new review comment at the task's path and line. */
    void answer(String text) throws IOException {
        final Anchor anchor = root(comments());
        call(
                "POST",
                repo() + "/pulls/" + number + "/reviews",
                Json.write(
                        Map.of(
                                "event",
                                "COMMENT",
                                "comments",
                                List.of(
                                        Map.of(
                                                "path",
                                                anchor.path(),
                                                "new_position",
                                                anchor.line(),
                                                "old_position",
                                                0,
                                                "body",
                                                text)))),
                200);
    }

    /** Deletes the remote repository and always removes its local checkout. */
    void delete() throws IOException {
        try {
            if (repository != null) {
                call("DELETE", repo(), null, 204, 404);
                repository = null;
            }
        } finally {
            try {
                deleteTree(work);
            } finally {
                if (http != null) {
                    http.close();
                }
            }
        }
    }

    static List<Comment> comments(String json) {
        return array(json).stream()
                .map(ForgejoScratch::comment)
                .sorted(Comparator.comparingLong(Comment::id))
                .toList();
    }

    static Anchor root(List<Comment> comments) {
        final List<Anchor> anchors = comments.stream().map(Comment::anchor).distinct().toList();
        if (anchors.size() != 1) {
            throw new AssertionError(
                    "the pull request on Forgejo has " + anchors.size() + " threads, not one");
        }
        return anchors.getFirst();
    }

    static List<Comment> thread(List<Comment> comments, Anchor anchor) {
        return comments.stream()
                .filter(comment -> comment.anchor().equals(anchor))
                .sorted(Comparator.comparingLong(Comment::id))
                .toList();
    }

    /** Supplies git with the token and CA, disabling helpers, prompts and redirects. */
    static Map<String, String> gitEnvironment(Identity identity) {
        final Map<String, String> environment = new LinkedHashMap<>();
        environment.put("GIT_CONFIG_NOSYSTEM", "1");
        environment.put("GIT_CONFIG_GLOBAL", "/dev/null");
        environment.put("GIT_TERMINAL_PROMPT", "0");
        environment.put("GIT_CONFIG_COUNT", "3");
        environment.put("GIT_CONFIG_KEY_0", "http." + identity.origin() + ".extraHeader");
        environment.put("GIT_CONFIG_VALUE_0", "Authorization: Basic " + basic(identity));
        environment.put("GIT_CONFIG_KEY_1", "credential.helper");
        environment.put("GIT_CONFIG_VALUE_1", "");
        environment.put("GIT_CONFIG_KEY_2", "http.followRedirects");
        environment.put("GIT_CONFIG_VALUE_2", "false");
        environment.put("GIT_SSL_CAINFO", identity.ca().toString());
        environment.put("GIT_SSL_VERIFY", "true");
        return environment;
    }

    static String hide(String text, Identity identity) {
        return text.replace(basic(identity), "<header>").replace(identity.token(), "<token>");
    }

    /** Uses the fixture's CA, HTTPS hostname checks and no redirects. */
    static HttpClient client(Identity identity) throws IOException {
        try {
            final SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[] {trustManager(identity.ca())}, null);
            final SSLParameters parameters = new SSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            return HttpClient.newBuilder()
                    .connectTimeout(CALL)
                    .sslContext(context)
                    .sslParameters(parameters)
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
        } catch (GeneralSecurityException ex) {
            throw new IOException("cannot initialize TLS for the Forgejo test CA", ex);
        }
    }

    /** A fresh trust store with exactly one CA; the system roots are not added. */
    static X509TrustManager trustManager(Path pem) throws IOException {
        try (InputStream input = Files.newInputStream(pem)) {
            final var certificates =
                    CertificateFactory.getInstance("X.509").generateCertificates(input);
            if (certificates.size() != 1
                    || !(certificates.iterator().next() instanceof X509Certificate ca)
                    || ca.getBasicConstraints() < 0) {
                throw new IOException("PORELO_FORGEJO_CA must hold exactly one CA certificate");
            }
            ca.checkValidity();
            final KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
            store.load(null, null);
            store.setCertificateEntry("fixture", ca);
            final TrustManagerFactory managers =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            managers.init(store);
            final TrustManager[] trusted = managers.getTrustManagers();
            if (trusted.length != 1 || !(trusted[0] instanceof X509TrustManager manager)) {
                throw new IOException("no X.509 trust manager for the Forgejo test CA");
            }
            return manager;
        } catch (GeneralSecurityException | IOException ex) {
            throw new IOException(
                    "cannot load the single CA certificate from PORELO_FORGEJO_CA", ex);
        }
    }

    private static String basic(Identity identity) {
        return Base64.getEncoder()
                .encodeToString(
                        (identity.owner() + ":" + identity.token())
                                .getBytes(StandardCharsets.UTF_8));
    }

    private String repo() {
        if (repository == null) {
            throw new AssertionError("no repository on Forgejo yet");
        }
        return "repos/" + repository;
    }

    private void write(String file, String text) throws IOException {
        final Path path = work.resolve(file);
        Files.createDirectories(Objects.requireNonNull(path.getParent(), "parent"));
        Files.writeString(path, text, StandardCharsets.UTF_8);
    }

    private void commit(String message) throws IOException {
        runner.run(
                List.of(
                        "-c",
                        "user.email=acceptance@example.invalid",
                        "-c",
                        "user.name=acceptance",
                        "-c",
                        "commit.gpgsign=false",
                        "commit",
                        // Runs two and three say the same, so a commit may change nothing: still a
                        // new head.
                        "--allow-empty",
                        "-qm",
                        message));
    }

    private void push(String branch) throws IOException {
        runner.run(
                List.of(
                        "push",
                        "-q",
                        identity.origin().resolve(repository + ".git").toString(),
                        branch));
    }

    private String git(List<String> arguments) throws IOException {
        final ProcessBuilder builder =
                new ProcessBuilder(GitHubScratch.gitCommand(work, arguments))
                        .redirectErrorStream(true);
        final Map<String, String> environment = builder.environment();
        environment
                .keySet()
                .removeIf(
                        name ->
                                name.startsWith("GIT_")
                                        || name.startsWith("SSH_")
                                        || name.equals("DISPLAY")
                                        || name.equals("WAYLAND_DISPLAY"));
        environment.putAll(gitEnvironment(identity));
        final Process process = builder.start();
        process.getOutputStream().close();
        // Drain concurrently so a full pipe cannot defeat the process timeout. Output stays in
        // memory.
        final FutureTask<String> output =
                new FutureTask<>(
                        () ->
                                new String(
                                        process.getInputStream().readAllBytes(),
                                        StandardCharsets.UTF_8));
        Thread.ofVirtual().start(output);
        try {
            if (!process.waitFor(120, TimeUnit.SECONDS)) {
                throw new AssertionError("git " + arguments.getFirst() + " did not end");
            }
            final String text = output.get(5, TimeUnit.SECONDS);
            if (process.exitValue() != 0) {
                throw new AssertionError(
                        "git "
                                + arguments.getFirst()
                                + " exited "
                                + process.exitValue()
                                + ":\n"
                                + hide(text, identity));
            }
            return text;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while git ran", ex);
        } catch (ExecutionException | TimeoutException ex) {
            throw new IOException("cannot read git's output", ex);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
            output.cancel(true);
        }
    }

    private List<Object> pages(String path) throws IOException {
        final List<Object> all = new ArrayList<>();
        for (int page = 1; ; page++) {
            if (page > MAX_PAGES) {
                throw new AssertionError(path + " has more than " + MAX_PAGES + " pages");
            }
            final List<?> found = array(call("GET", path + "?limit=50&page=" + page, null, 200));
            if (found.isEmpty()) {
                return List.copyOf(all);
            }
            all.addAll(found);
        }
    }

    /** Waits until Forgejo lists a pushed branch, at most 30 seconds. */
    private void untilKnown(String branch) throws IOException {
        final String path = repo() + "/branches/" + branch;
        for (int attempt = 0; attempt < 30; attempt++) {
            final Reply reply = exchange.send(request("GET", path, null));
            if (reply.status() == 200) {
                return;
            }
            if (reply.status() != 404) {
                throw new AssertionError(
                        "GET "
                                + path
                                + " answered "
                                + reply.status()
                                + ": "
                                + hide(reply.body(), identity));
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted waiting for the branch " + branch, ex);
            }
        }
        throw new AssertionError("Forgejo did not list the pushed branch " + branch + " in 30 s");
    }

    private HttpRequest request(String method, String path, @Nullable String body) {
        return HttpRequest.newBuilder(identity.origin().resolve("api/v1/" + path))
                .timeout(CALL)
                .header("Accept", "application/json")
                .header("Authorization", "token " + identity.token())
                .header("Content-Type", "application/json")
                .method(
                        method,
                        body == null
                                ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private String call(String method, String path, @Nullable String body, int... expected)
            throws IOException {
        final Reply response = exchange.send(request(method, path, body));
        for (final int status : expected) {
            if (response.status() == status) {
                return response.body();
            }
        }
        throw new AssertionError(
                method
                        + " "
                        + path
                        + " answered "
                        + response.status()
                        + ": "
                        + hide(response.body(), identity));
    }

    private static Comment comment(Object value) {
        final Map<String, Object> comment = map(value, "a review comment");
        return new Comment(
                number(comment.get("id"), "a comment's id"),
                new Anchor(
                        text(comment, "path"),
                        number(comment.get("position"), "a comment's position")),
                text(map(comment.get("user"), "a comment's user"), "login"),
                text(comment, "body"));
    }

    private static List<?> array(String json) {
        if (!(Json.parseExact(json) instanceof List<?> found)) {
            throw new AssertionError("Forgejo's answer is not a JSON array");
        }
        return found;
    }

    private static Map<String, Object> map(@Nullable Object value, String what) {
        if (!(value instanceof Map<?, ?> found)) {
            throw new AssertionError(what + " is not a JSON object");
        }
        final Map<String, Object> copy = new LinkedHashMap<>();
        found.forEach((key, one) -> copy.put(String.valueOf(key), one));
        return copy;
    }

    private static String text(Map<String, Object> object, String field) {
        if (!(object.get(field) instanceof String value)) {
            throw new AssertionError(field + " is not a JSON string");
        }
        return value;
    }

    private static long number(@Nullable Object value, String what) {
        if (!(value instanceof Long number)) {
            throw new AssertionError(what + " is not a whole number");
        }
        return number;
    }

    private static void pause() throws IOException {
        try {
            Thread.sleep(Duration.ofSeconds(2));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while waiting for Forgejo", ex);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walked = Files.walk(root)) {
            walked.sorted(Comparator.reverseOrder())
                    .forEach(
                            path -> {
                                try {
                                    Files.delete(path);
                                } catch (IOException ex) {
                                    throw new UncheckedIOException(ex);
                                }
                            });
        } catch (UncheckedIOException ex) {
            throw ex.getCause();
        }
    }
}
