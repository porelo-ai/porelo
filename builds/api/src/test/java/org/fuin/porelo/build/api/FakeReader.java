package org.fuin.porelo.build.api;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A reader the tests start as an executable: fixed answers, so a test sees what crossed the socket.
 *
 * <p>A branch {@code main} points at forty {@code a}s; a commit starting with {@code f} failed with
 * a log holding a NUL byte, one starting with {@code r} is rate limited for 30 seconds, one
 * starting with {@code m} failed in {@link Build#MOST_JOBS} jobs, each with a full {@link
 * Build#TAIL} of log ending in {@code END}, and one starting with {@code x} ends the reader's
 * process before it answers; any other succeeded, with its one job's log only when every log is
 * asked for; the token {@code wrong} is refused. A pull request is opened from a branch the first
 * time and found open after, its title the one given or else {@code head subject}.
 */
public final class FakeReader implements BuildReader {

    /** What a failed build's log holds: not text, so the protocol must carry bytes. */
    static final byte[] LOG = "step 3 failed\u0000\nexit 1\n".getBytes(StandardCharsets.UTF_8);

    /**
     * Runs the fake as a reader executable.
     *
     * @param arguments As every reader takes them.
     */
    public static void main(final String[] arguments) {
        BuildMain.run(new FakeReader(), arguments);
    }

    @Override
    public String forge() {
        return "fake";
    }

    @Override
    public String head(final Target target, final String branch) {
        refuseWrongToken(target);
        return "main".equals(branch) ? "a".repeat(40) : "";
    }

    @Override
    public Build build(final Target target, final String commit, final Build.Logs logs) {
        refuseWrongToken(target);
        if (commit.startsWith("r")) {
            throw new BuildRefused(
                    BuildRefused.Reason.RATE_LIMITED, "the forge's rate limit is reached", 30);
        }
        if (commit.startsWith("x")) {
            // As a reader killed for not answering in time is left: gone, with no answer.
            Runtime.getRuntime().halt(1);
        }
        if (commit.startsWith("m")) {
            final byte[] log = new byte[Build.TAIL];
            Arrays.fill(log, (byte) 'x');
            System.arraycopy("END\n".getBytes(StandardCharsets.UTF_8), 0, log, Build.TAIL - 4, 4);
            final List<Build.Job> jobs = new ArrayList<>();
            for (int i = 1; i <= Build.MOST_JOBS; i++) {
                jobs.add(
                        new Build.Job(
                                "Build / shard " + i,
                                "failure",
                                logs == Build.Logs.NONE ? null : log));
            }
            return new Build(Build.FAILURE, jobs, "");
        }
        if (commit.startsWith("f")) {
            return new Build(
                    Build.FAILURE,
                    List.of(
                            new Build.Job(
                                    "Build / unit tests",
                                    "failure",
                                    logs == Build.Logs.NONE ? null : LOG)),
                    "");
        }
        return logs == Build.Logs.ALL
                ? new Build(
                        Build.SUCCESS,
                        List.of(
                                new Build.Job(
                                        "Build / unit tests",
                                        "success",
                                        "all green\n".getBytes(StandardCharsets.UTF_8))),
                        "")
                : new Build(Build.SUCCESS, "");
    }

    /** The branches whose pull request is open, in this reader's process. */
    private final java.util.Set<String> opened = new java.util.HashSet<>();

    @Override
    public PullRequest openPullRequest(
            final Target target, final String branch, final @Nullable String title) {
        refuseWrongToken(target);
        return new PullRequest(
                "https://forge.example/owner/name/pulls/1#"
                        + (title == null ? "head subject" : title),
                opened.add(branch));
    }

    private static void refuseWrongToken(final Target target) {
        if ("wrong".equals(target.token())) {
            throw new BuildRefused(BuildRefused.Reason.CREDENTIAL_REFUSED, "bad credentials");
        }
    }
}
