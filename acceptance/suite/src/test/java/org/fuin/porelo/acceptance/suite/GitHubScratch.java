package org.fuin.porelo.acceptance.suite;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;

/**
 * A scratch repository in a test organization on GitHub with one pull request, made, answered, read
 * back and removed from the host the suite runs on, never through Porelo or a task.
 *
 * <p>The token reaches GitHub in a request header and git through its environment, as an {@code
 * http.extraHeader}; never in an argument, an address, a log line or a message.
 */
final class GitHubScratch {

    /** The host Porelo's forge file and vault name GitHub by. */
    static final String HOST = "github.com";

    /** The branch the pull request is opened against. */
    static final String BASE_BRANCH = "main";

    /** The branch the pull request is opened from. */
    static final String HEAD_BRANCH = "review-head";

    /** The file the pull request changes, around the lines the review fixtures anchor in. */
    static final String CHANGED = "src/main/java/org/example/shop/Login.java";

    private static final URI API = URI.create("https://api.github.com/");

    private static final Pattern ORGANIZATION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9-]*");

    private static final Duration CALL = Duration.ofSeconds(30);

    private static final int TRIES = 15;

    private static final Duration PAUSE = Duration.ofSeconds(2);

    private final Identity identity;

    private final Path work;

    private final HttpClient http;

    private @Nullable String repository;

    private @Nullable String login;

    private @Nullable String base;

    private long number;

    private @Nullable Long root;

    /**
     * Creates the helper; nothing is made on GitHub until {@link #open}.
     *
     * @param identity The test identity.
     * @param work An empty directory of the suite's host the checkout is made in.
     */
    GitHubScratch(Identity identity, Path work) {
        this.identity = Objects.requireNonNull(identity, "identity");
        this.work = Objects.requireNonNull(work, "work");
        this.http = HttpClient.newBuilder().connectTimeout(CALL).build();
    }

    /**
     * The token and organization the scenario runs with.
     *
     * @param token The test identity's token.
     * @param organization The test organization it may make and remove repositories in.
     */
    record Identity(String token, String organization) {

        /** Checks the organization is a GitHub login, so it is safe in an address. */
        Identity {
            Objects.requireNonNull(token, "token");
            if (!ORGANIZATION.matcher(organization).matches()) {
                throw new AssertionError("the test organization is not a GitHub login");
            }
        }

        /**
         * Reads both from the environment, failing loud, never skipping, when either is missing.
         *
         * @param environment The environment, as {@link System#getenv(String)} reads it.
         * @param token The variable holding the token.
         * @param organization The variable holding the organization.
         * @return Them.
         */
        static Identity of(
                Function<String, @Nullable String> environment, String token, String organization) {
            return new Identity(required(environment, token), required(environment, organization));
        }

        private static String required(
                Function<String, @Nullable String> environment, String variable) {
            final String value = environment.apply(variable);
            if (value == null || value.isBlank()) {
                throw new AssertionError(
                        "The environment variable "
                                + variable
                                + " is not set on the machine running this suite; the scenario on"
                                + " GitHub needs it");
            }
            return value.strip();
        }

        @Override
        public String toString() {
            return "Identity[organization=" + organization + "]";
        }
    }

    /**
     * One review comment as GitHub answers it.
     *
     * @param id Its REST id.
     * @param inReplyTo The REST id of its thread's root, {@code null} for a root.
     * @param login The account that wrote it.
     * @param body Its text.
     */
    record Comment(long id, @Nullable Long inReplyTo, String login, String body) {}

    /**
     * Makes the repository with a base commit, pushes a branch that changes the lines the review
     * fixtures anchor in, and opens the pull request from it.
     *
     * @throws IOException If GitHub cannot be reached.
     */
    void open() throws IOException {
        login = text(map(Json.parseExact(call("GET", "user", null, 200)), "the user"), "login");
        final String name = "porelo-acceptance-" + HexFormat.of().formatHex(random(6));
        call(
                "POST",
                "orgs/" + identity.organization() + "/repos",
                Json.write(
                        Map.of(
                                "name",
                                name,
                                "private",
                                true,
                                "auto_init",
                                false,
                                "description",
                                "A scratch repository of Porelo's acceptance suite")),
                201);
        repository = identity.organization() + "/" + name;
        Files.createDirectories(work);
        git(List.of("init", "-q", "-b", BASE_BRANCH, "."));
        write(CHANGED, lines(false));
        write("src/main/java/org/example/shop/Session.java", lines(false));
        git(List.of("add", "-A"));
        commit("acceptance: the base");
        base = git(List.of("rev-parse", "HEAD")).strip();
        push(BASE_BRANCH);
        git(List.of("checkout", "-q", "-b", HEAD_BRANCH));
        write(CHANGED, lines(true));
        git(List.of("add", "-A"));
        commit("acceptance: a change to review");
        push(HEAD_BRANCH);
        final String body =
                Json.write(
                        Map.of(
                                "title",
                                "A change to review",
                                "head",
                                HEAD_BRANCH,
                                "base",
                                BASE_BRANCH,
                                "body",
                                "Opened by Porelo's acceptance suite."));
        number = pullRequestNumber(retried("POST", repo() + "/pulls", body, 201));
    }

    /**
     * Commits a file at the head's root and pushes it, so the pull request's head carries it.
     *
     * @param file The file's name.
     * @param text Its text.
     * @throws IOException If the checkout or GitHub cannot be reached.
     */
    void commitToHead(String file, String text) throws IOException {
        repo();
        write(file, text);
        git(List.of("add", file));
        commit("acceptance: what the stub does in its next run");
        push(HEAD_BRANCH);
    }

    /**
     * Returns the pull request's address.
     *
     * @return It.
     */
    String url() {
        return "https://" + HOST + "/" + repo() + "/pull/" + number;
    }

    /**
     * Returns the base branch's commit, where the head left it.
     *
     * @return The commit id.
     */
    String base() {
        if (base == null) {
            throw new AssertionError("no pull request on GitHub yet");
        }
        return base;
    }

    /**
     * Returns the test identity's login, as GitHub names the token's account.
     *
     * @return It.
     */
    String login() {
        if (login == null) {
            throw new AssertionError("no pull request on GitHub yet");
        }
        return login;
    }

    /**
     * Reads every review comment on the pull request.
     *
     * @return Them, in the order GitHub made them.
     * @throws IOException If GitHub cannot be reached.
     */
    List<Comment> comments() throws IOException {
        return comments(
                call("GET", repo() + "/pulls/" + number + "/comments?per_page=100", null, 200));
    }

    /**
     * Reads the thread the task opened, waiting until it has at least a number of comments, since a
     * release reaches GitHub through the adapter.
     *
     * @param count How many comments to wait for.
     * @return The thread, its root first.
     * @throws IOException If GitHub cannot be reached.
     */
    List<Comment> thread(int count) throws IOException {
        List<Comment> thread = List.of();
        for (int i = 0; i < TRIES; i++) {
            final List<Comment> all = comments();
            if (!all.isEmpty()) {
                thread = thread(all, root(all));
                if (thread.size() >= count) {
                    return thread;
                }
            }
            pause();
        }
        return thread;
    }

    /**
     * Posts the test identity's answer in the task's thread.
     *
     * @param text What it says.
     * @throws IOException If GitHub cannot be reached.
     */
    void answer(String text) throws IOException {
        final long thread = root(comments());
        call(
                "POST",
                repo() + "/pulls/" + number + "/comments/" + thread + "/replies",
                Json.write(Map.of("body", text)),
                201);
    }

    /**
     * Removes the repository on GitHub and the checkout here; what was never made is skipped.
     *
     * @throws IOException If GitHub cannot be reached.
     */
    void delete() throws IOException {
        try {
            if (repository != null) {
                call("DELETE", "repos/" + repository, null, 204, 404);
                repository = null;
            }
        } finally {
            deleteTree(work);
        }
    }

    /**
     * Reads the review comments of a GitHub answer.
     *
     * @param json The answer, a JSON array.
     * @return The comments, by their ids.
     */
    static List<Comment> comments(String json) {
        if (!(Json.parseExact(json) instanceof List<?> listed)) {
            throw new AssertionError("GitHub's review comments are not a JSON array");
        }
        final List<Comment> read = new ArrayList<>();
        for (final Object one : listed) {
            final Map<String, Object> comment = map(one, "a review comment");
            read.add(
                    new Comment(
                            number(comment.get("id"), "a comment's id"),
                            comment.get("in_reply_to_id") == null
                                    ? null
                                    : number(comment.get("in_reply_to_id"), "in_reply_to_id"),
                            text(map(comment.get("user"), "a comment's user"), "login"),
                            text(comment, "body")));
        }
        read.sort(Comparator.comparingLong(Comment::id));
        return List.copyOf(read);
    }

    /**
     * Returns the one thread's root: the only comment that answers none.
     *
     * @param comments The pull request's review comments.
     * @return Its REST id.
     */
    static long root(List<Comment> comments) {
        final List<Comment> roots =
                comments.stream().filter(one -> one.inReplyTo() == null).toList();
        if (roots.size() != 1) {
            throw new AssertionError(
                    "the pull request on GitHub has " + roots.size() + " threads, not one");
        }
        return roots.getFirst().id();
    }

    /**
     * Returns a thread in the order it was written: its root, then every answer to it.
     *
     * @param comments The pull request's review comments.
     * @param root The thread's root.
     * @return Them.
     */
    static List<Comment> thread(List<Comment> comments, long root) {
        return comments.stream()
                .filter(one -> one.id() == root || Objects.equals(one.inReplyTo(), root))
                .sorted(Comparator.comparingLong(Comment::id))
                .toList();
    }

    /**
     * Returns what git is given in its environment to reach GitHub with the token: a header for
     * GitHub's address alone, no credential helper or prompt of the host's, and none of the host's
     * own git settings.
     *
     * @param token The token.
     * @return The variables.
     */
    static Map<String, String> gitEnvironment(String token) {
        final Map<String, String> environment = new LinkedHashMap<>();
        environment.put("GIT_CONFIG_NOSYSTEM", "1");
        environment.put("GIT_CONFIG_GLOBAL", "/dev/null");
        environment.put("GIT_TERMINAL_PROMPT", "0");
        environment.put("GIT_CONFIG_COUNT", "2");
        environment.put("GIT_CONFIG_KEY_0", "http.https://" + HOST + "/.extraHeader");
        environment.put("GIT_CONFIG_VALUE_0", "Authorization: Basic " + basic(token));
        // An empty helper list: the host's helpers never see GitHub's address.
        environment.put("GIT_CONFIG_KEY_1", "credential.helper");
        environment.put("GIT_CONFIG_VALUE_1", "");
        return environment;
    }

    /**
     * Builds the command line of a git call in a checkout.
     *
     * @param checkout The checkout.
     * @param arguments What git is asked.
     * @return The command line.
     */
    static List<String> gitCommand(Path checkout, List<String> arguments) {
        return Stream.concat(Stream.of("git", "-C", checkout.toString()), arguments.stream())
                .toList();
    }

    /**
     * Makes a file of numbered lines, the pull request's head changing those around the fixtures'
     * anchors so GitHub takes a comment on them.
     *
     * @param changed Whether it is the head's version.
     * @return The text.
     */
    static String lines(boolean changed) {
        final StringBuilder text = new StringBuilder();
        for (int line = 1; line <= 120; line++) {
            text.append("line ").append(line);
            if (changed && line >= 20 && line <= 50) {
                text.append(", changed");
            }
            text.append('\n');
        }
        return text.toString();
    }

    /**
     * Reads the number of a pull request GitHub made.
     *
     * @param json GitHub's answer.
     * @return Its number.
     */
    static long pullRequestNumber(String json) {
        return number(map(Json.parseExact(json), "the pull request").get("number"), "its number");
    }

    /**
     * Replaces the token, and the header git is given, in a text a failure shows.
     *
     * @param text The text.
     * @return It without them.
     */
    String hide(String text) {
        return hide(text, identity.token());
    }

    /**
     * Replaces a token, and the header git is given for it, in a text.
     *
     * @param text The text.
     * @param token The token.
     * @return It without them.
     */
    static String hide(String text, String token) {
        return text.replace(basic(token), "<header>").replace(token, "<token>");
    }

    @Override
    public String toString() {
        return "GitHubScratch[repository=" + repository + ", pullRequest=" + number + "]";
    }

    private static String basic(String token) {
        return Base64.getEncoder()
                .encodeToString(("x-access-token:" + token).getBytes(StandardCharsets.UTF_8));
    }

    private String repo() {
        if (repository == null) {
            throw new AssertionError("no repository on GitHub yet");
        }
        return "repos/" + repository;
    }

    private void write(String file, String text) throws IOException {
        final Path path = work.resolve(file);
        Files.createDirectories(Objects.requireNonNull(path.getParent(), "parent"));
        Files.writeString(path, text, StandardCharsets.UTF_8);
    }

    private void commit(String message) throws IOException {
        git(
                List.of(
                        "-c",
                        "user.email=acceptance@example.invalid",
                        "-c",
                        "user.name=acceptance",
                        "-c",
                        "commit.gpgsign=false",
                        "commit",
                        "-qm",
                        message));
    }

    /** Pushes a branch, again while a repository GitHub just made is not yet ready for it. */
    private void push(String branch) throws IOException {
        final List<String> push =
                List.of("push", "-q", "https://" + HOST + "/" + repository + ".git", branch);
        @Nullable AssertionError last = null;
        for (int i = 0; i < TRIES; i++) {
            try {
                git(push);
                return;
            } catch (AssertionError ex) {
                last = ex;
                pause();
            }
        }
        throw Objects.requireNonNull(last, "a push was tried");
    }

    private String git(List<String> arguments) throws IOException {
        final ProcessBuilder builder =
                new ProcessBuilder(gitCommand(work, arguments)).redirectErrorStream(true);
        final Map<String, String> environment = builder.environment();
        // No window, agent or prompt of the host's is reached from here.
        environment.remove("SSH_AUTH_SOCK");
        environment.remove("DISPLAY");
        environment.remove("WAYLAND_DISPLAY");
        environment.remove("GIT_ASKPASS");
        environment.remove("SSH_ASKPASS");
        environment.putAll(gitEnvironment(identity.token()));
        final Process process = builder.start();
        process.getOutputStream().close();
        final String output;
        try {
            output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(120, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new AssertionError("git " + arguments.getFirst() + " did not end");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("interrupted while git ran", ex);
        }
        if (process.exitValue() != 0) {
            throw new AssertionError(
                    "git "
                            + arguments.getFirst()
                            + " exited "
                            + process.exitValue()
                            + ":\n"
                            + hide(output));
        }
        return output;
    }

    /** Calls GitHub again while it answers 422, which it does until a new branch is indexed. */
    private String retried(String method, String path, String body, int expected)
            throws IOException {
        @Nullable AssertionError last = null;
        for (int i = 0; i < TRIES; i++) {
            try {
                return call(method, path, body, expected);
            } catch (AssertionError ex) {
                if (!String.valueOf(ex.getMessage()).contains(" answered 422")) {
                    throw ex;
                }
                last = ex;
                pause();
            }
        }
        throw Objects.requireNonNull(last, "a call was tried");
    }

    private String call(String method, String path, @Nullable String body, int... expected)
            throws IOException {
        final HttpRequest request =
                HttpRequest.newBuilder(API.resolve(path))
                        .timeout(CALL)
                        .header("Accept", "application/vnd.github+json")
                        .header("X-GitHub-Api-Version", "2022-11-28")
                        .header("User-Agent", "porelo-acceptance")
                        .header("Authorization", "Bearer " + identity.token())
                        .header("Content-Type", "application/json")
                        .method(
                                method,
                                body == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(body))
                        .build();
        final HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while calling GitHub", ex);
        }
        for (final int one : expected) {
            if (response.statusCode() == one) {
                return response.body();
            }
        }
        throw new AssertionError(
                method
                        + " "
                        + path
                        + " answered "
                        + response.statusCode()
                        + ": "
                        + hide(response.body()));
    }

    private static void pause() throws IOException {
        try {
            Thread.sleep(PAUSE);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while waiting for GitHub", ex);
        }
    }

    private static byte[] random(int bytes) {
        final byte[] made = new byte[bytes];
        new SecureRandom().nextBytes(made);
        return made;
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
}
