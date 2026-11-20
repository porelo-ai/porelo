package org.fuin.porelo.gate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link IssueTrailers}, against git's own reading of real commits: a task given work
 * must not get a commit past its gate that does not name the issue, or work reaches review without
 * saying what it is for.
 */
class IssueTrailersTest {

    private static final String ISSUE = "porelo-ai/porelo#123"; // not-a-citation

    private static final String LINE = "Porelo-Issue: " + ISSUE;

    @TempDir Path dir;

    private Path mirror;

    private Path work;

    private String base;

    @BeforeEach
    void repositories() throws Exception {
        mirror = dir.resolve("mirror.git");
        work = dir.resolve("work");
        run(dir, "git", "init", "-q", "--bare", mirror.toString());
        run(dir, "git", "init", "-q", "-b", "main", work.toString());
        base = commit("base\n");
        run(work, "git", "push", "-q", mirror.toString(), "main:refs/heads/main");
    }

    @Test
    void aPushWhoseEveryCommitNamesTheIssueIsTaken() throws Exception {
        commit("first\n\n" + LINE + "\n");
        final String second =
                commit("second\n\nbody text\n\n" + LINE + "\nPorelo-Issue: other#9\n");

        assertThat(judge(base, second)).as("every commit carries the line").isNull();
        assertThat(judge(SignedArrivals.ZERO, second)).as("a first push alike").isNull();
    }

    @Test
    void aCommitWithoutTheExactTrailerIsRefusedByName() throws Exception {
        for (final String message :
                List.of(
                        "none\n",
                        "lower\n\nporelo-issue: " + ISSUE + "\n",
                        "body\n\n" + LINE + "\n\nand more prose after it.\n",
                        "other\n\nPorelo-Issue: other#9\n",
                        "longer\n\n" + LINE + "4\n",
                        // To git one trailer whose value holds a carriage return, not two lines.
                        "carried\n\nOther: x\r" + LINE + "\n",
                        "subject " + LINE + "\n")) {
            final String good = commit("good\n\n" + LINE + "\n");
            final String bad = commit(message);
            final String after = commit("after\n\n" + LINE + "\n");

            assertThat(judge(base, after))
                    .as("refused for: %s", message)
                    .contains(bad)
                    .contains(LINE)
                    .doesNotContain(good)
                    .doesNotContain(after);
            run(work, "git", "reset", "-q", "--hard", base);
        }
    }

    @Test
    void whatABranchOrAnotherTasksLandedWorkHoldsIsNotJudgedAgain() throws Exception {
        final String onMain = commit("central moved\n");
        run(work, "git", "push", "-q", mirror.toString(), "main:refs/heads/main");
        final String landed = commit("landed elsewhere\n");
        run(
                work,
                "git",
                "push",
                "-q",
                mirror.toString(),
                "HEAD:" + GitGate.LANDED + "other-task/main");
        final String tagged = commit("released elsewhere\n");
        run(work, "git", "push", "-q", mirror.toString(), "HEAD:refs/tags/v1");
        final String own = commit("own\n\n" + LINE + "\n");

        assertThat(judge(base, own)).as("main's, the landed and the tagged commit pass").isNull();
        assertThat(tagged).isNotEmpty();
        assertThat(judge(base, onMain)).as("a ref put back onto main").isNull();
        assertThat(landed).isNotEmpty();
    }

    @Test
    void anythingButACommitIsRefused() throws Exception {
        final String own = commit("own\n\n" + LINE + "\n");
        run(
                work,
                "git",
                "-c",
                "user.name=Task",
                "-c",
                "user.email=task@example.org",
                "tag",
                "-a",
                "-m",
                "a tag",
                "t1",
                own);
        run(work, "git", "push", "-q", mirror.toString(), "refs/tags/t1:refs/porelo/incoming/fix");
        final String tag = output(work, "git", "rev-parse", "t1");
        final String tree = output(work, "git", "rev-parse", own + "^{tree}");

        assertThat(judge(base, tag)).as("an annotated tag").contains("not a commit");
        assertThat(judge(base, tree)).as("a tree").contains("not a commit");
        assertThat(judge(base, "1".repeat(40))).as("a missing object").contains("not a commit");
    }

    @Test
    void aWalkThatYieldsNothingForACommitNoRefHoldsIsRefused() throws Exception {
        final String own = commit("own\n\n" + LINE + "\n");
        run(work, "git", "push", "-q", "-f", mirror.toString(), "HEAD:refs/porelo/incoming/fix");
        final GitHttpServer.GitProcess real = GitSubprocess.withoutConfig();
        final GitHttpServer.GitProcess silentLog =
                new GitHttpServer.GitProcess() {
                    @Override
                    public void stream(
                            List<String> arguments, Path input, java.io.OutputStream output) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public byte[] run(List<String> arguments, byte[] input)
                            throws java.io.IOException {
                        return arguments.contains("log") ? new byte[0] : real.run(arguments, input);
                    }
                };

        assertThat(new IssueTrailers(mirror, ISSUE, silentLog).judge(base, own))
                .as("a failed walk does not pass")
                .contains(own);
    }

    @Test
    void aWalkThatFailsIsNotTakenForAnswered() throws Exception {
        final String own = commit("own\n\n" + LINE + "\n");
        run(work, "git", "push", "-q", "-f", mirror.toString(), "HEAD:refs/porelo/incoming/fix");
        final GitHttpServer.GitProcess real = GitSubprocess.withoutConfig();
        final GitHttpServer.GitProcess failingLog =
                new GitHttpServer.GitProcess() {
                    @Override
                    public byte[] run(List<String> arguments, byte[] input)
                            throws java.io.IOException {
                        return real.run(arguments, input);
                    }

                    @Override
                    public byte[] runOrFail(List<String> arguments, byte[] input)
                            throws java.io.IOException {
                        if (arguments.contains("log")) {
                            throw new java.io.IOException("git log exited 128");
                        }
                        return real.runOrFail(arguments, input);
                    }
                };

        assertThatThrownBy(() -> new IssueTrailers(mirror, ISSUE, failingLog).judge(base, own))
                .as("a walk that ended early answers nothing")
                .isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(
                        () ->
                                GitSubprocess.withoutConfig()
                                        .runOrFail(
                                                List.of(
                                                        "--git-dir",
                                                        mirror.toString(),
                                                        "log",
                                                        "--format=%H",
                                                        "1".repeat(40)),
                                                new byte[0]))
                .as("git's own exit code is read")
                .isInstanceOf(java.io.IOException.class);
    }

    @Test
    void anIssueThatWouldChangeWhatItSaysIsNotTaken() {
        for (final String issue : List.of("", "a b", "a:b", "a`b", "-a", "a".repeat(201))) {
            assertThatThrownBy(() -> new IssueTrailers(mirror, issue, new GitSubprocess()))
                    .as("issue %s", issue)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(IssueTrailers.refusal("a".repeat(200))).isNull();
    }

    private String judge(final String old, final String commit) throws Exception {
        run(work, "git", "push", "-q", "-f", mirror.toString(), "HEAD:refs/porelo/incoming/fix");
        return new IssueTrailers(mirror, ISSUE, GitSubprocess.withoutConfig()).judge(old, commit);
    }

    private String commit(final String message) throws Exception {
        final Path file = work.resolve("file.txt");
        Files.writeString(
                file,
                (Files.exists(file) ? Files.readString(file) : "") + message.lines().findFirst());
        run(work, "git", "add", "file.txt");
        run(
                work,
                "git",
                "-c",
                "user.name=Task",
                "-c",
                "user.email=task@example.org",
                "-c",
                "commit.gpgsign=false",
                "commit",
                "-q",
                "--cleanup=verbatim",
                "-m",
                message);
        return output(work, "git", "rev-parse", "HEAD");
    }

    private static void run(final Path in, final String... command) throws Exception {
        final Process process =
                new ProcessBuilder(command)
                        .directory(in.toFile())
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .start();
        assertThat(process.waitFor()).as(String.join(" ", command)).isZero();
    }

    private static String output(final Path in, final String... command) throws Exception {
        final Process process = new ProcessBuilder(command).directory(in.toFile()).start();
        final String out = new String(process.getInputStream().readAllBytes()).strip();
        assertThat(process.waitFor()).isZero();
        return out;
    }
}
