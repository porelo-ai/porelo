package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.fuin.porelo.gate.GitGate;
import org.fuin.porelo.gate.GitHttpServer;
import org.fuin.porelo.gate.GitSubprocess;
import org.fuin.porelo.gate.TaskToken;
import org.fuin.porelo.gate.review.PlaceUnreadable;
import org.fuin.porelo.gate.review.Push;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A task that reads another task's waiting push is pinned to it with real git: its gate, serving
 * the task's own repository at the pinned ref, shows that push alone, and a second waiting push of
 * the project is neither among the refs it can fetch nor reachable by a want of its commit.
 */
class WaitingPushPinsTest {

    private static final String READER = "reader";

    private static final String PINNED = GitGate.PINNED + READER;

    /** The reading task's recorded container id. */
    private static final String READER_ID = "bbbbbbbbbbbb";

    /** The recorded container id of the task that pushed the work read. */
    private static final String PUSHER_ID = "aaaaaaaaaaaa";

    @TempDir Path dir;

    private final CommandRunner runner = new ProcessCommandRunner();

    private Path mirror;

    private Path state;

    private Path served;

    private String base;

    private String reviewed;

    private String sibling;

    @BeforeEach
    void twoTasksPushesWaitInTheProjectsMirror() throws IOException {
        mirror = dir.resolve("mirror.git");
        git(dir, "init", "--bare", "-q", mirror.toString());
        final Path work = dir.resolve("work");
        git(dir, "init", "-q", work.toString());
        git(work, "config", "user.name", "Developer");
        git(work, "config", "user.email", "developer@example.org");
        git(work, "commit", "--allow-empty", "-q", "-m", "base");
        base = git(work, "rev-parse", "HEAD");
        git(work, "commit", "--allow-empty", "-q", "-m", "the work read");
        reviewed = git(work, "rev-parse", "HEAD");
        git(work, "checkout", "-q", "-b", "sibling", base);
        git(work, "commit", "--allow-empty", "-q", "-m", "another task's unreviewed work");
        sibling = git(work, "rev-parse", "HEAD");
        git(
                work,
                "push",
                "-q",
                mirror.toString(),
                base + ":refs/heads/main",
                reviewed + ":" + GitGate.INCOMING + "foo-1",
                sibling + ":" + GitGate.INCOMING + "foo-2");
        state = Files.createDirectories(dir.resolve("state"));
        Files.createDirectories(dir.resolve("record"));
        new RunPlace(
                        new org.fuin.porelo.gate.review.TaskParts.Asked(
                                        java.util.Set.of(
                                                org.fuin.porelo.gate.review.Right.REPLY,
                                                org.fuin.porelo.gate.review.Right.OPEN_THREADS),
                                        null,
                                        null,
                                        null,
                                        false,
                                        false,
                                        false)
                                .atAnothersWork(),
                        null,
                        "foo-1")
                .writeTo(state);
        served = dir.resolve("served").resolve(READER + ".git");
    }

    @Test
    void theGateShowsThePinnedPushAloneAndRefusesAWantOfTheSecond() throws Exception {
        WaitingPushPins.pin(runner, mirror, gate("demo"), READER_ID, push("demo"));

        assertThat(java.util.Objects.requireNonNull(RunPlace.readFrom(state)).pinned())
                .as("the state the run is pinned to")
                .isEqualTo(new RunPlace.Pin(reviewed, base, null, PUSHER_ID));
        final TaskToken token = TaskToken.mint();
        final GitHttpServer server =
                GitHttpServer.reviewing(
                        new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                        served,
                        token,
                        new GitSubprocess(),
                        PINNED);
        server.start();
        try {
            final String url =
                    "http://"
                            + InetAddress.getLoopbackAddress().getHostAddress()
                            + ":"
                            + server.port()
                            + "/repo.git";
            final Path empty = dir.resolve("empty.git");
            git(dir, "init", "--bare", "-q", empty.toString());
            git(
                    empty,
                    "config",
                    "http.extraHeader",
                    "Authorization: " + token.basicAuthorization());

            final List<String> refs =
                    Arrays.stream(git(empty, "ls-remote", url).split("\n"))
                            .map(line -> line.substring(line.indexOf('\t') + 1))
                            .toList();
            assertThat(refs).as("every ref the task can fetch").containsOnly(PINNED);
            assertThat(git(empty, "ls-remote", url))
                    .as("what the gate shows")
                    .doesNotContain(sibling)
                    .doesNotContain("foo-2");
            git(empty, "fetch", "-q", url, PINNED + ":" + PINNED);
            assertThat(git(empty, "rev-parse", PINNED)).as("the head fetched").isEqualTo(reviewed);
            assertThatThrownBy(() -> git(empty, "fetch", "-q", url, sibling + ":refs/taken"))
                    .as("a want of the second waiting push's commit")
                    .isInstanceOf(RuntimeException.class);
            assertThat(holds(empty, sibling)).as("whether it arrived").isFalse();
            assertThat(holds(served, sibling))
                    .as("whether the served repository holds it at all")
                    .isFalse();
        } finally {
            server.close();
        }
    }

    @Test
    void theReadingTasksOwnPushIsNeverPinned() {
        final PlaceUnreadable refused =
                catchThrowableOfType(
                        PlaceUnreadable.class,
                        () ->
                                WaitingPushPins.pin(
                                        runner, mirror, gate("demo"), PUSHER_ID, push("demo")));

        assertThat(refused.reason()).as("why").isEqualTo(PlaceUnreadable.Reason.NOT_IN_PROJECT);
        assertThat(Files.exists(served)).as("whether a repository was built").isFalse();
    }

    @Test
    void anotherProjectsPushIsNeverPinned() {
        final PlaceUnreadable refused =
                catchThrowableOfType(
                        PlaceUnreadable.class,
                        () ->
                                WaitingPushPins.pin(
                                        runner, mirror, gate("demo"), READER_ID, push("shop")));

        assertThat(refused.reason()).as("why").isEqualTo(PlaceUnreadable.Reason.NOT_IN_PROJECT);
        assertThat(Files.exists(served)).as("whether a repository was built").isFalse();
    }

    private PullRequestPins.TaskGate gate(final String project) {
        return new PullRequestPins.TaskGate(
                mirror, state, dir.resolve("record"), served, READER, project, project, "foo-1");
    }

    private Push push(final String project) {
        return new Push(project, project, "foo-1", reviewed, base, PUSHER_ID);
    }

    private boolean holds(final Path repository, final String object) {
        return runner.run(
                        Command.of(
                                List.of(
                                        "git",
                                        "-C",
                                        repository.toString(),
                                        "cat-file",
                                        "-e",
                                        object)))
                .successful();
    }

    private String git(final Path in, final String... args) {
        final List<String> all = new ArrayList<>(List.of("git", "-C", in.toString()));
        all.addAll(List.of(args));
        return runner.runOrFail(Command.of(all)).standardOutput().strip();
    }
}
