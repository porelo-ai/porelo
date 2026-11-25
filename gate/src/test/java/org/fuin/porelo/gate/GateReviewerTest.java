package org.fuin.porelo.gate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A reviewer's gate shows exactly one pinned state and takes nothing: every {@code receive-pack}
 * request is refused, at service discovery and at its call alike, its own ref included, and no
 * other ref is advertised or fetched: it serves a repository of its own, built from the mirror.
 */
class GateReviewerTest {

    private static final String PINNED = "refs/porelo/pinned/reviewer";

    private static final String OWN = GitGate.INCOMING + "reviewer";

    @TempDir Path dir;

    private final CommandRunner runner = new ProcessCommandRunner();

    private Path work;

    private Path mirror;

    private Path served;

    private String base;

    private String reviewed;

    private String later;

    private TaskToken token;

    private GitHttpServer server;

    private String url;

    @BeforeEach
    void anotherTasksPushWaitsAndTheReviewerIsPinnedToIt() throws java.io.IOException {
        final Path upstream = dir.resolve("upstream.git");
        work = dir.resolve("work");
        mirror = dir.resolve("mirror.git");
        git(dir, "init", "--bare", "-q", "-b", "main", upstream.toString());
        git(dir, "clone", "-q", upstream.toString(), work.toString());
        git(work, "config", "user.name", "Developer");
        git(work, "config", "user.email", "developer@example.org");
        git(work, "commit", "--allow-empty", "-q", "-m", "base");
        git(work, "push", "-q", "origin", "main");
        new GitGate(runner, mirror, GateMode.GATEKEEPING, upstream.toString()).initialize();
        base = git(work, "rev-parse", "HEAD");
        git(work, "commit", "--allow-empty", "-q", "-m", "the work under review");
        reviewed = git(work, "rev-parse", "HEAD");
        git(work, "commit", "--allow-empty", "-q", "-m", "the work's next push");
        later = git(work, "rev-parse", "HEAD");
        git(
                work,
                "push",
                "-q",
                "--force",
                mirror.toString(),
                later + ":" + GitGate.INCOMING + "foo-1");
        git(mirror, "update-ref", OWN, base);
        git(mirror, "update-ref", PINNED, reviewed);
        served = PinnedRepository.in(dir);
        PinnedRepository.replace(runner, mirror, served, "reviewer", reviewed, base).keep();

        token = TaskToken.mint();
        server =
                GitHttpServer.reviewing(
                        new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                        served,
                        token,
                        new GitSubprocess(),
                        PINNED);
        server.start();
        url =
                "http://"
                        + InetAddress.getLoopbackAddress().getHostAddress()
                        + ":"
                        + server.port()
                        + "/mirror.git";
        git(work, "config", "http.extraHeader", "Authorization: " + token.basicAuthorization());
    }

    @AfterEach
    void close() {
        server.close();
    }

    @Test
    void theReviewerIsShownThePinnedStateAndNothingElse() {
        assertThat(lsRemote())
                .as("what a reviewer's clone lists")
                .containsExactly(Map.entry(PINNED, reviewed));
    }

    @Test
    void theReviewerFetchesThePinnedState() {
        git(work, "fetch", "-q", url, PINNED + ":refs/remotes/review/pinned");
        assertThat(git(work, "rev-parse", "refs/remotes/review/pinned"))
                .as("the pinned head, fetched")
                .isEqualTo(reviewed);
    }

    @Test
    void aCommitOfAHiddenRefIsNotFetched() {
        // Into an empty repository: one that holds the commit already fetches it without asking.
        final Path empty = dir.resolve("empty.git");
        git(dir, "init", "--bare", "-q", empty.toString());
        git(empty, "config", "http.extraHeader", "Authorization: " + token.basicAuthorization());
        assertThatThrownBy(() -> git(empty, "fetch", "-q", url, later + ":refs/taken"))
                .as("the waiting push's next head, asked for by its id")
                .isInstanceOf(RuntimeException.class);
        assertThat(
                        runner.run(
                                        Command.of(
                                                List.of(
                                                        "git",
                                                        "-C",
                                                        empty.toString(),
                                                        "cat-file",
                                                        "-e",
                                                        later)))
                                .successful())
                .as("whether the hidden commit arrived")
                .isFalse();
    }

    @Test
    void aPushIsRefusedItsOwnRefIncluded() {
        git(work, "commit", "--allow-empty", "-q", "-m", "the reviewer's own change");
        for (final String target : List.of(OWN, PINNED, "refs/heads/main")) {
            assertThatThrownBy(() -> git(work, "push", url, "HEAD:" + target))
                    .as("a push to %s", target)
                    .isInstanceOf(RuntimeException.class);
        }
        assertThat(git(mirror, "rev-parse", OWN)).as("its own ref did not move").isEqualTo(base);
        assertThat(git(mirror, "rev-parse", PINNED))
                .as("the pinned ref did not move")
                .isEqualTo(reviewed);
    }

    @Test
    void receivePackIsRefusedAtDiscoveryAndAtItsCall() throws Exception {
        final HttpClient client = HttpClient.newHttpClient();
        final HttpResponse<String> discovery =
                client.send(
                        HttpRequest.newBuilder(
                                        URI.create(url + "/info/refs?service=git-receive-pack"))
                                .header("Authorization", token.basicAuthorization())
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(discovery.statusCode()).as("receive-pack's discovery").isEqualTo(403);
        assertThat(discovery.body()).as("no ref is advertised for a push").doesNotContain(OWN);
        final String command = reviewed + " " + later + " " + OWN + "\0report-status\n";
        final HttpResponse<String> call =
                client.send(
                        HttpRequest.newBuilder(URI.create(url + "/git-receive-pack"))
                                .header("Authorization", token.basicAuthorization())
                                .header("Content-Type", "application/x-git-receive-pack-request")
                                .POST(
                                        HttpRequest.BodyPublishers.ofString(
                                                pkt(command) + "0000", StandardCharsets.UTF_8))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(call.statusCode()).as("receive-pack's call").isEqualTo(403);
        assertThat(git(mirror, "rev-parse", OWN)).as("its own ref did not move").isEqualTo(base);
    }

    @Test
    void pinningAfreshReplacesWhatTheRunningGateShows() throws java.io.IOException {
        git(mirror, "update-ref", PINNED, later);
        PinnedRepository.replace(runner, mirror, served, "reviewer", later, base).keep();
        assertThat(lsRemote())
                .as("what the running gate lists once pinned afresh")
                .containsExactly(Map.entry(PINNED, later));
    }

    private Map<String, String> lsRemote() {
        final Map<String, String> refs = new LinkedHashMap<>();
        for (final String line : git(work, "ls-remote", url).split("\n")) {
            if (!line.isBlank()) {
                final String[] parts = line.split("\t");
                refs.put(parts[1], parts[0]);
            }
        }
        return refs;
    }

    private static String pkt(final String payload) {
        return String.format("%04x", payload.getBytes(StandardCharsets.UTF_8).length + 4) + payload;
    }

    private String git(final Path in, final String... args) {
        final List<String> all = new ArrayList<>(List.of("git", "-C", in.toString()));
        all.addAll(List.of(args));
        return runner.runOrFail(Command.of(all)).standardOutput().strip();
    }
}
