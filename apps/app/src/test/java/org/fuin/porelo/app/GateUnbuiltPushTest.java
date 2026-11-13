package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.fuin.porelo.gate.GateMode;
import org.fuin.porelo.gate.GitGate;
import org.fuin.porelo.gate.GitHttpServer;
import org.fuin.porelo.gate.GitSubprocess;
import org.fuin.porelo.gate.TaskToken;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Test for {@link UnbuiltPush} at a task's gate: a push that is not built while the task runs is
 * answered at once with {@code build-<commit>.txt}, verdict {@code none}, and why.
 */
class GateUnbuiltPushTest {

    private static final String TASK = "fix";

    private static final String REF = GitGate.INCOMING + TASK;

    private static final Instant NOW = Instant.parse("2026-11-13T10:15:30Z");

    private final CommandRunner runner = new ProcessCommandRunner(Duration.ofSeconds(60));

    @TempDir Path root;

    private Path upstream;

    private Path mirror;

    private Path work;

    /** What the task was handed, by name, with its content, as it was when the push returned. */
    private final Map<String, String> handed = new LinkedHashMap<>();

    @BeforeEach
    void anUpstreamWithMain() throws IOException {
        upstream = root.resolve("upstream.git");
        mirror = root.resolve("mirror.git");
        work = root.resolve("work");
        git(root, "init", "-q", "--bare", "--initial-branch=main", upstream.toString());
        final Path seed = root.resolve("seed");
        git(root, "init", "-q", "--initial-branch=main", seed.toString());
        Files.writeString(seed.resolve("project.yml"), "project: {}\n");
        git(seed, "add", "project.yml");
        git(seed, "commit", "-q", "-m", "the project");
        git(seed, "push", "-q", upstream.toString(), "HEAD:refs/heads/main");
    }

    @Test
    void aPushHeldAtTheGateForAPersonIsAnsweredAtOnceWithVerdictNoneAndWhy() throws IOException {
        final GitGate gate = new GitGate(runner, mirror, GateMode.GATEKEEPING, upstream.toString());
        gate.initialize();
        final TaskToken token = TaskToken.mint();
        try (GitHttpServer server = serving(token, null, UnbuiltPush.why(false, true))) {
            final String commit = pushed(server, token);

            // An agent waited 30 minutes for a build of work that waited for a person.
            assertThat(handed)
                    .as("handed in before the push returned")
                    .containsOnlyKeys("build-" + commit.substring(0, 12) + ".txt");
            assertThat(handed.get("build-" + commit.substring(0, 12) + ".txt"))
                    .as("the format the guide describes: commit, verdict, since, detail")
                    .isEqualTo(
                            "commit: "
                                    + commit
                                    + "\nverdict: none\nsince: 2026-11-13T10:15:30Z\n"
                                    + "detail: held at the gate for a person; nothing is built"
                                    + " until it is passed on\n");
        }
    }

    @Test
    void aPushPassedOnInAProjectThatFollowsNoBuildsIsAnsweredAtOnceWithWhy() throws IOException {
        final GitGate gate = new GitGate(runner, mirror, GateMode.ONLINE, upstream.toString());
        gate.initialize();
        final TaskToken token = TaskToken.mint();
        try (GitHttpServer server =
                serving(
                        token,
                        new GitHttpServer.Upstream() {
                            @Override
                            public void refresh() {
                                gate.refresh();
                            }

                            @Override
                            public @Nullable String passOn(final String ref, final String commit) {
                                return gate.passOn("porelo/" + TASK, commit);
                            }
                        },
                        UnbuiltPush.why(true, false))) {
            final String commit = pushed(server, token);

            assertThat(handed.get("build-" + commit.substring(0, 12) + ".txt"))
                    .as("passed on, but nothing reports its build")
                    .contains("verdict: none\n")
                    .contains("detail: " + UnbuiltPush.NO_BUILDS + "\n");
        }
    }

    @Test
    void aPushThatIsRefusedHandsInNothing() throws IOException {
        final GitGate gate = new GitGate(runner, mirror, GateMode.GATEKEEPING, upstream.toString());
        gate.initialize();
        final TaskToken token = TaskToken.mint();
        try (GitHttpServer server =
                serving(token, null, UnbuiltPush.HELD)
                        .judging((old, commit) -> "commit " + commit + " is not signed")) {
            assertThatThrownBy(() -> pushed(server, token)).hasMessageContaining("not signed");

            assertThat(handed).as("the push did not land: nothing is waited for").isEmpty();
        }
    }

    @Test
    void aPushGitRejectsHandsInNothingForItsCommit() throws IOException {
        final GitGate gate = new GitGate(runner, mirror, GateMode.GATEKEEPING, upstream.toString());
        gate.initialize();
        final TaskToken token = TaskToken.mint();
        try (GitHttpServer server = serving(token, null, UnbuiltPush.HELD)) {
            pushed(server, token);
            handed.clear();
            // receive-pack refuses the ref, here through a hook, and still ends well: only its
            // report says the ref was not updated.
            final Path hook = mirror.resolve("hooks").resolve("update");
            Files.createDirectories(hook.getParent());
            Files.writeString(hook, "#!/bin/sh\necho 'the mirror takes no more' >&2\nexit 1\n");
            hook.toFile().setExecutable(true);
            Files.writeString(work.resolve("more.txt"), "more of the agent's work");
            git(work, "add", "more.txt");
            git(work, "commit", "-q", "-m", "more of the agent's work");

            assertThatThrownBy(() -> git(work, "push", "-q", url(server), "HEAD:" + REF))
                    .hasMessageContaining("hook declined");

            assertThat(handed).as("the push did not land: nothing is waited for").isEmpty();
        }
    }

    @Test
    void onlyAPushPassedOnInAProjectThatFollowsItsBuildsIsLeftToTheBuildWatch() {
        assertThat(UnbuiltPush.why(false, true)).isEqualTo(UnbuiltPush.HELD);
        assertThat(UnbuiltPush.why(false, false)).isEqualTo(UnbuiltPush.HELD);
        assertThat(UnbuiltPush.why(true, false)).isEqualTo(UnbuiltPush.NO_BUILDS);
        assertThat(UnbuiltPush.why(true, true)).as("the build watch hands in its verdict").isNull();
    }

    private GitHttpServer serving(
            final TaskToken token,
            final GitHttpServer.@Nullable Upstream through,
            final @Nullable String why) {
        final GitHttpServer server =
                new GitHttpServer(
                        new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                        mirror,
                        token,
                        new GitSubprocess(),
                        GitGate.INCOMING + TASK,
                        through,
                        Duration.ZERO);
        if (why != null) {
            server.taking(
                    new UnbuiltPush(
                            (name, file) -> handed.put(name, Files.readString(file)),
                            root.resolve("state").resolve("builds"),
                            Clock.fixed(NOW.plusMillis(400), ZoneOffset.UTC),
                            why));
        }
        server.start();
        return server;
    }

    /** Clones through the gate, commits, and pushes to the task's ref: the commit pushed. */
    private String pushed(final GitHttpServer server, final TaskToken token) throws IOException {
        final String url = url(server);
        git(
                root,
                "clone",
                "--quiet",
                "-c",
                "http.extraHeader=Authorization: " + token.basicAuthorization(),
                url,
                work.toString());
        git(work, "config", "http.extraHeader", "Authorization: " + token.basicAuthorization());
        Files.writeString(work.resolve("agent.txt"), "written by the agent");
        git(work, "add", "agent.txt");
        git(work, "commit", "-q", "-m", "the agent's work");
        final String commit = git(work, "rev-parse", "HEAD").standardOutput().strip();
        git(work, "push", "-q", url, "HEAD:" + REF);
        return commit;
    }

    private static String url(final GitHttpServer server) {
        return "http://"
                + InetAddress.getLoopbackAddress().getHostAddress()
                + ":"
                + server.port()
                + "/mirror.git";
    }

    private CommandResult git(final Path directory, final String... arguments) {
        final List<String> all = new ArrayList<>(List.of("git"));
        all.addAll(List.of(arguments));
        return runner.runOrFail(
                new Command(
                        all,
                        directory,
                        Map.of(
                                "GIT_AUTHOR_NAME",
                                "Test",
                                "GIT_AUTHOR_EMAIL",
                                "test@example.com",
                                "GIT_COMMITTER_NAME",
                                "Test",
                                "GIT_COMMITTER_EMAIL",
                                "test@example.com",
                                "GIT_TERMINAL_PROMPT",
                                "0",
                                "GIT_CONFIG_GLOBAL",
                                "/dev/null",
                                "GIT_CONFIG_NOSYSTEM",
                                "1"),
                        null));
    }
}
