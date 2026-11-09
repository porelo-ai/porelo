package org.fuin.porelo.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ForgeGateSocketTest {
    private static final AtomicInteger SERIAL = new AtomicInteger();
    private final AtomicInteger logins = new AtomicInteger();

    record Answer(String response, GateHttp.Request forwarded, byte[] body) {}

    private Answer request(
            String mode, String method, String target, String body, String... headers)
            throws Exception {
        final StringBuilder raw =
                new StringBuilder(
                        method
                                + " "
                                + target
                                + " HTTP/1.1\r\nHost: host.localhost:3000\r\nConnection: close\r\n");
        boolean chunked = false;
        for (int i = 0; i < headers.length; i += 2) {
            raw.append(headers[i]).append(": ").append(headers[i + 1]).append("\r\n");
            chunked |= headers[i].equalsIgnoreCase("Transfer-Encoding");
        }
        if (!chunked) raw.append("Content-Length: ").append(body.length()).append("\r\n");
        raw.append("\r\n").append(body);
        return exercise(mode, raw.toString(), 1);
    }

    private Answer exercise(String mode, String raw, int requests) throws Exception {
        return exercise(
                mode,
                raw,
                requests,
                "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nSet-Cookie: session=fixture\r\nConnection: close\r\n\r\nok");
    }

    private Answer exercise(String mode, String raw, int requests, String response)
            throws Exception {
        final Path path = Path.of("target", "gate-" + SERIAL.incrementAndGet() + ".sock");
        final var forwarded = new AtomicReference<GateHttp.Request>();
        final var body = new AtomicReference<>(new byte[0]);
        try (var server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            server.bind(UnixDomainSocketAddress.of(path));
            final FutureTask<Void> serving =
                    new FutureTask<>(
                            () -> {
                                for (int i = 0; i < requests; i++)
                                    try (var accepted = new GateConnection(server.accept())) {
                                        final var received = GateHttp.request(accepted.input);
                                        forwarded.set(received);
                                        final ByteArrayOutputStream bytes =
                                                new ByteArrayOutputStream();
                                        if (received.headers()
                                                .first("Transfer-Encoding")
                                                .equals("chunked"))
                                            GateHttp.chunks(accepted.input, bytes, false, false);
                                        else
                                            GateHttp.fixed(
                                                    accepted.input,
                                                    bytes,
                                                    Long.parseLong(
                                                            received.headers()
                                                                    .first("Content-Length")));
                                        body.set(bytes.toByteArray());
                                        GateHttp.write(accepted.output, response);
                                    } catch (IOException ex) {
                                        // A deliberately incomplete request closes the upstream
                                        // before its response.
                                    }
                                return null;
                            });
            final Thread thread = Thread.ofPlatform().daemon().start(serving);
            try {
                final var gate =
                        new ForgeGate(
                                mode,
                                () -> {
                                    logins.incrementAndGet();
                                    return "person";
                                },
                                () -> ForgeGatePolicyTest.HOSTS,
                                () -> {
                                    final var upstream =
                                            SocketChannel.open(StandardProtocolFamily.UNIX);
                                    upstream.connect(UnixDomainSocketAddress.of(path));
                                    return new GateConnection(upstream);
                                });
                final var answer = new ByteArrayOutputStream();
                gate.serve(
                        new ByteArrayInputStream(raw.getBytes(StandardCharsets.ISO_8859_1)),
                        answer);
                serving.get(5, TimeUnit.SECONDS);
                return new Answer(
                        answer.toString(StandardCharsets.ISO_8859_1), forwarded.get(), body.get());
            } finally {
                server.close();
                thread.join(5000);
            }
        } finally {
            Files.deleteIfExists(path);
        }
    }

    // py: test_caller_identity_headers_never_reach_upstream
    @ParameterizedTest
    @ValueSource(strings = {"web", "git"})
    void callerIdentityHeadersNeverReachUpstream(String mode) throws Exception {
        final var answer =
                request(
                        mode,
                        "GET",
                        "/",
                        "",
                        "x-WebAuth-User",
                        "intruder",
                        "X-WEBAUTH-Email",
                        "intruder@example.org",
                        "Authorization",
                        "token fixture");
        assertThat(answer.response())
                .as("the authenticated request succeeds")
                .startsWith("HTTP/1.1 200");
        assertThat(answer.forwarded().headers().entries())
                .as("caller identity is never trusted")
                .noneMatch(
                        h -> h.name().toLowerCase(java.util.Locale.ROOT).startsWith("x-webauth-"));
    }

    // py: test_browser_gets_only_the_peer_login
    @Test
    void theBrowserGetsOnlyThePeerLogin() throws Exception {
        final var answer = request("web", "GET", "/", "", "X-WEBAUTH-USER", "intruder");
        assertThat(answer.forwarded().headers().all("X-WEBAUTH-USER"))
                .as("only the admitted socket peer signs in")
                .containsExactly("person");
        assertThat(answer.forwarded().headers().first("Host"))
                .as("the upstream receives the private forge host")
                .isEqualTo("localhost");
    }

    // py: test_git_forwards_credentials_and_body_but_no_cookie
    @ParameterizedTest
    @ValueSource(strings = {"Basic cGVyc29uOmZpeHR1cmU=", "token fixture"})
    void gitForwardsCredentialsAndBodyButNoCookie(String credential) throws Exception {
        final var answer =
                request(
                        "git",
                        "POST",
                        "/owner/repo.git/git-receive-pack",
                        "git body",
                        "Cookie",
                        "session=fixture",
                        "Authorization",
                        credential);
        assertThat(answer.body())
                .as("the pack bytes reach the forge")
                .isEqualTo("git body".getBytes(StandardCharsets.US_ASCII));
        assertThat(answer.forwarded().headers().first("Authorization"))
                .as("the token remains the caller's")
                .isEqualTo(credential);
        assertThat(answer.forwarded().headers().all("Cookie"))
                .as("a browser session is no Git authentication")
                .isEmpty();
    }

    // py: test_git_accepts_chunked_push_without_buffering_the_pack
    @Test
    void gitAcceptsAChunkedPushWithoutBufferingThePack() throws Exception {
        final var answer =
                request(
                        "git",
                        "POST",
                        "/",
                        "1\r\ng\r\n2\r\nit\r\n0\r\n\r\n",
                        "Authorization",
                        "token fixture",
                        "Transfer-Encoding",
                        "chunked");
        assertThat(answer.body())
                .as("chunk boundaries do not alter pack bytes")
                .isEqualTo("git".getBytes(StandardCharsets.US_ASCII));
        assertThat(answer.forwarded().headers().first("Transfer-Encoding"))
                .as("the pack stays streamed")
                .isEqualTo("chunked");
    }

    // py: test_incomplete_or_ambiguous_chunked_requests_fail_closed
    @ParameterizedTest
    @ValueSource(
            strings = {"3\r\ngitXX0\r\n\r\n", "3\r\ngit\r\n0\r\nTrailer: no\r\n\r\n", "3\r\ngi"})
    void incompleteOrAmbiguousChunkedRequestsFailClosed(String body) throws Exception {
        assertThat(
                        request(
                                        "git",
                                        "POST",
                                        "/",
                                        body,
                                        "Authorization",
                                        "token fixture",
                                        "Transfer-Encoding",
                                        "chunked")
                                .response())
                .as("a broken streamed upload is a failed upstream exchange")
                .startsWith("HTTP/1.1 502");
    }

    // py: test_runner_forwards_job_tokens_as_git_does
    @Test
    void theRunnerForwardsJobTokensAsGitDoes() throws Exception {
        final var answer =
                request(
                        "runner",
                        "GET",
                        "/owner/repo.git/info/refs",
                        "",
                        "Authorization",
                        "token t");
        assertThat(answer.forwarded().headers().first("Authorization"))
                .as("checkout uses the job's token")
                .isEqualTo("token t");
    }

    @Test
    void theRunnerForwardsBearerTokensAndProtocolHeadersButNoCookie() throws Exception {
        final var answer =
                request(
                        "runner",
                        "POST",
                        "/api/actions_pipeline/_apis/pipelines/workflows/1/artifacts",
                        "artifact",
                        "Authorization",
                        "Bearer job.fixture-token",
                        "x-runner-uuid",
                        "fixture",
                        "Cookie",
                        "s=fixture");
        assertThat(answer.forwarded().headers().first("Authorization"))
                .as("artifact authentication reaches Forgejo")
                .isEqualTo("Bearer job.fixture-token");
        assertThat(answer.forwarded().headers().first("x-runner-uuid"))
                .as("runner metadata survives")
                .isEqualTo("fixture");
        assertThat(answer.forwarded().headers().all("Cookie"))
                .as("runner jobs cannot carry browser sessions")
                .isEmpty();
    }

    // py: test_git_on_loopback_tcp_challenges_without_reading_a_peer
    @Test
    void gitOnLoopbackTcpChallengesWithoutReadingAPeer() throws Exception {
        try (var listener = ServerSocketChannel.open()) {
            listener.bind(new InetSocketAddress("127.0.0.1", 0));
            try (var client = SocketChannel.open(listener.getLocalAddress());
                    var accepted = listener.accept()) {
                HostSocketActivation.checkFamily(accepted, "git");
                final var gate =
                        new ForgeGate(
                                "git",
                                () -> {
                                    throw new AssertionError("TCP has no peer uid");
                                },
                                SetHolder::hosts,
                                () -> {
                                    throw new AssertionError("no authenticated upstream");
                                });
                final FutureTask<Void> served =
                        new FutureTask<>(
                                () -> {
                                    try (var connection = new GateConnection(accepted)) {
                                        gate.serve(connection.input, connection.output);
                                    }
                                    return null;
                                });
                Thread.ofPlatform().daemon().start(served);
                try (var connection = new GateConnection(client)) {
                    GateHttp.write(
                            connection.output,
                            "GET /porelo/stand-in.git/info/refs HTTP/1.1\r\nHost: x\r\n\r\n");
                    final var response = GateHttp.response(connection.input);
                    assertThat(response.status())
                            .as("loopback Git challenges without NSS")
                            .isEqualTo(401);
                    assertThat(response.headers().first("WWW-Authenticate"))
                            .as("Git retries with Basic")
                            .isEqualTo("Basic");
                }
                served.get(5, TimeUnit.SECONDS);
            }
        }
    }

    private static final class SetHolder {
        static java.util.Set<String> hosts() {
            return ForgeGatePolicyTest.HOSTS;
        }
    }

    // py: test_peer_checked_modes_refuse_a_tcp_connection
    @ParameterizedTest
    @ValueSource(strings = {"web", "runner"})
    void peerCheckedModesRefuseATcpConnection(String mode) throws Exception {
        try (var listener = ServerSocketChannel.open()) {
            listener.bind(new InetSocketAddress("127.0.0.1", 0));
            try (var client = SocketChannel.open(listener.getLocalAddress());
                    var accepted = listener.accept()) {
                assertThatThrownBy(() -> HostSocketActivation.checkFamily(accepted, mode))
                        .as("peer checked gates require Unix credentials")
                        .isInstanceOf(HostRefusal.class)
                        .hasMessageContaining(mode + " mode serves a Unix socket alone");
            }
        }
    }

    @Test
    void aHeaderNamedByTheSecondConnectionLineIsNotForwarded() throws Exception {
        final var answer =
                request(
                        "git",
                        "GET",
                        "/",
                        "",
                        "Authorization",
                        "token fixture",
                        "Connection",
                        "x-extra",
                        "X-Extra",
                        "private");
        assertThat(answer.forwarded().headers().all("X-Extra")).isEmpty();
    }

    @Test
    void theWebGateKeepsTwoFramedResponsesOnOneAcceptedConnection() throws Exception {
        final String request = "GET / HTTP/1.1\r\nHost: host.localhost:3000\r\n\r\n";
        final var answer = exercise("web", request + request, 2);
        assertThat(logins.get()).as("admission is resolved once per accepted connection").isOne();
        assertThat(answer.response())
                .as("the next request is served through the same activation")
                .contains("Connection: keep-alive\r\n")
                .containsSubsequence("\r\n\r\nok", "HTTP/1.1 200", "\r\n\r\nok");
        assertThat(answer.response().split("HTTP/1.1 200", -1))
                .as("both requests receive their own complete response")
                .hasSize(3);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n2\r\nok\r\n0\r\n\r\n",
                "HTTP/1.1 200 OK\r\nConnection: close\r\n\r\nok"
            })
    void streamedWebResponsesKeepTheirFramingAcrossRequests(String response) throws Exception {
        final String request = "GET / HTTP/1.1\r\nHost: host.localhost:3000\r\n\r\n";
        final var answer = exercise("web", request + request, 2, response);
        final var input =
                new ByteArrayInputStream(answer.response().getBytes(StandardCharsets.ISO_8859_1));
        for (int count = 0; count < 2; count++) {
            final var received = GateHttp.response(input);
            assertThat(received.headers().first("Transfer-Encoding"))
                    .as("streaming responses have explicit HTTP/1.1 framing")
                    .isEqualTo("chunked");
            assertThat(received.headers().first("Connection"))
                    .as("the next page can reuse the accepted connection")
                    .isEqualTo("keep-alive");
            final var body = new ByteArrayOutputStream();
            GateHttp.chunks(input, body, false, true);
            assertThat(body.toString(StandardCharsets.US_ASCII))
                    .as("one response never absorbs the next")
                    .isEqualTo("ok");
        }
        assertThat(input.read()).as("both framed responses end cleanly").isEqualTo(-1);
    }

    @Test
    void aRefusedExpectContinueNeverAsksForAnUpload() throws Exception {
        final var bytes = new ByteArrayOutputStream();
        ForgeGatePolicyTest.gate("git")
                .serve(
                        new ByteArrayInputStream(
                                "POST / HTTP/1.1\r\nExpect: 100-continue\r\nContent-Length: 123\r\n\r\n"
                                        .getBytes(StandardCharsets.US_ASCII)),
                        bytes);
        assertThat(bytes.toString(StandardCharsets.US_ASCII))
                .as("authentication precedes an upload")
                .startsWith("HTTP/1.1 401")
                .doesNotContain("100 Continue");
    }

    @Test
    void socketActivationMustBelongToThisProcessAndCarryOneConnection() {
        for (var environment :
                List.of(
                        Map.<String, String>of(),
                        Map.of("LISTEN_FDS", "2", "LISTEN_PID", "123"),
                        Map.of("LISTEN_FDS", "1", "LISTEN_PID", "122")))
            assertThatThrownBy(() -> HostSocketActivation.check(environment, 123))
                    .as("a caller cannot invent socket activation")
                    .isInstanceOf(HostRefusal.class);
        HostSocketActivation.check(Map.of("LISTEN_FDS", "1", "LISTEN_PID", "123"), 123);
    }
}
