package org.fuin.porelo.organization.gittree;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.fuin.porelo.organization.IdentitySource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A display refresh must never roll the trust record back behind a decision's newer read. */
class GitTreeRefreshTest {
    @TempDir Path dir;

    // A slow display refresh must not erase a withdrawal already checked by another reader.
    @Test
    void concurrentReadersCannotOverwriteANewerTrustRecord() throws Exception {
        final GitTreeFixture fixture = new GitTreeFixture(dir);
        fixture.found("mia");
        final IdentitySource identities =
                new IdentitySource() {
                    @Override
                    public java.util.Optional<Keys> keys(String login) {
                        return java.util.Optional.of(
                                new Keys(java.util.List.of(fixture.key(login)), true));
                    }

                    @Override
                    public java.util.Optional<Boolean> owns(String organization, String login) {
                        return java.util.Optional.of("mia".equals(login));
                    }
                };
        final ProcessCommandRunner runner = new ProcessCommandRunner();
        final Path bare = dir.resolve("origin.git");
        runner.runOrFail(
                Command.of(
                                "git",
                                "clone",
                                "-q",
                                "--bare",
                                dir.resolve("organization").toString(),
                                bare.toString())
                        .withEnvironment(
                                java.util.Map.of(
                                        "GIT_CONFIG_GLOBAL",
                                        "/dev/null",
                                        "GIT_CONFIG_NOSYSTEM",
                                        "1")));
        final Path state = dir.resolve("state");
        final GitTreeStore normal =
                GitTreeStore.at(
                        runner,
                        bare.toString(),
                        "acme",
                        "forge.example.org",
                        FetchAccess.NONE,
                        state);
        final String first = normal.read(identities).revision();
        final CountDownLatch fetched = new CountDownLatch(1);
        final CountDownLatch resume = new CountDownLatch(1);
        final CommandRunner blocked =
                command -> {
                    final var result = runner.run(command);
                    if (command.arguments().contains("fetch")) {
                        fetched.countDown();
                        try {
                            assertThat(resume.await(15, TimeUnit.SECONDS))
                                    .as("the old fetch is released")
                                    .isTrue();
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(ex);
                        }
                    }
                    return result;
                };
        try (var threads = Executors.newFixedThreadPool(2)) {
            final var old =
                    threads.submit(
                            () ->
                                    GitTreeStore.at(
                                                    blocked,
                                                    bare.toString(),
                                                    "acme",
                                                    "forge.example.org",
                                                    FetchAccess.NONE,
                                                    state)
                                            .read(identities));
            try {
                assertThat(fetched.await(10, TimeUnit.SECONDS))
                        .as("the older reader has fetched")
                        .isTrue();
                assertThat(normal.last().orElseThrow().revision())
                        .as("display does not wait for the fetch lock")
                        .isEqualTo(first);
                fixture.fileCard("mia");
                runner.runOrFail(
                        Command.of(
                                        "git",
                                        "--git-dir=" + bare,
                                        "fetch",
                                        "-q",
                                        dir.resolve("organization").toString(),
                                        "main:main")
                                .withEnvironment(
                                        java.util.Map.of(
                                                "GIT_CONFIG_GLOBAL",
                                                "/dev/null",
                                                "GIT_CONFIG_NOSYSTEM",
                                                "1")));
                final CountDownLatch reading = new CountDownLatch(1);
                final var newer =
                        threads.submit(
                                () -> {
                                    reading.countDown();
                                    return normal.read(identities);
                                });
                assertThat(reading.await(5, TimeUnit.SECONDS))
                        .as("the second read has started")
                        .isTrue();
                // If reads serialize, this wait expires; without the lock the newer record wins
                // first.
                try {
                    newer.get(1, TimeUnit.SECONDS);
                } catch (java.util.concurrent.TimeoutException expected) {
                }
                resume.countDown();
                old.get(10, TimeUnit.SECONDS);
                final String tip = newer.get(10, TimeUnit.SECONDS).revision();
                assertThat(normal.last().orElseThrow().revision())
                        .as("a late old refresh cannot erase the new state")
                        .isEqualTo(tip)
                        .isNotEqualTo(first);
            } finally {
                resume.countDown();
            }
        }
    }
}
