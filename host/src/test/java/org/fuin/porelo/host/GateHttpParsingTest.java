package org.fuin.porelo.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GateHttpParsingTest {
    @ParameterizedTest
    @ValueSource(
            strings = {
                "Host: host.localhost:3000 \r\n",
                "Host: host.localhost:3000\r\nContent-Length: 0 \r\n"
            })
    void trailingWhitespaceDoesNotRepairARefusedHeader(String headers) throws Exception {
        final var parsed =
                GateHttp.request(
                        new ByteArrayInputStream(
                                ("GET / HTTP/1.1\r\n" + headers + "\r\n")
                                        .getBytes(StandardCharsets.US_ASCII)));
        final var refused = ForgeGatePolicyTest.gate("web").refusal(parsed, "person");
        assertThat(refused)
                .as(
                        "a Host or length with trailing whitespace is refused, never trimmed into admission")
                .isNotNull();
        assertThat(refused.status()).as("Host is forbidden, framing is malformed").isIn(400, 403);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1\nx\r\n0\r\n\r\n", "1\r\nx\r\n0\n\r\n"})
    void requestChunksRequireCrLfOnTheirSizeLines(String raw) {
        assertThatThrownBy(
                        () ->
                                GateHttp.chunks(
                                        new ByteArrayInputStream(
                                                raw.getBytes(StandardCharsets.US_ASCII)),
                                        new ByteArrayOutputStream(),
                                        true,
                                        false))
                .as("a chunk size line ended by a bare LF is refused, never normalized")
                .isInstanceOf(IOException.class);
    }
}
