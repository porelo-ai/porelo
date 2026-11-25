package org.fuin.porelo.build.stub;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.fuin.porelo.build.api.Build;
import org.fuin.porelo.build.api.BuildMain;
import org.fuin.porelo.build.api.BuildReader;
import org.fuin.porelo.build.api.BuildRefused;
import org.fuin.porelo.build.api.ConversationPage;
import org.fuin.porelo.build.api.ForgeAccess;
import org.fuin.porelo.build.api.ForgeReviewRefused;
import org.fuin.porelo.build.api.ReviewFacts;
import org.fuin.porelo.build.api.ReviewProtocol;
import org.fuin.porelo.build.api.ReviewReader;
import org.fuin.porelo.build.api.Target;
import org.fuin.porelo.wire.Json;
import org.fuin.porelo.wire.JsonException;
import org.jspecify.annotations.Nullable;

/**
 * A build reader for the tests: it answers from a file instead of a forge, read again on every
 * call, so a test moves a build from running to failed by writing the file.
 *
 * <p>The file is {@code answers.json} beside the executable, or what {@code
 * PORELO_BUILD_STUB_ANSWERS} names:
 *
 * <pre>
 * {"token": "the one the vault holds",
 *  "heads": {"porelo/fix-login": "4f2a…"},
 *  "builds": {"4f2a…": {"verdict": "failure", "jobs": [{"name": "Build / test", "result": "failure", "log": "…"}],
 *                       "detail": ""}},
 *  "refuse": {"reason": "RATE_LIMITED", "detail": "…", "retryAfter": 30}}
 * </pre>
 *
 * {@code heads} maps a branch at the forge to its head commit: the branch the task's work reaches
 * there, which for an online task is {@code porelo/<task>}, not the task's name. A {@code token}
 * there is the only one accepted; any other is refused as the forge would refuse it, so a test sees
 * that the token Porelo sends is the vault's. A {@code refuse} refuses every call. Its jobs are
 * listed once the verdict is failure or final, each with its log when it failed, or every one's
 * when every log is asked for and the verdict is final, or no log for {@link Build.Logs#NONE} - as
 * a forge's reader does.
 *
 * <p>It answers the review contract from the same file:
 *
 * <pre>
 * {"reviews": {"https://forge.example/o/n/pull/1": {
 *     "facts": {"repository": "https://forge.example/o/n", "head": "4f2a…", "base": "…", "mergeBase": "…",
 *               "state": "open", "approved": false},
 *     "pages": [{… a profile 2 page …}, …],
 *     "postedAs": {"porelo/forgeAccountId": "77", "porelo/forgeAccountName": "reviewer"}},
 *   "https://forge.example/o/n/pull/2": {
 *     "facts": {"repository": "https://forge.example/o/n", "head": "4f2a…", "base": "…", "mergeBase": "…",
 *               "state": "merged", "approved": true, "mergeCommit": "…", "mergedHead": "main",
 *               "decidedAt": "2026-11-20T10:15:30Z"},
 *     "pages": [{… a profile 2 page …}, …]}},
 *  "openPullRequests": {"porelo/fix-login": "https://forge.example/o/n/pull/1"},
 *  "reviewRefuse": {"reason": "RATE_LIMITED", "retryAfter": 30}}
 * </pre>
 *
 * {@code facts} are a pull request's as the contract answers them, {@code approved} and, for a
 * merged or closed one, {@code decidedAt} among them, and {@code pages} its conversation, one
 * profile 2 page each, the cursor of the next being its index; a page past 100 results or 1 MiB
 * spills onto pages of its own. Each {@code Post} is written as one line to {@link #POSTS} beside
 * the answers - its address, its thread, the guid it is given then, and its document, never the
 * token - and its result joins the last page of that conversation with that guid, its thread, and
 * as {@code forge-account}: the stub's own account ({@link #ACCOUNT_ID}, {@link #ACCOUNT_NAME}), or
 * what {@code postedAs} names over it. A reply names a thread the conversation holds or is refused;
 * a new comment opens a thread of its own. A {@code reviewRefuse} refuses every review call, a
 * reason of {@link ForgeReviewRefused.Reason} with its {@code retryAfter} or {@code field}; the
 * {@code token} holds for the review calls too.
 */
public final class StubReader implements BuildReader, ReviewReader {

    /**
     * The forge a project file names to read builds from this reader, chosen apart from the stub
     * agent's name.
     */
    public static final String FORGE = "stub-forge";

    /**
     * The environment variable that names the answers, for a test that cannot write beside the
     * executable.
     */
    public static final String ANSWERS = "PORELO_BUILD_STUB_ANSWERS";

    /** The file beside the answers each {@code Post} is written to, one JSON line each. */
    public static final String POSTS = "review-posts.jsonl";

    /** The first thread id a new comment opens, so a test tells it from the ones it wrote. */
    /** The forge account id a posted result carries when the answers name none. */
    public static final String ACCOUNT_ID = "4242";

    /** The forge account name a posted result carries when the answers name none. */
    public static final String ACCOUNT_NAME = "porelo-stub";

    private static final long FIRST_THREAD = 9_000_000_000L;

    private final Path answers;

    /**
     * Constructor.
     *
     * @param answers The file to answer from.
     */
    public StubReader(final Path answers) {
        this.answers = answers;
    }

    /**
     * Runs the stub as a reader executable.
     *
     * @param arguments {@code describe}, or {@code serve <socket>}.
     */
    public static void main(final String[] arguments) {
        BuildMain.run(
                new StubReader(
                        answers(
                                System.getenv(ANSWERS),
                                ProcessHandle.current().info().command().orElse(FORGE))),
                arguments);
    }

    /**
     * Returns where the answers are.
     *
     * @param named What {@link #ANSWERS} says, or {@code null}.
     * @param command The running executable.
     * @return The file.
     */
    static Path answers(final @Nullable String named, final String command) {
        if (named != null && !named.isBlank()) {
            return Path.of(named);
        }
        final Path parent = Path.of(command).toAbsolutePath().getParent();
        return (parent == null ? Path.of(".") : parent).resolve("answers.json");
    }

    @Override
    public String forge() {
        return FORGE;
    }

    @Override
    public String head(final Target target, final String branch) {
        final Map<?, ?> read = read(target);
        return read.get("heads") instanceof Map<?, ?> heads && heads.get(branch) != null
                ? String.valueOf(heads.get(branch))
                : "";
    }

    @Override
    public Build build(final Target target, final String commit, final Build.Logs logs) {
        final Map<?, ?> read = read(target);
        if (!(read.get("builds") instanceof Map<?, ?> builds)
                || !(builds.get(commit) instanceof Map<?, ?> build)) {
            return Build.unknown("no build of " + commit);
        }
        final String verdict = text(build.get("verdict"));
        final boolean done =
                List.of(Build.SUCCESS, Build.FAILURE, Build.CANCELLED).contains(verdict);
        final List<Build.Job> jobs = new ArrayList<>();
        if (done && build.get("jobs") instanceof List<?> listed) {
            for (final Object each : listed) {
                if (each instanceof Map<?, ?> job) {
                    final String result = text(job.get("result"));
                    final Object log =
                            logs == Build.Logs.ALL
                                            || (logs == Build.Logs.FAILURE
                                                    && "failure".equals(result))
                                    ? job.get("log")
                                    : null;
                    jobs.add(
                            log == null
                                    ? new Build.Job(text(job.get("name")), result)
                                    : new Build.Job(
                                            text(job.get("name")),
                                            result,
                                            String.valueOf(log).getBytes(StandardCharsets.UTF_8)));
                }
            }
        }
        // Every run at every answer, done or not: the tile names each while it runs.
        final List<Build.Run> runs = new ArrayList<>();
        if (build.get("runs") instanceof List<?> named) {
            for (final Object each : named) {
                if (each instanceof Map<?, ?> run) {
                    runs.add(new Build.Run(text(run.get("name")), text(run.get("result"))));
                }
            }
        }
        return new Build(verdict, jobs, text(build.get("detail")), runs);
    }

    @Override
    public ReviewFacts pullRequest(final ForgeAccess access, final String url) {
        if (!(review(access, url).get("facts") instanceof Map<?, ?> facts)) {
            throw new ForgeReviewRefused(ForgeReviewRefused.Reason.NO_SUCH_PULL_REQUEST);
        }
        final Map<String, Object> wire = new LinkedHashMap<>();
        facts.forEach((key, value) -> wire.put(String.valueOf(key), value));
        return ReviewFacts.of(wire);
    }

    @Override
    public @Nullable String pullRequestOf(
            final ForgeAccess access, final String upstream, final String branch) {
        final Map<?, ?> read = reviews(access);
        return read.get("openPullRequests") instanceof Map<?, ?> open && open.get(branch) != null
                ? String.valueOf(open.get(branch))
                : null;
    }

    @Override
    public ConversationPage comments(
            final ForgeAccess access, final String url, final @Nullable String cursor) {
        final List<Map<String, Object>> pages = conversation(review(access, url), url);
        final int index;
        try {
            index = cursor == null ? 0 : Integer.parseInt(cursor);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("the stub's cursors are page numbers", ex);
        }
        if (index < 0 || index >= pages.size()) {
            throw new IllegalArgumentException("the stub has no page " + index + " of " + url);
        }
        return ConversationPage.of(
                Json.write(pages.get(index)),
                index + 1 < pages.size() ? Integer.toString(index + 1) : null);
    }

    @Override
    public synchronized String post(
            final ForgeAccess access, final String url, final String document) {
        final Map<?, ?> review = review(access, url);
        final Map<String, Object> result = onlyResult(document);
        final Map<String, Object> properties = properties(result);
        final Object replyTo = properties.get("porelo/replyTo");
        final String thread;
        if (replyTo == null) {
            thread = Long.toString(FIRST_THREAD + posted().size());
        } else {
            thread = String.valueOf(replyTo);
            if (!threads(conversation(review, url)).contains(thread)) {
                throw new ForgeReviewRefused(ForgeReviewRefused.Reason.NO_SUCH_THREAD);
            }
        }
        final Map<String, Object> line = new LinkedHashMap<>();
        line.put("url", url);
        line.put("thread", thread);
        // Fixed when posted, so every page written later carries the same guid for it.
        line.put("guid", UUID.randomUUID().toString());
        line.put("document", Json.parseExact(document));
        try {
            Files.writeString(
                    posts(),
                    Json.write(line) + "\n",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException ex) {
            // Not written where the conversation is read from: the post did not happen.
            throw new ForgeReviewRefused(ForgeReviewRefused.Reason.UNREACHABLE);
        }
        return thread;
    }

    /**
     * Returns where each post is written.
     *
     * @return The file beside the answers.
     */
    public Path posts() {
        return answers.resolveSibling(POSTS);
    }

    /** The answers for the review calls, after the review refusal and the token. */
    private Map<?, ?> reviews(final ForgeAccess access) {
        final Object parsed;
        try {
            parsed = Json.parseExact(Files.readString(answers, StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new ForgeReviewRefused(ForgeReviewRefused.Reason.UNREACHABLE);
        }
        if (!(parsed instanceof Map<?, ?> read)) {
            throw new ForgeReviewRefused(ForgeReviewRefused.Reason.UNREACHABLE);
        }
        if (read.get("reviewRefuse") instanceof Map<?, ?> refuse) {
            final ForgeReviewRefused.Reason reason =
                    ForgeReviewRefused.Reason.valueOf(text(refuse.get("reason")));
            throw switch (reason) {
                case RATE_LIMITED ->
                        ForgeReviewRefused.rateLimited(
                                refuse.get("retryAfter") instanceof Number number
                                        ? number.longValue()
                                        : 0);
                case NOT_IN_PROFILE -> ForgeReviewRefused.notInProfile(text(refuse.get("field")));
                default -> new ForgeReviewRefused(reason);
            };
        }
        if (read.get("token") != null && !text(read.get("token")).equals(access.token())) {
            throw new ForgeReviewRefused(ForgeReviewRefused.Reason.CREDENTIAL_REFUSED);
        }
        return read;
    }

    private Map<?, ?> review(final ForgeAccess access, final String url) {
        if (reviews(access).get("reviews") instanceof Map<?, ?> reviews
                && reviews.get(url) instanceof Map<?, ?> review) {
            return review;
        }
        throw new ForgeReviewRefused(ForgeReviewRefused.Reason.NO_SUCH_PULL_REQUEST);
    }

    /** The pages the answers hold, each post to this pull request joining the last one. */
    private List<Map<String, Object>> conversation(final Map<?, ?> review, final String url) {
        final List<Map<String, Object>> pages = new ArrayList<>();
        if (review.get("pages") instanceof List<?> listed) {
            for (final Object each : listed) {
                if (each instanceof Map<?, ?> page) {
                    pages.add(copy(page));
                }
            }
        }
        if (pages.isEmpty()) {
            throw new IllegalArgumentException("the stub's answers hold no page of " + url);
        }
        final List<Object> added = new ArrayList<>();
        final Map<String, Object> postedAs = new LinkedHashMap<>();
        postedAs.put("porelo/sourceKind", "forge-account");
        postedAs.put("porelo/forgeAccountId", ACCOUNT_ID);
        postedAs.put("porelo/forgeAccountName", ACCOUNT_NAME);
        if (review.get("postedAs") instanceof Map<?, ?> named) {
            postedAs.putAll(copy(named));
        }
        for (final Map<?, ?> post : posted()) {
            if (url.equals(post.get("url")) && post.get("document") instanceof Map<?, ?> doc) {
                final Map<String, Object> result = onlyResult(Json.write(doc));
                final Map<String, Object> properties = properties(result);
                final String thread = text(post.get("thread"));
                properties.put("porelo/threadId", thread);
                properties.put("porelo/threadState", stateOf(pages, thread));
                properties.putAll(postedAs);
                result.put("guid", text(post.get("guid")));
                added.add(result);
            }
        }
        attach(pages.getLast(), added);
        final List<Map<String, Object>> spilled = new ArrayList<>();
        for (final Map<String, Object> page : pages) {
            spilled.addAll(spill(page));
        }
        return spilled;
    }

    /**
     * Splits a page past {@link ReviewProtocol#MOST_PAGE_RESULTS} results or {@link
     * ReviewProtocol#MOST_PAGE_BYTES} into pages within both, in order, each its run's copy.
     */
    private static List<Map<String, Object>> spill(final Map<String, Object> page) {
        final List<?> results = results(page);
        final Map<String, Object> empty = copy(page);
        attach(empty, List.of(), true);
        final int bare = bytes(empty);
        final List<Map<String, Object>> pages = new ArrayList<>();
        List<Object> held = new ArrayList<>();
        int size = bare;
        for (final Object result : results) {
            final int more = bytes(result) + (held.isEmpty() ? 0 : 1);
            if (!held.isEmpty()
                    && (held.size() == ReviewProtocol.MOST_PAGE_RESULTS
                            || size + more > ReviewProtocol.MOST_PAGE_BYTES)) {
                pages.add(withResults(empty, held));
                held = new ArrayList<>();
                size = bare;
            }
            size += bytes(result) + (held.isEmpty() ? 0 : 1);
            held.add(result);
        }
        pages.add(withResults(empty, held));
        return pages;
    }

    private static Map<String, Object> withResults(
            final Map<String, Object> empty, final List<Object> results) {
        final Map<String, Object> page = copy(empty);
        attach(page, results, true);
        return page;
    }

    private static int bytes(final Object json) {
        return Json.write(json).getBytes(StandardCharsets.UTF_8).length;
    }

    /** Every post written so far, in order. */
    private List<Map<?, ?>> posted() {
        final Path file = posts();
        if (!Files.exists(file)) {
            return List.of();
        }
        final List<Map<?, ?>> posted = new ArrayList<>();
        try {
            for (final String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (!line.isBlank() && Json.parseExact(line) instanceof Map<?, ?> post) {
                    posted.add(post);
                }
            }
        } catch (IOException ex) {
            throw new ForgeReviewRefused(ForgeReviewRefused.Reason.UNREACHABLE);
        }
        return posted;
    }

    /** The one result of a submission; anything else is not what the contract writes. */
    private static Map<String, Object> onlyResult(final String document) {
        final Object parsed;
        try {
            parsed = Json.parseExact(document);
        } catch (JsonException ex) {
            throw ForgeReviewRefused.notInProfile("document");
        }
        if (parsed instanceof Map<?, ?> submission
                && submission.get("runs") instanceof List<?> runs
                && runs.size() == 1
                && runs.getFirst() instanceof Map<?, ?> run
                && run.get("results") instanceof List<?> results
                && results.size() == 1
                && results.getFirst() instanceof Map<?, ?> result) {
            return copy(result);
        }
        throw ForgeReviewRefused.notInProfile("runs[0].results");
    }

    private static Map<String, Object> properties(final Map<String, Object> result) {
        final Map<String, Object> properties =
                result.get("properties") instanceof Map<?, ?> given
                        ? copy(given)
                        : new LinkedHashMap<>();
        result.put("properties", properties);
        return properties;
    }

    /** Adds results to a page's run, which the copy of the answers holds. */
    private static void attach(final Map<String, Object> page, final List<Object> added) {
        attach(page, added, false);
    }

    /** Adds results to a page's run, or sets them in place of those it holds. */
    private static void attach(
            final Map<String, Object> page, final List<Object> added, final boolean replace) {
        if (!(page.get("runs") instanceof List<?> runs)
                || runs.isEmpty()
                || !(runs.getFirst() instanceof Map<?, ?> first)) {
            throw new IllegalArgumentException("a page of the stub's answers holds no run");
        }
        final Map<String, Object> run = copy(first);
        final List<Object> results = replace ? new ArrayList<>() : new ArrayList<>(results(page));
        results.addAll(added);
        run.put("results", results);
        final List<Object> copied = new ArrayList<>(runs);
        copied.set(0, run);
        page.put("runs", copied);
    }

    private static List<?> results(final Map<String, Object> page) {
        return page.get("runs") instanceof List<?> runs
                        && !runs.isEmpty()
                        && runs.getFirst() instanceof Map<?, ?> run
                        && run.get("results") instanceof List<?> results
                ? results
                : List.of();
    }

    private static Set<String> threads(final List<Map<String, Object>> pages) {
        final Set<String> threads = new HashSet<>();
        for (final Map<String, Object> page : pages) {
            for (final Object each : results(page)) {
                if (each instanceof Map<?, ?> result
                        && result.get("properties") instanceof Map<?, ?> properties
                        && properties.get("porelo/threadId") != null) {
                    threads.add(text(properties.get("porelo/threadId")));
                }
            }
        }
        return threads;
    }

    /** A thread's state as its earliest result says; a new thread is open. */
    private static String stateOf(final List<Map<String, Object>> pages, final String thread) {
        for (final Map<String, Object> page : pages) {
            for (final Object each : results(page)) {
                if (each instanceof Map<?, ?> result
                        && result.get("properties") instanceof Map<?, ?> properties
                        && thread.equals(text(properties.get("porelo/threadId")))
                        && properties.get("porelo/threadState") != null) {
                    return text(properties.get("porelo/threadState"));
                }
            }
        }
        return "open";
    }

    private static Map<String, Object> copy(final Map<?, ?> map) {
        final Map<String, Object> copied = new LinkedHashMap<>();
        map.forEach((key, value) -> copied.put(String.valueOf(key), value));
        return copied;
    }

    private Map<?, ?> read(final Target target) {
        final Object parsed;
        try {
            parsed = Json.parseExact(Files.readString(answers, StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new BuildRefused(
                    BuildRefused.Reason.UNREACHABLE, "the stub has no answers at " + answers);
        }
        if (!(parsed instanceof Map<?, ?> read)) {
            throw new BuildRefused(
                    BuildRefused.Reason.UNREACHABLE, answers + " is not a JSON object");
        }
        if (read.get("refuse") instanceof Map<?, ?> refuse) {
            throw new BuildRefused(
                    BuildRefused.Reason.valueOf(text(refuse.get("reason"))),
                    text(refuse.get("detail")),
                    refuse.get("retryAfter") instanceof Number number ? number.longValue() : 0);
        }
        if (read.get("token") != null && !text(read.get("token")).equals(target.token())) {
            throw new BuildRefused(
                    BuildRefused.Reason.CREDENTIAL_REFUSED, "the stub was given another token");
        }
        return read;
    }

    private static String text(final @Nullable Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
