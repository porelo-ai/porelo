package org.fuin.porelo.acceptance.suite;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.fuin.porelo.machines.Ssh;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;

/**
 * The stub forge adapter's review side as a scenario sees it: the answers it gives about a pull
 * request, which the scenario writes, and the log of what it wrote to the forge, which the scenario
 * reads.
 *
 * <p>Every write of the answers file and every read of the write log goes through here, and the
 * files' formats are each left to one method, so that the adapter's format is settled in one place.
 * The pages and comments are the review profile's own fixtures.
 */
final class ForgeStub {

    /** Where the adapter's files lie on the machine, beside the build reader of the same name. */
    static final String DIRECTORY = "\"${XDG_DATA_HOME:-$HOME/.local/share}/porelo/builds\"";

    /** The fixture whose forge account's item every forge account's item here is made from. */
    static final String FORGE_PAGE = "forge-page.sarif.json";

    /** The first native id a thread opened here gets; the forge gives such numbers. */
    private static final long FIRST_THREAD = 9_100_000_001L;

    private final Script script;

    private final @Nullable StubGitForge git;

    /** The native ids of every thread an answer here opened or answered, in order. */
    private final List<String> threads = new ArrayList<>();

    /**
     * Creates the helper over a scenario's connection.
     *
     * @param script What runs a command on the machine.
     */
    ForgeStub(Script script) {
        this(script, null);
    }

    /**
     * Creates the helper over a scenario's connection, with the forge's git side its pull requests'
     * repositories are served from.
     *
     * @param script What runs a command on the machine.
     * @param git The stub git forge, started; {@code null} when no pull request is given here.
     */
    ForgeStub(Script script, @Nullable StubGitForge git) {
        this.script = Objects.requireNonNull(script, "script");
        this.git = git;
    }

    /**
     * Says the adapter has a pull request, open, with no conversation yet, its repository served by
     * the stub git forge and its facts that repository's commits: the branch's head, {@code main},
     * and their merge base.
     *
     * <p>The stub answers no opener: a write goes out under the account its token stands for, which
     * {@link #account} names.
     *
     * @param url The pull request's https address, on the stub git forge.
     * @param openedBy The forge account that opened it.
     * @param repository The repository on the machine its head is read from, for the shell; for a
     *     pull request from the task's branch, the repository the task's pushes reach.
     * @param branch The branch it is opened from.
     * @throws IOException If the machine cannot be reached.
     */
    void pullRequest(String url, String openedBy, @Nullable String repository, String branch)
            throws IOException {
        if (git == null) {
            throw new AssertionError(
                    "the pull request " + url + " needs the stub git forge started first");
        }
        if (repository == null) {
            throw new AssertionError(
                    "the pull request "
                            + url
                            + " names no repository on the machine: for the task's branch, name"
                            + " the repository its pushes reach");
        }
        write(
                answersWithPullRequest(
                        answers(), url, git.serve(url, repository, branch, "main"), branch));
    }

    /**
     * Says which forge account a token stands for, so a write made with it goes out, and is logged,
     * under that account; the answers hold the token's digest, never the token.
     *
     * @param token The token, as the vault holds it.
     * @param account The forge account's login.
     * @throws IOException If the machine cannot be reached.
     */
    void account(String token, String account) throws IOException {
        write(answersWithAccount(answers(), token, account));
    }

    /**
     * Appends a forge account's new thread, as a profile 2 page of one result.
     *
     * @param url The pull request.
     * @param account The forge account that writes it.
     * @param text What it says.
     * @return The new thread's native id.
     * @throws IOException If the machine cannot be reached.
     */
    String comment(String url, String account, String text) throws IOException {
        final String thread = String.valueOf(FIRST_THREAD + threads.size());
        page(url, forgeItem(fixture(FORGE_PAGE), url, account, thread, false, text));
        threads.add(thread);
        return thread;
    }

    /**
     * Appends a forge account's answer to a thread, as a profile 2 page of one result.
     *
     * @param url The pull request.
     * @param account The forge account that answers.
     * @param thread The native id of the thread it answers.
     * @param text What it says.
     * @throws IOException If the machine cannot be reached.
     */
    void answer(String url, String account, String thread, String text) throws IOException {
        page(url, forgeItem(fixture(FORGE_PAGE), url, account, thread, true, text));
        threads.add(thread);
    }

    /**
     * Sets the pull request's state as the adapter answers it.
     *
     * @param url The pull request.
     * @param state {@code open}, {@code merged} or {@code closed}.
     * @throws IOException If the machine cannot be reached.
     */
    void state(String url, String state) throws IOException {
        write(answersWithState(answers(), url, state, java.time.Instant.now()));
    }

    /**
     * Reads what the adapter wrote to the forge for a pull request, in the order it wrote it.
     *
     * @param url The pull request.
     * @return Each write.
     * @throws IOException If the machine cannot be reached.
     */
    List<Written> written(String url) throws IOException {
        final Ssh.Output log =
                script.run("cat " + DIRECTORY + "/" + writeLogFile() + " 2>/dev/null; true");
        if (log.status() != 0) {
            throw new AssertionError("the stub forge's write log could not be read:\n" + log.err());
        }
        return writeLog(log.out()).stream().filter(one -> one.pullRequest().equals(url)).toList();
    }

    /**
     * Removes the answers and the write log.
     *
     * @throws IOException If the machine cannot be reached.
     */
    void clear() throws IOException {
        final Ssh.Output removed =
                script.run(
                        "rm -f -- "
                                + DIRECTORY
                                + "/"
                                + answersFile()
                                + " "
                                + DIRECTORY
                                + "/"
                                + writeLogFile());
        if (removed.status() != 0) {
            throw new AssertionError(
                    "the stub forge's files could not be removed:\n" + removed.all());
        }
        threads.clear();
    }

    /**
     * Returns the native ids of every thread a forge account opened or answered here.
     *
     * @return The ids, in order, repeated where a thread was answered again.
     */
    List<String> threads() {
        return List.copyOf(threads);
    }

    private void page(String url, String page) throws IOException {
        write(answersWithPage(answers(), url, page));
    }

    private String answers() throws IOException {
        final Ssh.Output read =
                script.run("cat " + DIRECTORY + "/" + answersFile() + " 2>/dev/null; true");
        if (read.status() != 0) {
            throw new AssertionError("the stub forge's answers could not be read:\n" + read.err());
        }
        return read.out();
    }

    private void write(String answers) throws IOException {
        final Ssh.Output written = script.run(writeCommand(answersFile()), answers);
        if (written.status() != 0) {
            throw new AssertionError(
                    "the stub forge's answers could not be written:\n" + written.all());
        }
    }

    /**
     * Builds the command that replaces a file of the adapter's with standard input, at once: a
     * half-written file would be read by an adapter polling it.
     *
     * @param file The file's name in {@link #DIRECTORY}.
     * @return The command.
     */
    static String writeCommand(String file) {
        return "set -e; d="
                + DIRECTORY
                + "; mkdir -p \"$d\"; t=$(mktemp \"$d/.answers.XXXXXX\"); cat > \"$t\"; mv -f \"$t\" \"$d/"
                + file
                + "\"";
    }

    /**
     * Reads one of the review profile's fixtures.
     *
     * @param name Its file name.
     * @return Its text.
     */
    static String fixture(String name) {
        try (InputStream in = ForgeStub.class.getResourceAsStream("review/" + name)) {
            if (in == null) {
                throw new AssertionError("no review fixture " + name + " on the classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new AssertionError("the review fixture " + name + " could not be read", ex);
        }
    }

    /**
     * Makes a profile 2 page of one forge account's item from the fixture's page, at the pull
     * request the scenario names: a new thread, or an answer to one.
     *
     * @param page The fixture's page.
     * @param url The pull request.
     * @param account The forge account's login.
     * @param thread The thread's native id.
     * @param answers Whether the item answers the thread rather than opening it.
     * @param text What it says.
     * @return The page.
     */
    static String forgeItem(
            String page, String url, String account, String thread, boolean answers, String text) {
        final Map<String, Object> document = map(Json.parse(page), "the page");
        final Map<String, Object> run = map(list(document.get("runs"), "runs").get(0), "the run");
        final Map<String, Object> runProperties =
                map(run.get("properties"), "the run's properties");
        runProperties.put("porelo/pullRequest", url);
        run.put("properties", runProperties);
        final Map<String, Object> item =
                list(run.get("results"), "results").stream()
                        .map(one -> map(one, "a result"))
                        .filter(
                                one ->
                                        "forge-account"
                                                .equals(
                                                        map(
                                                                        one.get("properties"),
                                                                        "a result's properties")
                                                                .get("porelo/sourceKind")))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new AssertionError(
                                                "the fixture's page holds no forge account's item"));
        final Map<String, Object> properties = map(item.get("properties"), "the item's properties");
        properties.put("porelo/threadId", thread);
        properties.put("porelo/threadState", "open");
        properties.put("porelo/forgeAccountName", account);
        if (answers) {
            properties.put("porelo/replyTo", thread);
        } else {
            properties.remove("porelo/replyTo");
        }
        item.put("properties", properties);
        item.put("message", Map.of("text", text));
        item.put(
                "guid",
                UUID.nameUUIDFromBytes((thread + answers + text).getBytes(StandardCharsets.UTF_8))
                        .toString());
        run.put("results", List.of(item));
        document.put("runs", List.of(run));
        return Json.write(document);
    }

    private static Map<String, Object> map(@Nullable Object value, String what) {
        if (!(value instanceof Map<?, ?> found)) {
            throw new AssertionError(what + " is not a JSON object");
        }
        final Map<String, Object> copy = new LinkedHashMap<>();
        found.forEach((key, one) -> copy.put(String.valueOf(key), one));
        return copy;
    }

    private static List<Object> list(@Nullable Object value, String what) {
        if (!(value instanceof List<?> found) || found.isEmpty()) {
            throw new AssertionError(what + " is not a JSON array with an element");
        }
        return new ArrayList<>(found);
    }

    // The adapter's file formats, one method each; the adapter's review side settles them.

    /** The answers file's name in {@link #DIRECTORY}. */
    static String answersFile() {
        // The stub forge adapter answers its build and review calls from one file beside it.
        return "answers.json";
    }

    /** The write log's name in {@link #DIRECTORY}. */
    static String writeLogFile() {
        return "review-posts.jsonl";
    }

    /**
     * Adds a pull request to the answers, open from its branch, keeping any conversation it has.
     *
     * @param answers The answers as they are, empty when there are none.
     * @param url The pull request.
     * @param facts Its repository and commits.
     * @param branch Its branch.
     * @return The answers with it.
     */
    static String answersWithPullRequest(String answers, String url, Facts facts, String branch) {
        final Map<String, Object> read = read(answers);
        final Map<String, Object> review = review(read, url);
        final Map<String, Object> written = new LinkedHashMap<>();
        written.put("repository", facts.repository());
        written.put("head", facts.head());
        written.put("base", facts.base());
        written.put("mergeBase", facts.mergeBase());
        written.put("state", "open");
        written.put("approved", false);
        review.put("facts", written);
        final Map<String, Object> open =
                read.get("openPullRequests") == null
                        ? new LinkedHashMap<>()
                        : map(read.get("openPullRequests"), "the open pull requests");
        open.put(branch, url);
        read.put("openPullRequests", open);
        return Json.write(read);
    }

    /**
     * Says in the answers which forge account a token stands for, by the token's digest.
     *
     * @param answers The answers as they are.
     * @param token The token.
     * @param account The forge account's login.
     * @return The answers with it.
     */
    static String answersWithAccount(String answers, String token, String account) {
        final Map<String, Object> read = read(answers);
        final Map<String, Object> accounts =
                read.get("accounts") == null
                        ? new LinkedHashMap<>()
                        : map(read.get("accounts"), "the accounts");
        final Map<String, Object> named = new LinkedHashMap<>();
        // A forge's account ids are numbers; one made from the login keeps it the same each time.
        named.put("porelo/forgeAccountId", Integer.toUnsignedString(account.hashCode()));
        named.put("porelo/forgeAccountName", account);
        accounts.put(tokenKey(token), named);
        read.put("accounts", accounts);
        return Json.write(read);
    }

    /** What the answers name a token by: its SHA-256, never the token. */
    static String tokenKey(String token) {
        try {
            return "sha256:"
                    + java.util.HexFormat.of()
                            .formatHex(
                                    java.security.MessageDigest.getInstance("SHA-256")
                                            .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("every Java platform has SHA-256", ex);
        }
    }

    /**
     * A pull request's facts as the stub answers them.
     *
     * @param repository The repository the pin fetches from, on the pull request's own forge.
     * @param head Its head commit.
     * @param base Its base branch's commit.
     * @param mergeBase Where its head left its base.
     */
    record Facts(String repository, String head, String base, String mergeBase) {}

    /**
     * Appends a conversation page to a pull request's answers.
     *
     * @param answers The answers as they are.
     * @param url The pull request.
     * @param page A profile 2 page.
     * @return The answers with it.
     */
    static String answersWithPage(String answers, String url, String page) {
        final Map<String, Object> read = read(answers);
        final Map<String, Object> review = review(read, url);
        final List<Object> pages =
                review.get("pages") instanceof List<?> listed
                        ? new ArrayList<>(listed)
                        : new ArrayList<>();
        final Map<String, Object> added = map(Json.parse(page), "the page");
        if (pages.isEmpty()) {
            pages.add(added);
        } else {
            // One page that grows: pages before the last say they are partial, and the stub
            // splits a page past its bounds by itself.
            final Map<String, Object> last = map(pages.getLast(), "the last page");
            final Map<String, Object> run = map(list(last.get("runs"), "runs").get(0), "its run");
            final List<Object> results =
                    run.get("results") instanceof List<?> held
                            ? new ArrayList<>(held)
                            : new ArrayList<>();
            results.addAll(
                    list(
                            map(list(added.get("runs"), "runs").get(0), "the run").get("results"),
                            "results"));
            run.put("results", results);
            last.put("runs", List.of(run));
            pages.set(pages.size() - 1, last);
        }
        review.put("pages", pages);
        return Json.write(read);
    }

    /**
     * Sets a pull request's state in the answers.
     *
     * @param answers The answers as they are.
     * @param url The pull request.
     * @param state Its state.
     * @param decidedAt When a merged or closed one was decided.
     * @return The answers with it.
     */
    static String answersWithState(
            String answers, String url, String state, java.time.Instant decidedAt) {
        final Map<String, Object> read = read(answers);
        final Map<String, Object> review = review(read, url);
        final Map<String, Object> facts = map(review.get("facts"), "the pull request's facts");
        facts.put("state", state);
        if ("merged".equals(state)) {
            // A merge commit and its branch are given exactly when merged; the head stands in.
            facts.put("mergeCommit", facts.get("head"));
            facts.put("mergedHead", "main");
        } else {
            facts.remove("mergeCommit");
            facts.remove("mergedHead");
        }
        if ("merged".equals(state) || "closed".equals(state)) {
            // The facts refuse a decided pull request without its time, in whole seconds, UTC.
            facts.put(
                    "decidedAt",
                    decidedAt.truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString());
        } else {
            facts.remove("decidedAt");
        }
        review.put("facts", facts);
        return Json.write(read);
    }

    /**
     * Reads the write log.
     *
     * @param log Its text, empty when the adapter wrote nothing.
     * @return Each write, in order.
     */
    static List<Written> writeLog(String log) {
        final List<Written> written = new ArrayList<>();
        for (final String line : log.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            final Map<String, Object> post = map(Json.parse(line), "a line of the write log");
            final Map<String, Object> document = map(post.get("document"), "its document");
            final Map<String, Object> result =
                    map(
                            list(
                                            map(
                                                            list(document.get("runs"), "runs")
                                                                    .get(0),
                                                            "its run")
                                                    .get("results"),
                                            "results")
                                    .get(0),
                            "its result");
            final Map<String, Object> properties =
                    map(result.get("properties"), "its result's properties");
            written.add(
                    new Written(
                            String.valueOf(post.get("url")),
                            properties.containsKey("porelo/replyTo") ? "reply" : "comment",
                            // Absent from a line the stub wrote before it named the account.
                            post.get("account") instanceof String account ? account : "",
                            String.valueOf(post.get("thread"))));
        }
        return written;
    }

    private static Map<String, Object> read(String answers) {
        return answers.isBlank() ? new LinkedHashMap<>() : map(Json.parse(answers), "the answers");
    }

    /** The pull request's answers, made when missing, put into the answers it is taken from. */
    private static Map<String, Object> review(Map<String, Object> answers, String url) {
        final Map<String, Object> reviews =
                answers.get("reviews") == null
                        ? new LinkedHashMap<>()
                        : map(answers.get("reviews"), "the reviews");
        final Map<String, Object> review =
                reviews.get(url) == null
                        ? new LinkedHashMap<>()
                        : map(reviews.get(url), "the pull request's answers");
        reviews.put(url, review);
        answers.put("reviews", reviews);
        return review;
    }

    /**
     * One write the adapter made to the forge.
     *
     * @param pullRequest The pull request it wrote to.
     * @param operation {@code comment} or {@code reply}.
     * @param account The forge account it wrote under.
     * @param thread The native id of the thread it opened or answered, as the forge gave it.
     */
    record Written(String pullRequest, String operation, String account, String thread) {}

    /** Runs a command on the machine, as a scenario's connection does. */
    interface Script {

        /**
         * Runs a command.
         *
         * @param command What to run.
         * @return What it wrote and exited with.
         * @throws IOException If the machine cannot be reached.
         */
        Ssh.Output run(String command) throws IOException;

        /**
         * Runs a command with standard input.
         *
         * @param command What to run.
         * @param stdin What to feed it.
         * @return What it wrote and exited with.
         * @throws IOException If the machine cannot be reached.
         */
        Ssh.Output run(String command, String stdin) throws IOException;
    }
}
