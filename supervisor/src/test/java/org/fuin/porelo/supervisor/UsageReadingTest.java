package org.fuin.porelo.supervisor;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Prevents unbounded observation and false certainty after a broken usage event. All inline answer
 * fragments are written from provider documentation, with synthetic values; no live answer is
 * recorded yet.
 */
class UsageReadingTest {
    private UsageReading reading(String path, boolean stream) {
        final UsageReading reading =
                new UsageReading(path, "{}".getBytes(StandardCharsets.UTF_8), false, true);
        reading.start(stream, 200);
        return reading;
    }

    private void feed(UsageReading reading, String value) {
        final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        for (int at = 0; at < bytes.length; at += 7) {
            final byte[] part =
                    java.util.Arrays.copyOfRange(bytes, at, Math.min(at + 7, bytes.length));
            reading.accept(part, part.length);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void anOversizedBodyOrEventHasUnknownCounts(boolean stream) {
        final UsageReading reading = reading("/responses", stream);
        feed(
                reading,
                (stream ? "data: " : "")
                        + "{\"text\":\""
                        + "x".repeat(1024 * 1024)
                        + "\",\"usage\":{\"input_tokens\":999}}"
                        + (stream ? "\n\n" : ""));
        assertThat(reading.finish(null).get("input"))
                .as("a body or event over 1 MiB is never parsed")
                .isNull();
        if (!stream)
            assertThat(reading.finish(null).get("end"))
                    .as("a fully relayed body is complete even when usage is unreadable")
                    .isEqualTo("complete");
    }

    @Test
    void aMalformedUsageEventDoesNotLeaveEarlierCountsLookingComplete() {
        final UsageReading reading = reading("/responses", true);
        feed(reading, "data: {\"usage\":{\"input_tokens\":9}}\n\ndata: {invalid}\n\n");
        assertThat(reading.finish(null).get("input"))
                .as("a failed parse records unknown counts")
                .isNull();
    }

    @Test
    void repeatedCumulativeCountsAreReplacedAndNullDoesNotEraseThem() {
        final UsageReading reading = reading("/models/m:streamGenerateContent", true);
        for (int i = 0; i < 2000; i++) {
            feed(
                    reading,
                    "data: {\"usageMetadata\":{\"promptTokenCount\":100,\"cachedContentTokenCount\":30,\"candidatesTokenCount\":15,\"thoughtsTokenCount\":5}}\r\n\r\n");
        }
        feed(
                reading,
                "data: {\"candidates\":[{\"finishReason\":\"STOP\"}],\"usageMetadata\":{\"promptTokenCount\":null}}\n\n");
        final var line = reading.finish(null);
        assertThat(line.get("input")).as("cumulative chunks are never added").isEqualTo(70L);
        assertThat(line.get("output")).as("reasoning belongs in output").isEqualTo(20L);
        assertThat(line.get("cache_write")).as("an unreported field stays null").isNull();
    }

    @Test
    void anErrorEventAndANonSuccessStatusAreErrors() {
        final UsageReading event = reading("/responses", true);
        feed(event, "event: error\ndata: {\"type\":\"error\",\"message\":\"PRIVATE_ERROR\"}\n\n");
        assertThat(event.finish(null).get("end"))
                .as("provider errors are named without their text")
                .isEqualTo("error");
        final UsageReading status = reading("/responses", false);
        status.start(false, 429);
        feed(status, "{\"error\":{\"message\":\"PRIVATE_ERROR\"}}");
        assertThat(status.finish(null).get("end"))
                .as("HTTP failures never read as complete")
                .isEqualTo("error");
        assertThat(status.finish(null).toString())
                .as("provider error text is discarded")
                .doesNotContain("PRIVATE_ERROR");
    }

    @Test
    void anAgentDisconnectKeepsTheCountsAlreadyObserved() {
        final UsageReading reading = reading("/v1/messages", true);
        feed(
                reading,
                "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":7}}}\n\n");
        final var line = reading.finish("agent");
        assertThat(line.get("end")).as("an agent disconnect is aborted").isEqualTo("aborted");
        assertThat(line.get("closed_by"))
                .as("the caller identifies a failed write")
                .isEqualTo("agent");
        assertThat(line.get("input")).as("partial input is already billable").isEqualTo(7L);
    }

    @Test
    void twoCallsOfTheSameToolAreCountedAsTwoCalls() {
        final UsageReading reading = reading("/chat/completions", false);
        // Written from the Chat Completions documentation; this is not a live answer.
        feed(
                reading,
                """
                {"choices":[{"message":{"tool_calls":[{"function":{"name":"lookup","arguments":"PRIVATE_ONE"}},{"function":{"name":"lookup","arguments":"PRIVATE_TWO"}}]}}]}
                """);
        assertThat(reading.finish(null).get("tools"))
                .as("tool names do not collapse distinct calls")
                .isEqualTo(java.util.Map.of("lookup", 2L));
    }

    @Test
    void invalidNumbersAndTooManyToolCallsMakeUsageUnknown() {
        final UsageReading invalid = reading("/responses", false);
        feed(invalid, "{\"usage\":{\"input_tokens\":-1,\"output_tokens\":3}}");
        assertThat(invalid.finish(null).get("output"))
                .as("a bad usage member invalidates the reading")
                .isNull();
        final UsageReading tools = reading("/responses", true);
        for (int i = 0; i < 300; i++) {
            feed(
                    tools,
                    "data: {\"type\":\"response.output_item.added\",\"output_index\":"
                            + i
                            + ",\"item\":{\"type\":\"function_call\",\"name\":\"lookup\"}}\n\n");
        }
        feed(
                tools,
                "data: {\"type\":\"response.completed\",\"response\":{\"usage\":{\"input_tokens\":8}}}\n\n");
        assertThat(tools.finish(null).get("input"))
                .as("tool bookkeeping is bounded for an arbitrarily long stream")
                .isNull();
    }

    @Test
    void reportedCostAndLargeCountsKeepTheirExactValues() {
        final UsageReading reading = reading("/chat/completions", false);
        // Written from the documented usage shape; values stress decimal precision, not a live
        // answer.
        feed(
                reading,
                "{\"usage\":{\"prompt_tokens\":9007199254740993,\"cost\":0.123456789012345678901,\"cost_in_usd_ticks\":9007199254740993}}");
        final var line = reading.finish(null);
        assertThat(line.get("input"))
                .as("integer counts never pass through a double")
                .isEqualTo(9007199254740993L);
        assertThat(line.get("reported_cost"))
                .as("each cost remains a decimal string in its own unit")
                .isEqualTo(
                        java.util.Map.of(
                                "cost",
                                "0.123456789012345678901",
                                "cost_in_usd_ticks",
                                "9007199254740993"));
    }

    @Test
    void geminiCountsEachFunctionCallAcrossSeparateChunks() {
        final UsageReading reading = reading("/models/m:streamGenerateContent", true);
        // Written from the Gemini streaming documentation; these are separate synthetic chunks.
        feed(
                reading,
                "data: {\"candidates\":[{\"index\":0,\"content\":{\"parts\":[{\"functionCall\":{\"name\":\"lookup\",\"args\":{}}}]}}]}\n\n");
        feed(
                reading,
                "data: {\"candidates\":[{\"index\":0,\"content\":{\"parts\":[{\"functionCall\":{\"name\":\"lookup\",\"args\":{}}},{\"functionCall\":{\"name\":\"save\",\"args\":{}}}]},\"finishReason\":\"STOP\"}]}\n\n");
        assertThat(reading.finish(null).get("tools"))
                .as("chunk-local part indices cannot overwrite earlier calls")
                .isEqualTo(java.util.Map.of("lookup", 2L, "save", 1L));
    }

    @Test
    void onlyAPostToAGeneratingPathIsObserved() {
        // Reading a stored completion back generates nothing and bills nothing.
        assertThat(UsageReading.observes("POST", "/v1/chat/completions")).as("POST").isTrue();
        assertThat(UsageReading.observes("GET", "/v1/chat/completions")).as("GET").isFalse();
    }

    @Test
    void aCodeAssistAnswerIsReadThroughItsWrapper() {
        final UsageReading reading = reading("/v1internal:streamGenerateContent?alt=sse", true);

        feed(
                reading,
                "data: {\"response\":{\"modelVersion\":\"m\",\"candidates\":[{\"finishReason\":"
                        + "\"STOP\"}],\"usageMetadata\":{\"promptTokenCount\":9,"
                        + "\"candidatesTokenCount\":2}},\"traceId\":\"t\"}\n\n");

        final var line = reading.finish(null);
        assertThat(line.get("input")).as("input").isEqualTo(9L);
        assertThat(line.get("end")).as("end").isEqualTo("complete");
    }

    @Test
    void aCountInAContentChunkDoesNotMakeACutStreamComplete() {
        // Some providers repeat the count: a cut answer called complete would hide that it was cut.
        final UsageReading reading = reading("/v1/chat/completions", true);

        feed(
                reading,
                "data: {\"choices\":[{\"index\":0,\"delta\":{}}],\"usage\":{\"prompt_tokens\":9}}\n\n");

        assertThat(reading.finish(null).get("end")).isEqualTo("aborted");
    }

    @Test
    void aCountInTheLastChoiceCompletesAStreamWithoutAClosingEvent() {
        final UsageReading reading = reading("/v1/chat/completions", true);

        feed(
                reading,
                "data: {\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"delta\":{}}],"
                        + "\"usage\":{\"prompt_tokens\":9}}\n\n");

        assertThat(reading.finish(null).get("end")).isEqualTo("complete");
    }

    @Test
    void anAnthropicOutputReportedOnlyAtTheStartIsUnknown() {
        // The opening count is a placeholder, not what was generated.
        final UsageReading reading = reading("/v1/messages", true);

        feed(
                reading,
                "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"usage\":"
                        + "{\"input_tokens\":7,\"output_tokens\":1}}}\n\n");

        final var line = reading.finish(null);
        assertThat(line.get("input")).as("input").isEqualTo(7L);
        assertThat(line.get("output")).as("output").isNull();
    }

    @Test
    void aGeminiAnswerOfOnlyThoughtsCountsThemAsOutput() {
        // Output is with the reasoning: a count of thoughts alone must not leave it unknown.
        final UsageReading reading = reading("/v1beta/models/m:generateContent", false);

        feed(reading, "{\"usageMetadata\":{\"thoughtsTokenCount\":5}}");

        final var line = reading.finish(null);
        assertThat(line.get("reasoning")).as("reasoning").isEqualTo(5L);
        assertThat(line.get("output")).as("output").isEqualTo(5L);
    }
}
