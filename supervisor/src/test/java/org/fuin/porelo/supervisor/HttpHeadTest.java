package org.fuin.porelo.supervisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Tests how much of a request head is read before it is refused. */
class HttpHeadTest {

    @Test
    void aHeadOfManyShortLinesIsRefusedPastItsWholeLimit() {
        // Each line is short; together they are past 64 KiB, and no blank line ends them.
        final String head = "GET /corp/a.jar HTTP/1.1\r\n" + "X: a\r\n".repeat(20_000);

        assertThatThrownBy(
                        () ->
                                HttpHead.read(
                                        new ByteArrayInputStream(
                                                head.getBytes(StandardCharsets.US_ASCII))))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("exceeds");
    }

    @Test
    void anOrdinaryHeadIsRead() throws IOException {
        final HttpHead head =
                HttpHead.read(
                        new ByteArrayInputStream(
                                "GET /corp/a.jar HTTP/1.1\r\nAccept: */*\r\n\r\n"
                                        .getBytes(StandardCharsets.US_ASCII)));

        assertThat(head).isNotNull();
        assertThat(head.value("accept")).isEqualTo("*/*");
    }
}
