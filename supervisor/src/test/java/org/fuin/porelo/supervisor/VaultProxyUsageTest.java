package org.fuin.porelo.supervisor;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.fuin.porelo.wire.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Prevents lost billing counts, private records and changed provider answers. */
class VaultProxyUsageTest {
    private static final String REAL = "real-private-credential";
    private static final String PHANTOM = "phantom-private-token";

    private static byte[] fixture(String name) throws IOException {
        try (var in = VaultProxyUsageTest.class.getResourceAsStream("/usage/" + name)) {
            final byte[] bytes = java.util.Objects.requireNonNull(in, name).readAllBytes();
            if (name.endsWith(".json")) {
                final Map<?, ?> fixture =
                        (Map<?, ?>) Json.parse(new String(bytes, StandardCharsets.UTF_8));
                return ((String) fixture.get("answer")).getBytes(StandardCharsets.UTF_8);
            }
            return bytes;
        }
    }

    private record Replay(byte[] answer, byte[] request, Map<?, ?> record, String log) {}

    private Replay replay(
            Path dir,
            String path,
            String request,
            byte[] answer,
            boolean stream,
            boolean streamUsage,
            int status)
            throws Exception {
        final var received = new AtomicReference<byte[]>();
        final List<String> logs = new CopyOnWriteArrayList<>();
        final Path record = dir.resolve("usage.jsonl");
        final Path socket = dir.resolve("broker/vault.sock");
        final HttpServer provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        provider.createContext(
                "/",
                exchange -> {
                    final byte[] arrived = exchange.getRequestBody().readAllBytes();
                    received.set(arrived);
                    byte[] payload = answer;
                    if (path.split("\\?", 2)[0].endsWith("/chat/completions") && stream) {
                        boolean include = false;
                        try {
                            if (Json.parse(new String(arrived, StandardCharsets.UTF_8))
                                            instanceof Map<?, ?> root
                                    && root.get("stream_options") instanceof Map<?, ?> options) {
                                include = Boolean.TRUE.equals(options.get("include_usage"));
                            }
                        } catch (RuntimeException ex) {
                        }
                        if (!include) {
                            final StringBuilder withoutUsage = new StringBuilder();
                            for (final String event :
                                    new String(answer, StandardCharsets.UTF_8).split("\n\n")) {
                                if (!event.contains("\"choices\":[]"))
                                    withoutUsage.append(event).append("\n\n");
                            }
                            payload = withoutUsage.toString().getBytes(StandardCharsets.UTF_8);
                        }
                    }
                    exchange.getResponseHeaders()
                            .set("Content-Type", stream ? "text/event-stream" : "application/json");
                    exchange.getResponseHeaders().set("X-Private", "HEADER_PRIVATE");
                    exchange.sendResponseHeaders(status, payload.length);
                    try (var out = exchange.getResponseBody()) {
                        out.write(payload);
                    }
                });
        provider.start();
        try {
            final byte[] decoded;
            try (VaultProxy proxy =
                    new VaultProxy(
                            socket,
                            "http://127.0.0.1:" + provider.getAddress().getPort(),
                            token ->
                                    PHANTOM.equals(token)
                                            ? new TokenExchange.Granted(REAL)
                                            : new TokenExchange.Rejected(),
                            "Authorization",
                            "Bearer ",
                            null,
                            Map.of(),
                            Map.of(),
                            logs::add,
                            dir.resolve("no-authorities"),
                            new VaultProxy.Usage(
                                    record,
                                    "test-worker",
                                    "test-provider",
                                    "test-key",
                                    streamUsage))) {
                Thread.ofVirtual().start(proxy);
                final byte[] body = request.getBytes(StandardCharsets.UTF_8);
                final byte[] reply;
                try (SocketChannel client =
                        SocketChannel.open(UnixDomainSocketAddress.of(socket))) {
                    final var out = Channels.newOutputStream(client);
                    out.write(
                            ("POST "
                                            + path
                                            + " HTTP/1.1\r\nAuthorization: Bearer "
                                            + PHANTOM
                                            + "\r\nX-Private: HEADER_PRIVATE\r\nContent-Length: "
                                            + body.length
                                            + "\r\nContent-Type: application/json\r\n\r\n")
                                    .getBytes(StandardCharsets.UTF_8));
                    out.write(body);
                    out.flush();
                    reply = Channels.newInputStream(client).readAllBytes();
                }
                final String raw = new String(reply, StandardCharsets.UTF_8);
                final int start = raw.indexOf("\r\n\r\n") + 4;
                final byte[] after = java.util.Arrays.copyOfRange(reply, start, reply.length);
                decoded =
                        raw.substring(0, start).contains("Transfer-Encoding: chunked")
                                ? HttpHead.readChunked(
                                        new ByteArrayInputStream(after), answer.length + 1)
                                : after;
            }
            if (UsageReading.format(path) != null) {
                assertThat(Files.exists(record))
                        .as("broker shutdown drains every completed generation record")
                        .isTrue();
                final String line = Files.readString(record);
                assertThat(line.lines().count()).as("one request writes one record").isEqualTo(1);
                assertThat(line + String.join("\n", logs))
                        .as("private text and credentials never enter records or logs")
                        .doesNotContain(
                                "PROMPT_PRIVATE",
                                "ANSWER_PRIVATE",
                                "TOOL_PRIVATE",
                                "HEADER_PRIVATE",
                                REAL,
                                PHANTOM);
                return new Replay(
                        decoded,
                        received.get(),
                        (Map<?, ?>) Json.parse(line),
                        String.join("\n", logs));
            }
            assertThat(Files.exists(record))
                    .as("non-generation paths create no usage record")
                    .isFalse();
            return new Replay(decoded, received.get(), Map.of(), String.join("\n", logs));
        } finally {
            provider.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "chat.json",
                "chat.sse",
                "responses.json",
                "responses.sse",
                "messages.json",
                "messages.sse",
                "gemini.json",
                "gemini.sse"
            })
    void recordsDocumentedCountsWithoutChangingAnAnswer(String name, @TempDir Path dir)
            throws Exception {
        final boolean stream = name.endsWith("sse");
        final String path =
                name.startsWith("chat")
                        ? "/v1/chat/completions"
                        : name.startsWith("responses")
                                ? "/responses"
                                : name.startsWith("messages")
                                        ? "/v1/messages"
                                        : stream
                                                ? "/v1beta/models/asked-model:streamGenerateContent?alt=sse"
                                                : "/v1beta/models/asked-model:generateContent";
        final byte[] answer = fixture(name);
        final Replay seen =
                replay(
                        dir,
                        path,
                        "{\"model\":\"asked-model\",\"stream\":"
                                + stream
                                + ",\"messages\":[{\"content\":\"PROMPT_PRIVATE\"}],\"stream_options\":{\"include_usage\":false}}",
                        answer,
                        stream,
                        true,
                        200);
        assertThat(seen.answer())
                .as("the provider's answer reaches the agent byte for byte")
                .isEqualTo(answer);
        final Map<?, ?> line = seen.record();
        assertThat(line.keySet())
                .as("records contain only the documented metadata fields")
                .isEqualTo(
                        java.util.Set.of(
                                "time",
                                "agent",
                                "provider",
                                "credential",
                                "format",
                                "model_asked",
                                "model_answered",
                                "input",
                                "cache_read",
                                "cache_write",
                                "output",
                                "reasoning",
                                "asked",
                                "tools",
                                "reported_cost",
                                "finish_reason",
                                "end",
                                "closed_by",
                                "status"));
        assertThat(java.time.Instant.parse((String) line.get("time")))
                .as("record time is a UTC instant")
                .isNotNull();
        assertThat(line.get("status"))
                .as("the provider's HTTP status is retained")
                .isEqualTo(200.0);
        assertThat(line.get("closed_by")).as("a complete response was not cut off").isNull();
        assertThat(line.get("reported_cost"))
                .as("reported cost keeps the provider's unit and decimal value")
                .isEqualTo(name.startsWith("chat") ? Map.of("cost", "0.0123") : Map.of());
        assertThat(line.get("input")).as("cache is excluded from input").isEqualTo(70.0);
        assertThat(line.get("cache_read")).as("cached input is counted separately").isEqualTo(30.0);
        assertThat(line.get("output")).as("output includes reasoning").isEqualTo(20.0);
        assertThat(line.get("cache_write"))
                .as("an unreported count stays null")
                .isEqualTo(name.startsWith("messages") ? 10.0 : null);
        assertThat(line.get("reasoning"))
                .as("only documented reasoning is counted")
                .isEqualTo(name.startsWith("messages") ? null : 5.0);
        assertThat(line.get("model_asked"))
                .as("the request identifies its model")
                .isEqualTo("asked-model");
        assertThat(line.get("model_answered"))
                .as("the provider identifies its model")
                .isEqualTo("answered-model");
        assertThat(line.get("agent"))
                .as("the task's agent crosses the broker boundary")
                .isEqualTo("test-worker");
        assertThat(line.get("provider"))
                .as("the route identifies its provider")
                .isEqualTo("test-provider");
        assertThat(line.get("credential"))
                .as("a sum needs no credential join")
                .isEqualTo("test-key");
        assertThat(line.get("tools"))
                .as("tool arguments are discarded and fragmented names are joined")
                .isEqualTo(Map.of("lookup", 1.0));
        assertThat(line.get("end"))
                .as("the final event or full body arrived")
                .isEqualTo("complete");
        assertThat(line.get("asked"))
                .as("only a streamed Chat Completions request is changed")
                .isEqualTo(stream && name.startsWith("chat"));
        if (name.startsWith("chat") && stream) {
            assertThat(new String(seen.request(), StandardCharsets.UTF_8))
                    .as("the agent cannot turn usage off")
                    .contains("\"include_usage\":true");
        }
    }

    @Test
    void asksForUsageWhenTheAgentLeavesTheOptionOut(@TempDir Path dir) throws Exception {
        final Replay seen =
                replay(
                        dir,
                        "/chat/completions",
                        "{\"model\":\"asked-model\",\"stream\":true}",
                        fixture("chat.sse"),
                        true,
                        true,
                        200);
        assertThat(new String(seen.request(), StandardCharsets.UTF_8))
                .as("missing options still request the extra usage chunk")
                .contains("\"include_usage\":true");
        assertThat(seen.answer())
                .as("the extra usage chunk is relayed unchanged")
                .isEqualTo(fixture("chat.sse"));
    }

    @Test
    void keepsPartialCountsWhenTheProviderCutsAStreamOff(@TempDir Path dir) throws Exception {
        final byte[] answer = fixture("messages-aborted.sse");
        final Replay seen =
                replay(dir, "/v1/messages", "{\"stream\":true}", answer, true, true, 200);
        assertThat(seen.answer()).as("an unfinished event is still relayed").isEqualTo(answer);
        assertThat(seen.record().get("end")).as("no final event arrived").isEqualTo("aborted");
        assertThat(seen.record().get("closed_by"))
                .as("the provider ended the body")
                .isEqualTo("provider");
        assertThat(seen.record().get("input"))
                .as("message_start already reported input")
                .isEqualTo(70.0);
        assertThat(seen.record().get("output")).as("unknown output is never zero").isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/v1/messages/count_tokens", "/models/model:countTokens", "/models"})
    void doesNotRecordACountingOrListingCall(String path, @TempDir Path dir) throws Exception {
        // Written from the Messages count_tokens documentation; this is not a live answer.
        final byte[] answer = "{\"input_tokens\":70}".getBytes(StandardCharsets.UTF_8);
        assertThat(replay(dir, path, "{}", answer, false, true, 200).answer())
                .as("counting calls still relay their answer")
                .isEqualTo(answer);
    }

    @Test
    void aProviderCanDeclineStreamUsageWithoutChangingItsRequest(@TempDir Path dir)
            throws Exception {
        final String request = "{ \"stream\": true, \"model\": \"asked-model\" }";
        final Replay seen =
                replay(dir, "/chat/completions", request, fixture("chat.sse"), true, false, 200);
        assertThat(seen.request())
                .as("stream_usage false preserves the request")
                .isEqualTo(request.getBytes(StandardCharsets.UTF_8));
        assertThat(seen.record().get("input"))
                .as("a declined usage request is recorded as unknown")
                .isNull();
        assertThat(seen.record().get("asked"))
                .as("the broker asked for no extra chunk")
                .isEqualTo(false);
    }

    @Test
    void aNonJsonRequestPassesThroughUnchanged(@TempDir Path dir) throws Exception {
        final String request = "not JSON PROMPT_PRIVATE";
        final Replay seen =
                replay(dir, "/chat/completions", request, fixture("chat.json"), false, true, 200);
        assertThat(seen.request())
                .as("JSON rewriting never touches another body")
                .isEqualTo(request.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void aUsageParseFailureKeepsAllTheAnswerBytes(@TempDir Path dir) throws Exception {
        // Written from the Chat Completions usage shape with a deliberately invalid count, not a
        // live answer.
        final byte[] answer =
                "{\"model\":\"answered-model\",\"choices\":[{\"message\":{\"content\":\"ANSWER_PRIVATE\"}}],\"usage\":{\"prompt_tokens\":\"bad\",\"completion_tokens\":5}}"
                        .getBytes(StandardCharsets.UTF_8);
        final Replay seen = replay(dir, "/chat/completions", "{}", answer, false, true, 200);
        assertThat(seen.answer())
                .as("broken usage cannot withhold or alter a byte")
                .isEqualTo(answer);
        assertThat(seen.record().get("input")).as("a failed reading is unknown").isNull();
        assertThat(seen.record().get("output"))
                .as("a failed reading invalidates every count")
                .isNull();
    }

    @Test
    void anAnswerWithheldForItsCredentialIsNotRecordedAsComplete(@TempDir Path dir)
            throws Exception {
        // The agent got nothing: called complete, the record would say a request succeeded.
        final byte[] answer =
                "{\"access_token\":\"a\",\"refresh_token\":\"b\"}".getBytes(StandardCharsets.UTF_8);

        final Replay seen = replay(dir, "/v1/messages", "{}", answer, false, true, 200);

        assertThat(new String(seen.answer(), StandardCharsets.UTF_8))
                .as("what the agent got")
                .doesNotContain("access_token");
        assertThat(seen.record().get("end")).as("end").isEqualTo("error");
    }
}
