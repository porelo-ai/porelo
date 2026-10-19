package org.fuin.porelo.vault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Tests for {@link StdinPassphrase}, the one line a program hands over on standard input. */
class StdinPassphraseTest {

    private static InputStream of(final String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    private static Optional<String> read(final InputStream input) {
        return new StdinPassphrase(input).passphrase().map(String::new);
    }

    @Test
    void takesTheFirstLineWithoutItsNewline() {

        assertThat(read(of("correct horse\n"))).as("the line").contains("correct horse");
    }

    @Test
    void takesALineEndingInCarriageReturnAndNewline() {

        assertThat(read(of("correct horse\r\n"))).as("the line").contains("correct horse");
    }

    @Test
    void takesALastLineWithoutANewline() {

        assertThat(read(of("correct horse"))).as("the line").contains("correct horse");
    }

    @Test
    void readsNoFurtherThanTheLine() {

        // What follows is for whoever reads next, as a program handing over more than the
        // passphrase on one channel would expect.
        final InputStream input = of("first\nsecond\n");

        assertThat(read(input)).as("the first line").contains("first");
        assertThat(read(input)).as("what was left").contains("second");
    }

    @Test
    void answersNothingForAnEmptyStreamOrAnEmptyLine() {

        assertThat(read(of(""))).as("nothing at all").isEmpty();
        assertThat(read(of("\n"))).as("an empty line").isEmpty();
    }

    @Test
    void takesALineAtTheLimitAndRefusesOneOverIt() {

        final String atLimit = "a".repeat(StdinPassphrase.MAX_BYTES);

        assertThat(read(of(atLimit + "\n"))).as("at the limit").contains(atLimit);
        assertThatThrownBy(() -> read(of(atLimit + "a\n")))
                .isInstanceOf(VaultException.class)
                .hasMessageContaining("longer than " + StdinPassphrase.MAX_BYTES + " bytes");
    }

    @Test
    void takesUtf8BeyondAscii() {

        assertThat(read(of("Grüße aus Zürich ☃\n"))).as("the line").contains("Grüße aus Zürich ☃");
    }

    @Test
    void refusesBytesThatAreNotUtf8() {

        assertThatThrownBy(
                        () -> read(new ByteArrayInputStream(new byte[] {(byte) 0xff, (byte) 0xfe})))
                .isInstanceOf(VaultException.class)
                .hasMessageContaining("not UTF-8");
    }
}
