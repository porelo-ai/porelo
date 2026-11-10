package org.fuin.porelo.supervisor;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.fuin.porelo.core.UsageSums;
import org.fuin.porelo.wire.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class UsageResponseTest {
    // Synthetic Messages events follow the provider's streaming documentation, with no live body.
    private static final String ANSWER =
            """
            event: message_start
            data: {"type":"message_start","message":{"model":"claude-opus-5-5","content":[],"usage":{"input_tokens":70,"cache_read_input_tokens":30,"cache_creation_input_tokens":10,"output_tokens":1}}}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":20}}

            event: message_stop
            data: {"type":"message_stop"}

            """;

    private Path record(Path dir, String answer, int chunk, int status) {
        final Path file = dir.resolve("usage.jsonl");
        final var observation =
                new UsageObservation(
                        new UsageReading(
                                "/v1/messages?beta=true",
                                "{\"model\":\"requested-model\",\"stream\":true}"
                                        .getBytes(StandardCharsets.UTF_8),
                                false,
                                true),
                        new VaultProxy.Usage(file, "test-worker", "anthropic", "test-entry", true),
                        "anthropic",
                        "test-entry",
                        "",
                        "");
        // The broker supplies false for an application/json response, even to a streamed request.
        observation.start(false, status);
        final byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
        for (int at = 0; at < bytes.length; ) {
            final int length = Math.min(at == 0 ? chunk : 8192, bytes.length - at);
            final byte[] part = java.util.Arrays.copyOfRange(bytes, at, at + length);
            observation.accept(part, part.length);
            at += length;
        }
        observation.finish(true);
        observation.awaitUntil(System.nanoTime() + TimeUnit.SECONDS.toNanos(5));
        return file;
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 7, 8192})
    void aMessagesStreamLabeledJsonKeepsItsModelAndBillableTokens(int chunk, @TempDir Path dir)
            throws Exception {
        // Lost response framing must not erase the model and durable billing counts.
        final Path file = record(dir, ANSWER, chunk, 200);
        final var lines = Files.readAllLines(file);
        assertThat(lines).hasSize(1);
        final var line = (Map<?, ?>) Json.parseDecimals(lines.getFirst());
        assertThat(line.get("model_asked")).isEqualTo("requested-model");
        assertThat(line.get("model_answered")).isEqualTo("claude-opus-5-5");
        assertThat(line.get("end")).isEqualTo("complete");
        for (final var count :
                Map.of("input", 70L, "cache_read", 30L, "cache_write", 10L, "output", 20L)
                        .entrySet())
            assertThat(line.get(count.getKey()))
                    .isEqualTo(java.math.BigDecimal.valueOf(count.getValue()));
        assertThat(line.get("reasoning")).isNull();
        final var usage = UsageSums.read(file, ZoneId.of("UTC"));
        assertThat(usage.state()).isEqualTo("available");
        assertThat(usage.models())
                .extracting(UsageSums.Group::name)
                .containsExactly("claude-opus-5-5");
        assertThat(usage.models().getFirst().totals())
                .isEqualTo(
                        new UsageSums.Totals(
                                1,
                                java.util.List.of(
                                        new UsageSums.Count(70L, 0),
                                        new UsageSums.Count(30L, 0),
                                        new UsageSums.Count(10L, 0),
                                        new UsageSums.Count(20L, 0),
                                        new UsageSums.Count(null, 1))));
        assertThat(usage.totals()).isEqualTo(usage.models().getFirst().totals());
    }

    @Test
    void aStreamedErrorLabeledJsonDoesNotBecomeACompletedAnswer(@TempDir Path dir)
            throws Exception {
        // A lost error event must not certify a failed generation as complete.
        final Path file =
                record(
                        dir,
                        "event: error\ndata: {\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"PRIVATE_ERROR\"}}\n\n",
                        1,
                        200);
        final String json = Files.readString(file);
        assertThat(json).doesNotContain("PRIVATE_ERROR");
        final var line = (Map<?, ?>) Json.parseDecimals(json);
        assertThat(line.get("end")).isEqualTo("error");
        assertThat(line.get("model_answered")).isNull();
        final var usage = UsageSums.read(file, ZoneId.of("UTC"));
        assertThat(usage.totals().requests()).isEqualTo(1);
        assertThat(usage.models()).extracting(UsageSums.Group::name).containsExactly("unknown");
        assertThat(usage.totals().fields()).containsOnly(new UsageSums.Count(null, 1));
    }

    @Test
    void aRejectedMessageDoesNotInventAnAnsweringModelOrFreeTokens(@TempDir Path dir)
            throws Exception {
        // An authentication failure must not masquerade as a model answer or a zero-cost success.
        final Path file =
                record(
                        dir,
                        "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"PRIVATE_ERROR\"},\"request_id\":\"synthetic-request\"}",
                        7,
                        401);
        final String json = Files.readString(file);
        assertThat(json).doesNotContain("PRIVATE_ERROR", "synthetic-request");
        final var line = (Map<?, ?>) Json.parseDecimals(json);
        assertThat(line.get("end")).isEqualTo("error");
        assertThat(line.get("model_answered")).isNull();
        final var usage = UsageSums.read(file, ZoneId.of("UTC"));
        assertThat(usage.totals().requests()).isEqualTo(1);
        assertThat(usage.models()).extracting(UsageSums.Group::name).containsExactly("unknown");
        assertThat(usage.totals().fields()).containsOnly(new UsageSums.Count(null, 1));
    }
}
