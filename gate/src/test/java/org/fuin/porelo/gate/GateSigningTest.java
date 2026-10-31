package org.fuin.porelo.gate;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for a task's gate signing its commits on request. */
class GateSigningTest {

    @TempDir Path dir;

    private final TaskToken token = TaskToken.mint();

    private final List<String> signed = new ArrayList<>();

    @Test
    void aTaskWithItsTokenGetsItsPayloadSignedAndNobodyElseDoes() throws Exception {
        try (GitHttpServer server = server().signing(this::sign)) {
            server.start();

            final HttpResponse<String> answer =
                    post(server, token.basicAuthorization(), "tree 1\n");
            assertThat(answer.statusCode()).isEqualTo(200);
            assertThat(answer.body()).isEqualTo("-----BEGIN SSH SIGNATURE-----\ntree 1\n");

            assertThat(post(server, null, "tree 2\n").statusCode()).isEqualTo(401);
            assertThat(post(server, TaskToken.mint().basicAuthorization(), "tree 3\n").statusCode())
                    .isEqualTo(401);
            assertThat(signed).containsExactly("tree 1\n");
        }
    }

    @Test
    void aGateWithoutAKeySignsNothingAndAPayloadBeyondTheLimitIsRefused() throws Exception {
        try (GitHttpServer server = server()) {
            server.start();
            assertThat(post(server, token.basicAuthorization(), "tree 1\n").statusCode())
                    .isEqualTo(404);
        }
        try (GitHttpServer server = server().signing(this::sign)) {
            server.start();
            assertThat(
                            post(
                                            server,
                                            token.basicAuthorization(),
                                            "x".repeat(GitHttpServer.SIGN_LIMIT + 1))
                                    .statusCode())
                    .isEqualTo(413);
            assertThat(signed).isEmpty();
        }
    }

    private String sign(byte[] payload) {
        final String text = new String(payload, StandardCharsets.UTF_8);
        signed.add(text);
        return "-----BEGIN SSH SIGNATURE-----\n" + text;
    }

    private GitHttpServer server() {
        return new GitHttpServer(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                dir,
                token,
                new GitSubprocess(),
                GitGate.INCOMING + "task-1");
    }

    private static HttpResponse<String> post(
            GitHttpServer server, String authorization, String body) throws Exception {
        final HttpRequest.Builder request =
                HttpRequest.newBuilder(
                                URI.create("http://127.0.0.1:" + server.port() + "/porelo/sign"))
                        .POST(HttpRequest.BodyPublishers.ofString(body));
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
