package org.fuin.porelo.gate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Judges a task's push by the issue the task was started for: every commit it brings carries the
 * trailer {@code Porelo-Issue: <issue>}, so no work reaches review that does not say which issue it
 * is for. A commit may name further issues by further lines.
 *
 * <p>The commits judged are those the pushed commit reaches that no branch, tag or landed read of
 * another task holds, as {@link SignedArrivals} walks them: what central's main or another task's
 * landed work brought in was judged where it came from. The trailers are git's own reading of the
 * message, with the separator set on the command line, which no configuration of the account
 * overrides.
 */
public final class IssueTrailers {

    /** The trailer's key. */
    public static final String KEY = "Porelo-Issue";

    /** The most characters of an issue: a forge's owner and repository alone take up to 140. */
    public static final int MOST = 200;

    private final Path mirror;

    private final String issue;

    private final GitHttpServer.GitProcess git;

    /**
     * Constructor.
     *
     * @param mirror The bare mirror the push went to.
     * @param issue The issue the task was started for; one {@link #refusal(String)} takes.
     * @param git Runs git.
     */
    public IssueTrailers(Path mirror, String issue, GitHttpServer.GitProcess git) {
        final String refused = refusal(issue);
        if (refused != null) {
            throw new IllegalArgumentException(refused);
        }
        this.mirror = mirror;
        this.issue = issue;
        this.git = git;
    }

    /** Whether a branch, a tag or another task's landed read already holds the commit. */
    private boolean heldElsewhere(final String commit) throws IOException {
        return !new String(
                        git.run(
                                List.of(
                                        "--git-dir",
                                        mirror.toString(),
                                        "for-each-ref",
                                        "--count=1",
                                        "--format=%(refname)",
                                        "--contains",
                                        commit,
                                        "refs/heads/",
                                        "refs/tags/",
                                        GitGate.LANDED),
                                new byte[0]),
                        StandardCharsets.UTF_8)
                .isBlank();
    }

    /**
     * Says why text cannot be a task's issue, or {@code null}: 1 to {@value #MOST} characters of
     * printable ASCII without a space, a colon or a backtick, and not led by {@code -}. It stands
     * as it is in a commit's trailer, a markdown code span and a process's option, where each of
     * those would change what it says.
     *
     * @param issue The issue.
     * @return The refusal, or {@code null}.
     */
    public static @Nullable String refusal(final String issue) {
        if (issue.isEmpty()) {
            return "the work's issue is missing; it takes <issue>:<phase>";
        }
        if (issue.length() > MOST) {
            return "the work's issue has " + issue.length() + " characters, at most " + MOST;
        }
        if (issue.chars().anyMatch(c -> c <= ' ' || c > '~' || c == ':' || c == '`')
                || issue.startsWith("-")) {
            return "the work's issue is printable ASCII without a space, ':' or '`', and does not"
                    + " start with '-', such as owner/repo#123";
        }
        return null;
    }

    /**
     * Returns the trailer line each of the task's commits carries.
     *
     * @return {@code Porelo-Issue: <issue>}.
     */
    public String line() {
        return KEY + ": " + issue;
    }

    /**
     * Judges one update of the task's ref.
     *
     * @param old What the ref pointed at before, or git's zero name; not used, since every commit
     *     of the task's own is judged again.
     * @param commit What it points at now.
     * @return {@code null} when every commit carries the line, else why not, for the agent.
     * @throws IOException If it cannot be judged.
     */
    public @Nullable String judge(String old, String commit) throws IOException {
        final String type =
                new String(
                                git.run(
                                        List.of(
                                                "--git-dir",
                                                mirror.toString(),
                                                "cat-file",
                                                "-t",
                                                commit),
                                        new byte[0]),
                                StandardCharsets.US_ASCII)
                        .strip();
        if (!"commit".equals(type)) {
            return "the push brings a "
                    + (type.isEmpty() ? "missing object" : type)
                    + ", not a commit";
        }
        final String walked =
                new String(
                        git.runOrFail(
                                List.of(
                                        "--git-dir",
                                        mirror.toString(),
                                        "-c",
                                        "trailer.separators=:",
                                        "log",
                                        "-z",
                                        "--no-show-signature",
                                        "--max-count=" + (SignedArrivals.MAX_COMMITS + 1),
                                        "--format=%H%n%(trailers:only)",
                                        commit,
                                        "--not",
                                        "--branches",
                                        "--tags",
                                        "--glob=" + GitGate.LANDED + "*",
                                        "--"),
                                new byte[0]),
                        StandardCharsets.UTF_8);
        final List<String> records =
                java.util.Arrays.stream(walked.split("\0"))
                        .filter(each -> !each.isBlank())
                        .toList();
        if (records.size() > SignedArrivals.MAX_COMMITS) {
            return "the push brings more than "
                    + SignedArrivals.MAX_COMMITS
                    + " commits; push fewer at once";
        }
        if (records.isEmpty() && !heldElsewhere(commit)) {
            // git's exit code does not reach here: an empty walk is believed only when the commit
            // is already on a branch, a tag or a landed read, else a walk that failed would pass.
            return "commit " + commit + " could not be judged; push again";
        }
        final String line = line();
        for (final String record : records) {
            // On '\n' alone, as git splits a message: a '\r' is the agent's, inside a line.
            final List<String> lines =
                    java.util.Arrays.stream(record.split("\n"))
                            .filter(each -> !each.isEmpty())
                            .toList();
            if (lines.isEmpty()) {
                continue;
            }
            if (lines.stream().skip(1).noneMatch(line::equals)) {
                return "commit "
                        + lines.getFirst()
                        + " does not carry the trailer '"
                        + line
                        + "'; this task works on "
                        + issue
                        + ", and each of its commits names it in that line below the subject";
            }
        }
        return null;
    }
}
