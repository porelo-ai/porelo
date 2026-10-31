package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.fuin.porelo.core.config.XdgPaths;
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
                        push(mirror, conf("host=walk6m\nport=3000\ngit_port=3001\n"), "t0ken")
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
                        "refs/heads/porelo/fix:refs/heads/porelo/fix")
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
