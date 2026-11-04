package org.fuin.porelo.supervisor;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Keeps the usage rewrite confined to streamed Chat Completions. */
class UsageRequestTest {
    @ParameterizedTest
    @ValueSource(
            strings = {
                "/responses",
                "/v1/messages",
                "/models/m:streamGenerateContent",
                "/chat/completions/count_tokens"
            })
    void anotherFormatKeepsItsRequestBytes(String path) {
        final byte[] original = "{ \"stream\": true }".getBytes(StandardCharsets.UTF_8);
        final var request = UsageRequest.prepare("POST", path, original, null, true);
        assertThat(request.body()).as("another endpoint is never rewritten").isEqualTo(original);
        assertThat(request.asked()).as("only Chat Completions asks for include_usage").isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{ \"stream\": false }", "not JSON", "{\"stream\":true,broken}", "[]"})
    void aBodyThatIsNotAStreamedJsonObjectStaysUntouched(String body) {
        final byte[] original = body.getBytes(StandardCharsets.UTF_8);
        assertThat(UsageRequest.prepare("POST", "/chat/completions", original, null, true).body())
                .as("a failed parse or an ordinary completion keeps all bytes")
                .isEqualTo(original);
    }

    @Test
    void aCompressedStreamedChatRequestIsSentAsPlainJson() throws Exception {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (var zip = new java.util.zip.GZIPOutputStream(bytes)) {
            zip.write(
                    "{\"stream\":true,\"stream_options\":{\"include_usage\":false,\"other\":true}}"
                            .getBytes(StandardCharsets.UTF_8));
        }
        final var request =
                UsageRequest.prepare(
                        "POST", "/chat/completions", bytes.toByteArray(), "gzip", true);
        assertThat(request.rewritten())
                .as("the relay must drop Content-Encoding and recalculate length")
                .isTrue();
        assertThat(new String(request.body(), StandardCharsets.UTF_8))
                .as("other stream options survive the rewrite")
                .contains("\"include_usage\":true", "\"other\":true");
    }

    @Test
    void requestingUsagePreservesExactNumbersElsewhereInTheRequest() {
        final byte[] original =
                "{\"stream\":true,\"seed\":9007199254740993,\"tools\":[{\"const\":0.123456789012345678901}]}"
                        .getBytes(StandardCharsets.UTF_8);
        final var changed = UsageRequest.prepare("POST", "/chat/completions", original, null, true);
        assertThat(new String(changed.body(), StandardCharsets.UTF_8))
                .as("requesting usage cannot change a seed or tool schema")
                .contains("9007199254740993", "0.123456789012345678901");
    }

    @Test
    void aStreamedChatRequestOverAMebibyteIsStillAskedForItsCount() {
        // Long contexts pass a mebibyte in ordinary work, and left to the agent they would go
        // uncounted.
        final byte[] body =
                ("{\"stream\":true,\"stream_options\":{\"include_usage\":false},\"messages\":\""
                                + "x".repeat(2 * 1024 * 1024)
                                + "\"}")
                        .getBytes(StandardCharsets.UTF_8);

        final var request = UsageRequest.prepare("POST", "/chat/completions", body, null, true);

        assertThat(request.asked()).as("asked").isTrue();
        assertThat(new String(request.body(), StandardCharsets.UTF_8))
                .as("what goes to the provider")
                .contains("\"include_usage\":true");
    }

    @Test
    void anEncodingListTheBrokerAcceptsIsAskedToo() throws Exception {
        // The broker reads "identity, gzip" as gzip, so the count must not be lost to its spelling.
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (var zip = new java.util.zip.GZIPOutputStream(bytes)) {
            zip.write("{\"stream\":true}".getBytes(StandardCharsets.UTF_8));
        }

        final var request =
                UsageRequest.prepare(
                        "POST", "/chat/completions", bytes.toByteArray(), "identity, gzip", true);

        assertThat(request.asked()).isTrue();
    }
}
