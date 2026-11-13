package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.gate.GitGate;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for {@link HostPush}: a task's branch pushed to the host's forge with the task's token. */
class HostPushTest {

    private static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";

    private static final String TOKEN = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b";

    @TempDir Path dir;

    private final FakeCommandRunner runner = new FakeCommandRunner();

    // A token on an argument or in the URL is readable by every account on the host.
    @Test
    void theBranchIsSetInTheMirrorAndPushedToTheLoopbackWithTheTokenOnlyInTheEnvironment()
            throws IOException {
        final Path mirror = dir.resolve("mirror.git");

        assertThat(
                        push(mirror, conf("host=wharf6\nport=3000\ngit_port=3001\n"), "t0ken")
                                .passOn(COMMIT))
                .isNull();

        final var calls = runner.invocations();
        assertThat(calls.get(calls.size() - 2).inheritsEnvironment()).isFalse();
        assertThat(calls.get(calls.size() - 2).arguments())
                .containsExactly(
                        "git",
                        "-c",
                        "core.hooksPath=/dev/null",
                        "--git-dir",
                        mirror.toString(),
                        "update-ref",
                        "refs/heads/porelo/fix",
                        COMMIT);
        final var pushed = calls.getLast();
        assertThat(pushed.arguments())
                .containsSubsequence(
                        "-c",
                        "credential.helper=",
                        "--git-dir",
                        mirror.toString(),
                        "push",
                        "http://127.0.0.1:3001/acme/demo.git",
                        COMMIT + ":refs/heads/porelo/fix")
                .doesNotContain("--force", "sudo")
                .anySatisfy(
                        argument ->
                                assertThat(argument)
                                        .startsWith("credential.http://127.0.0.1:3001.helper=!")
                                        .endsWith(
                                                " vault credential --env "
                                                        + HostPush.TOKEN_VARIABLE));
        assertThat(pushed.arguments())
                .contains("core.hooksPath=/dev/null", "protocol.allow=never", "--no-verify");
        // The account's own git config could send the push, and its token, elsewhere.
        assertThat(pushed.inheritsEnvironment()).isFalse();
        assertThat(pushed.environment())
                .containsEntry(HostPush.TOKEN_VARIABLE, "t0ken")
                .containsEntry("GIT_CONFIG_GLOBAL", "/dev/null")
                .containsEntry("GIT_CONFIG_NOSYSTEM", "1")
                .doesNotContainKeys("http_proxy", "GIT_TRACE_CURL");
        assertThat(pushed.input()).isNull();
        assertThat(pushed.describe()).doesNotContain("t0ken");
    }

    // An online task that amends after a reading rewrites its branch; without force the forge
    // refused it as non-fast-forward, and move 4's "work every result off" stopped there.
    @Test
    void anAmendReplacesOnlyWhatTheGateItselfLastPushedThere() throws IOException {
        final Path mirror = dir.resolve("mirror.git");
        final Path conf = conf("git_port=3001\n");
        final String amended = "fedcba9876543210fedcba9876543210fedcba98";

        assertThat(push(mirror, conf, TOKEN).passOn(COMMIT)).isNull();
        assertThat(runner.invocations().getLast().arguments())
                .as("the first push leases nothing: it replaces nothing")
                .contains("--no-force-with-lease")
                .noneMatch(argument -> argument.startsWith("--force"));

        assertThat(push(mirror, conf, TOKEN).passOn(amended)).isNull();
        assertThat(runner.invocations().getLast().arguments())
                .contains("--force-with-lease=refs/heads/porelo/fix:" + COMMIT)
                .doesNotContain("--force", "+refs/heads/porelo/fix:refs/heads/porelo/fix");

        runner.failing("push", 1, "! [rejected] porelo/fix (stale info)\n");
        assertThat(push(mirror, conf, TOKEN).passOn(COMMIT)).contains("stale info");
        runner.answering("push", "");
        push(mirror, conf, TOKEN).passOn(COMMIT);
        assertThat(runner.invocations().getLast().arguments())
                .as("a push the forge refused is not what the next one leases on")
                .contains("--force-with-lease=refs/heads/porelo/fix:" + amended);
    }

    // Two pushes of one task at once: a refspec of the mirror's branch sent whatever the other
    // push had set there, and recorded this push's commit as what the forge holds.
    @Test
    void thePushSendsTheCommitItWasGivenNotWhatTheMirrorsBranchHolds() throws IOException {
        final Path mirror = dir.resolve("mirror.git");

        assertThat(push(mirror, conf("git_port=3001\n"), TOKEN).passOn(COMMIT)).isNull();

        assertThat(runner.invocations().getLast().arguments())
                .endsWith(COMMIT + ":refs/heads/porelo/fix")
                .doesNotContain("refs/heads/porelo/fix:refs/heads/porelo/fix");
    }

    // A record cut short or written by hand must not become a lease: leasing on garbage either
    // fails every push or, worse, names a commit the gate never pushed.
    @Test
    void aRecordThatIsNoCommitLeasesNothing() throws IOException {
        final Path mirror = dir.resolve("mirror.git");
        final Path conf = conf("git_port=3001\n");
        final Path record = mirror.resolve(GitGate.HOST_PUSHED).resolve("fix");
        Files.createDirectories(record.getParent());
        for (final String kept :
                java.util.List.of("0123456789abcdef", "-d\n", COMMIT.toUpperCase(), COMMIT + "0")) {
            Files.writeString(record, kept);

            assertThat(push(mirror, conf, TOKEN).passOn(COMMIT)).isNull();

            assertThat(runner.invocations().getLast().arguments())
                    .as(kept)
                    .contains("--no-force-with-lease")
                    .noneMatch(argument -> argument.startsWith("--force-with-lease"));
        }
    }

    // A refusal must be said; with no token or no port, nothing may reach the forge.
    @Test
    void aRefusalNoTokenOrNoPortIsSaidAndNothingIsPushed() throws IOException {
        runner.failing("push", 1, "! [rejected] porelo/fix (non-fast-forward)\n");
        final Path mirror = dir.resolve("mirror.git");
        final Path conf = conf("git_port=3001\n");

        assertThat(push(mirror, conf, TOKEN).passOn(COMMIT)).contains("non-fast-forward");
        final int before = runner.invocations().size();
        assertThat(
                        new HostPush(context(), mirror, "fix", "acme/demo", Optional::empty, conf)
                                .passOn(COMMIT))
                .contains("no forge token");
        assertThat(push(mirror, conf("git_port=0\n"), TOKEN).passOn(COMMIT)).contains("git_port");
        assertThat(push(mirror, conf("port=3000\n"), TOKEN).passOn(COMMIT)).contains("git_port");
        assertThat(push(mirror, dir.resolve("missing.conf"), TOKEN).passOn(COMMIT))
                .contains("git_port");
        assertThat(runner.invocations()).hasSize(before);
    }

    // A name with ".." or a leading dash steers the push to another repository or into git's
    // options.
    @Test
    void aNameOrCommitThatCouldSteerThePushAndATokenOfMoreThanOneWordAreRefused()
            throws IOException {
        final Path mirror = dir.resolve("mirror.git");
        final Path conf = conf("git_port=3001\n");
        for (final String repository :
                java.util.List.of("../victim", "acme/..", ".x/demo", "acme/-d")) {
            assertThat(
                            new HostPush(
                                            context(),
                                            mirror,
                                            "fix",
                                            repository,
                                            () -> Optional.of(TOKEN),
                                            conf)
                                    .passOn(COMMIT))
                    .as(repository)
                    .contains("refuses");
        }
        for (final String task : java.util.List.of("a/b", "-d", "..", "a..b")) {
            assertThat(
                            new HostPush(
                                            context(),
                                            mirror,
                                            task,
                                            "acme/demo",
                                            () -> Optional.of(TOKEN),
                                            conf)
                                    .passOn(COMMIT))
                    .as(task)
                    .contains("refuses");
        }
        assertThat(push(mirror, conf, TOKEN).passOn("-d")).contains("refuses");
        assertThat(push(mirror, conf, "line1\nsecret2").passOn(COMMIT)).contains("not one word");
        assertThat(runner.invocations()).isEmpty();
    }

    // A forge that never answers held the agent's push for the runner's ten minutes, and was then
    // said to have failed to run.
    @Test
    void aPushTheForgeDoesNotAnswerEndsAtTheLimitAndSaysSo() throws IOException {
        runner.timingOut(" push ");
        final Path mirror = dir.resolve("mirror.git");
        final String said =
                push(mirror, conf("git_port=3001\n"), TOKEN)
                        .within(java.time.Duration.ofSeconds(90))
                        .passOn(COMMIT);

        assertThat(said)
                .isEqualTo(
                        "the forge did not answer within 90 s and did not hold the push when"
                                + " asked after - push again");
        assertThat(runner.only(" push ").timeout())
                .as("the push carries what is left of the limit, so the runner ends it there")
                .isBetween(java.time.Duration.ofSeconds(80), java.time.Duration.ofSeconds(90));
        assertThat(runner.only(" ls-remote ").arguments())
                .contains("http://127.0.0.1:3001/acme/demo.git", "refs/heads/porelo/fix");
        assertThat(runner.only(" ls-remote ").environment())
                .containsEntry(HostPush.TOKEN_VARIABLE, TOKEN);
        assertThat(runner.invocations().getLast().arguments())
                .as("the mirror's branch goes back to before, while it holds this push's")
                .containsSubsequence("update-ref", "-d", "refs/heads/porelo/fix", COMMIT);
        assertThat(mirror.resolve(GitGate.HOST_PUSHED).resolve("fix")).doesNotExist();
    }

    // The forge set the branch and then hung: "nothing was passed on" would have been untrue.
    @Test
    void aPushThatLandedBeforeTheForgeStalledIsRecorded() throws IOException {
        runner.timingOut(" push ").answering(" ls-remote ", COMMIT + "\trefs/heads/porelo/fix\n");
        final Path mirror = dir.resolve("mirror.git");

        assertThat(push(mirror, conf("git_port=3001\n"), TOKEN).passOn(COMMIT)).isNull();
        assertThat(mirror.resolve(GitGate.HOST_PUSHED).resolve("fix")).hasContent(COMMIT);
        assertThat(runner.invocations().getLast().arguments()).contains("ls-remote");
    }

    @Test
    void aForgeThatDoesNotSayWhatItHoldsLeavesTheOutcomeOpen() throws IOException {
        runner.timingOut(" push ").timingOut(" ls-remote ");

        assertThat(push(dir.resolve("mirror.git"), conf("git_port=3001\n"), TOKEN).passOn(COMMIT))
                .startsWith(
                        "the forge did not answer within 120 s; whether it took the push is"
                                + " not known");
    }

    // The mirror's branch was set before the push and stayed there when the forge refused it.
    @Test
    void aRefusedPushPutsTheMirrorsBranchBackToWhatItHeld() throws IOException {
        final String before = "fedcba9876543210fedcba9876543210fedcba98";
        runner.answering(" rev-parse ", before + "\n").failing("push", 1, "! [rejected]\n");

        assertThat(push(dir.resolve("mirror.git"), conf("git_port=3001\n"), TOKEN).passOn(COMMIT))
                .contains("rejected");
        assertThat(runner.invocations().getLast().arguments())
                .containsSubsequence("update-ref", "refs/heads/porelo/fix", before, COMMIT);
    }

    // What the wait for the lock took is gone from the push's own limit: the two share one bound.
    @Test
    void aPushAfterAWaitForTheLockHasWhatIsLeftOfTheLimit() throws Exception {
        final Path mirror = dir.resolve("mirror.git");
        final Path lock = mirror.resolve(GitGate.HOST_PUSHED).resolve(".fix.lock");
        Files.createDirectories(lock.getParent());
        final java.util.concurrent.CountDownLatch held = new java.util.concurrent.CountDownLatch(1);
        final Thread other =
                Thread.ofVirtual()
                        .start(
                                () -> {
                                    try {
                                        FileLocks.holding(
                                                lock,
                                                () -> {
                                                    held.countDown();
                                                    try {
                                                        Thread.sleep(2000);
                                                    } catch (InterruptedException ex) {
                                                        Thread.currentThread().interrupt();
                                                    }
                                                    return null;
                                                });
                                    } catch (Exception ex) {
                                        throw new IllegalStateException(ex);
                                    }
                                });
        held.await();

        assertThat(
                        push(mirror, conf("git_port=3001\n"), TOKEN)
                                .within(java.time.Duration.ofSeconds(10))
                                .passOn(COMMIT))
                .isNull();
        other.join();
        assertThat(runner.only(" push ").timeout())
                .as("the limit less the wait of about two seconds")
                .isLessThan(java.time.Duration.ofMillis(8500))
                .isGreaterThan(java.time.Duration.ofSeconds(5));
    }

    // Another push of the task waiting on a slow forge held the lock with no bound on the wait.
    @Test
    void theWaitForAnotherPushOfTheTaskIsPartOfTheLimit() throws Exception {
        final Path mirror = dir.resolve("mirror.git");
        final Path lock = mirror.resolve(GitGate.HOST_PUSHED).resolve(".fix.lock");
        Files.createDirectories(lock.getParent());
        final java.util.concurrent.CountDownLatch held = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        final Thread other =
                Thread.ofVirtual()
                        .start(
                                () -> {
                                    try {
                                        FileLocks.holding(
                                                lock,
                                                () -> {
                                                    held.countDown();
                                                    try {
                                                        done.await();
                                                    } catch (InterruptedException ex) {
                                                        Thread.currentThread().interrupt();
                                                    }
                                                    return null;
                                                });
                                    } catch (Exception ex) {
                                        throw new IllegalStateException(ex);
                                    }
                                });
        held.await();
        try {
            final java.time.Instant started = java.time.Instant.now();

            assertThat(
                            push(mirror, conf("git_port=3001\n"), TOKEN)
                                    .within(java.time.Duration.ofSeconds(2))
                                    .passOn(COMMIT))
                    .isEqualTo(
                            "another push of porelo/fix held it longer than 2 s; nothing was"
                                    + " passed on - push again");
            assertThat(java.time.Duration.between(started, java.time.Instant.now()))
                    .isLessThan(java.time.Duration.ofSeconds(10));
            assertThat(runner.invocations()).isEmpty();
        } finally {
            done.countDown();
            other.join();
        }
    }

    // Git's error output goes back to the task: the token must not travel with it.
    @Test
    void theTokenIsMaskedInWhatTheForgeAnswers() throws IOException {
        runner.failing("push", 128, "fatal: Authentication failed for t0ken\u001b[2J\n");
        assertThat(push(dir.resolve("mirror.git"), conf("git_port=3001\n"), "t0ken").passOn(COMMIT))
                .contains("<token>")
                .doesNotContain("t0ken", "\u001b");
    }

    private HostPush push(Path mirror, Path conf, String token) {
        return new HostPush(context(), mirror, "fix", "acme/demo", () -> Optional.of(token), conf);
    }

    private Path conf(String text) throws IOException {
        final Path file = Files.createTempFile(dir, "forge", ".conf");
        return Files.writeString(file, text);
    }

    private PoreloContext context() {
        return new PoreloContext(
                runner,
                new PoreloPaths(XdgPaths.of(name -> null, dir.resolve("home")), dir.resolve("bin")),
                args -> 0);
    }
}
