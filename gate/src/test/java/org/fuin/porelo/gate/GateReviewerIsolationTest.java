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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A reviewer's gate isolates on the server's side: asked from an empty repository, or with a
 * hand-written request, it gives out nothing the pin does not reach, even where the served
 * repository's own configuration would allow any want.
 */
class GateReviewerIsolationTest {

    private static final String TASK = "reviewer";

    private static final String PINNED = GitGate.PINNED + TASK;

    @TempDir Path dir;

    private final CommandRunner runner = new ProcessCommandRunner();

    private Path mirror;

    private String base;

    private String reviewed;

    private String hidden;

    private String tag;

    private TaskToken token;

    private GitHttpServer server;

    private String url;

    @BeforeEach
    void anotherTasksWorkAndATagOnThePinnedCommitLieInTheMirror() throws Exception {
        final Path upstream = dir.resolve("upstream.git");
        final Path work = dir.resolve("work");
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
        git(work, "tag", "-a", "-m", "not the reviewer's", "hidden-tag", reviewed);
        tag = git(work, "rev-parse", "hidden-tag");
        git(work, "commit", "--allow-empty", "-q", "-m", "another task's unreviewed work");
        hidden = git(work, "rev-parse", "HEAD");
        git(
                work,
                "push",
                "-q",
                "--force",
                mirror.toString(),
                hidden + ":" + GitGate.INCOMING + "foo-1",
                "refs/tags/hidden-tag:refs/tags/hidden-tag");
        git(mirror, "update-ref", PINNED, reviewed);

        final Path served = dir.resolve("state").resolve("pinned").resolve(TASK + ".git");
        Files.createDirectories(served.getParent());
        PinnedRepository.build(runner, mirror, served, TASK, reviewed, base);
        // As the host's or the repository's own configuration could have it.
        git(served, "config", "uploadpack.allowAnySHA1InWant", "true");
        git(served, "config", "uploadpack.allowTipSHA1InWant", "true");

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
                        + "/repo.git";
    }

    @AfterEach
    void close() {
        server.close();
    }

    @Test
    void anEmptyRepositoryFetchesThePinnedStateAndNoTag() {
        final Path empty = empty();
        git(empty, "fetch", "-q", url, PINNED + ":" + PINNED);
        assertThat(git(empty, "rev-parse", PINNED))
                .as("the pinned head, fetched into an empty repository")
                .isEqualTo(reviewed);
        assertThat(holds(empty, tag))
                .as("whether the annotated tag on the pinned commit came with it")
                .isFalse();
        assertThat(holds(empty, hidden)).as("whether another task's work came with it").isFalse();
    }

    @Test
    void anEmptyRepositoryFetchingAHiddenCommitByItsIdIsRefused() {
        final Path empty = empty();
        assertThatThrownBy(() -> git(empty, "fetch", "-q", url, hidden + ":refs/taken"))
                .as("another task's work, asked for by its id")
                .isInstanceOf(RuntimeException.class);
        assertThat(holds(empty, hidden)).as("whether the hidden commit arrived").isFalse();
    }

    @Test
    void aWantOfAHiddenCommitIsRefused() throws Exception {
        final String answer =
                uploadPack(
                        pkt("want " + hidden + " multi_ack_detailed side-band-64k ofs-delta\n")
                                + "0000"
                                + pkt("done\n"));
        assertThat(answer).as("the answer to a want of a hidden commit").doesNotContain("PACK");
    }

    @Test
    void aHaveOfAHiddenCommitIsNotAcknowledged() throws Exception {
        final String answer =
                uploadPack(
                        pkt("want " + reviewed + " multi_ack_detailed\n")
                                + "0000"
                                + pkt("have " + hidden + "\n")
                                + "0000");
        assertThat(answer)
                .as("the negotiation's answer to a have of a hidden commit")
                .doesNotContain("ACK " + hidden);
    }

    @Test
    void anIncludeTagRequestBringsNoHiddenTag() throws Exception {
        final byte[] answer =
                uploadPackBytes(
                        pkt("want " + reviewed + " ofs-delta include-tag\n")
                                + "0000"
                                + pkt("done\n"));
        final String text = new String(answer, StandardCharsets.ISO_8859_1);
        assertThat(text).as("a pack was sent for the pinned head").contains("PACK");
        final Path empty = empty();
        final Path pack = empty.resolve("objects/pack/pack-answer.pack");
        final int start = text.indexOf("PACK");
        Files.write(pack, java.util.Arrays.copyOfRange(answer, start, answer.length));
        git(empty, "index-pack", pack.toString());
        assertThat(holds(empty, reviewed)).as("whether the pinned head was in the pack").isTrue();
        assertThat(holds(empty, tag))
                .as("whether the annotated tag on the pinned commit was in the pack")
                .isFalse();
    }

    @Test
    void theAdvertisementNamesNoRefButThePinnedOneSymrefIncluded() throws Exception {
        final HttpResponse<String> discovery =
                HttpClient.newHttpClient()
                        .send(
                                HttpRequest.newBuilder(
                                                URI.create(
                                                        url + "/info/refs?service=git-upload-pack"))
                                        .header("Authorization", token.basicAuthorization())
                                        .GET()
                                        .build(),
                                HttpResponse.BodyHandlers.ofString(StandardCharsets.ISO_8859_1));
        final List<String> named = new ArrayList<>();
        final Matcher refs = Pattern.compile("refs/[^\\s\\x00]+").matcher(discovery.body());
        while (refs.find()) {
            named.add(refs.group());
        }
        assertThat(named).as("every ref the advertisement names").containsOnly(PINNED);
        assertThat(discovery.body())
                .as("the advertisement")
                .doesNotContain(hidden)
                .doesNotContain(tag)
                .doesNotContain("refs/heads/");
    }

    private String uploadPack(final String body) throws Exception {
        return new String(uploadPackBytes(body), StandardCharsets.ISO_8859_1);
    }

    private byte[] uploadPackBytes(final String body) throws Exception {
        return HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(URI.create(url + "/git-upload-pack"))
                                .header("Authorization", token.basicAuthorization())
                                .header("Content-Type", "application/x-git-upload-pack-request")
                                .POST(
                                        HttpRequest.BodyPublishers.ofString(
                                                body, StandardCharsets.US_ASCII))
                                .build(),
                        HttpResponse.BodyHandlers.ofByteArray())
                .body();
    }

    private Path empty() {
        final Path empty = dir.resolve("empty-" + System.nanoTime() + ".git");
        git(dir, "init", "--bare", "-q", empty.toString());
        git(empty, "config", "http.extraHeader", "Authorization: " + token.basicAuthorization());
        return empty;
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

    private static String pkt(final String payload) {
        return String.format("%04x", payload.getBytes(StandardCharsets.UTF_8).length + 4) + payload;
    }

    private String git(final Path in, final String... args) {
        final List<String> all = new ArrayList<>(List.of("git", "-C", in.toString()));
        all.addAll(List.of(args));
        return runner.runOrFail(Command.of(all)).standardOutput().strip();
    }
}
