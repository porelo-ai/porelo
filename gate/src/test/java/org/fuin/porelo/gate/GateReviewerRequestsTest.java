package org.fuin.porelo.gate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a reviewer's gate asks git, with git stood in: no {@code receive-pack} at all, and a fetch
 * that shows the pinned ref alone.
 */
class GateReviewerRequestsTest {

    private static final String PINNED = "refs/porelo/pinned/reviewer";

    @TempDir Path dir;

    private final List<List<String>> asked = new ArrayList<>();

    private final TaskToken token = TaskToken.mint();

    @Test
    void receivePackIsRefusedAtDiscoveryAndAtItsCallWithoutAskingGit() throws Exception {
        try (GitHttpServer server = started()) {
            assertThat(get(server, "/repo.git/info/refs?service=git-receive-pack").statusCode())
                    .as("receive-pack's discovery")
                    .isEqualTo(403);
            assertThat(post(server, "/repo.git/git-receive-pack").statusCode())
                    .as("receive-pack's call")
                    .isEqualTo(403);
        }
        assertThat(asked)
                .as("what git was asked")
                .noneMatch(arguments -> arguments.contains("receive-pack"));
    }

    @Test
    void aFetchIsShownThePinnedRefAloneNotEvenHead() throws Exception {
        try (GitHttpServer server = started()) {
            assertThat(get(server, "/repo.git/info/refs?service=git-upload-pack").statusCode())
                    .as("upload-pack's discovery")
                    .isEqualTo(200);
            assertThat(post(server, "/repo.git/git-upload-pack").statusCode())
                    .as("upload-pack's call")
                    .isEqualTo(200);
        }
        assertThat(asked).as("what git was asked").hasSize(2);
        for (final List<String> arguments : asked) {
            assertThat(arguments)
                    .as("a fetch's arguments")
                    .startsWith(
                            "-c",
                            "transfer.hideRefs=HEAD",
                            "-c",
                            "transfer.hideRefs=refs/",
                            "-c",
                            "transfer.hideRefs=!" + PINNED)
                    .contains("upload-pack");
        }
    }

    @Test
    void everyFetchRunsWithWantsOfUnadvertisedCommitsPinnedOffOnItsCommandLine() throws Exception {
        try (GitHttpServer server = started()) {
            get(server, "/repo.git/info/refs?service=git-upload-pack");
            post(server, "/repo.git/git-upload-pack");
        }
        assertThat(asked).as("what git was asked").hasSize(2);
        for (final List<String> arguments : asked) {
            final List<String> before =
                    arguments.subList(0, Math.max(0, arguments.indexOf("upload-pack")));
            assertThat(before)
                    .as("the configuration a fetch's git runs with")
                    .containsSequence("-c", "uploadpack.allowAnySHA1InWant=false")
                    .containsSequence("-c", "uploadpack.allowReachableSHA1InWant=false")
                    .containsSequence("-c", "uploadpack.allowTipSHA1InWant=false");
        }
    }

    @Test
    void anAuthorsGateHidesEveryReviewersPinnedState() {
        assertThat(GitHttpServer.hidingOthers(GitGate.INCOMING + "author", "upload-pack"))
                .as("an author's fetch arguments")
                .containsSequence("-c", "transfer.hideRefs=" + GitGate.PINNED);
    }

    @Test
    void aPinnedRefThatIsNoPlainRefNameIsRefused() {
        for (final String name : List.of("refs/porelo/*", "HEAD", "refs/a/../b", "refs/a b", "")) {
            assertThatThrownBy(() -> server(name))
                    .as("a reviewer's gate pinned to '%s'", name)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private GitHttpServer started() {
        final GitHttpServer server = server(PINNED);
        server.start();
        return server;
    }

    private GitHttpServer server(final String pinned) {
        return GitHttpServer.reviewing(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                dir.resolve("repo.git"),
                token,
                (arguments, input) -> {
                    synchronized (asked) {
                        asked.add(List.copyOf(arguments));
                    }
                    return new byte[0];
                },
                pinned);
    }

    private HttpResponse<String> get(final GitHttpServer server, final String path)
            throws Exception {
        return HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(uri(server, path))
                                .header("Authorization", token.basicAuthorization())
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(final GitHttpServer server, final String path)
            throws Exception {
        return HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(uri(server, path))
                                .header("Authorization", token.basicAuthorization())
                                .POST(HttpRequest.BodyPublishers.ofString("0000"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
    }

    private static URI uri(final GitHttpServer server, final String path) {
        return URI.create(
                "http://"
                        + InetAddress.getLoopbackAddress().getHostAddress()
                        + ":"
                        + server.port()
                        + path);
    }
}
