package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Tests for {@link TerminalText}: agent text that can neither act on a terminal nor reorder it. */
class TerminalTextTest {

    @ParameterizedTest
    @ValueSource(
            ints = {
                0x202a, 0x202b, 0x202c, 0x202d, 0x202e, 0x2066, 0x2067, 0x2068, 0x2069, 0x200b,
                0x200c, 0x200d, 0x200e, 0x200f
            })
    void aFormatCharacterShowsAsItsCodePoint(final int point) {
        // A bidi override or isolate in a file name could show a review's line in another order
        // than it was written; a zero-width character could hide what tells two names apart.
        final String text = "a" + Character.toString(point) + "b";
        final String expected = "a\\u%04xb".formatted(point);

        assertThat(TerminalText.line(text)).isEqualTo(expected);
        assertThat(TerminalText.lines(text + "\n" + text)).isEqualTo(expected + "\n" + expected);
    }

    @Test
    void aFormatCharacterBeyondTheBasicPlaneShowsAsItsCodePoint() {
        // A tag character is a format character too, written as a surrogate pair.
        assertThat(TerminalText.line("a" + Character.toString(0xe0001) + "b"))
                .isEqualTo("a\\u{e0001}b");
    }

    @Test
    void separatorsAndALoneSurrogateShowAsTheirCodePoint() {
        // A line separator breaks a line in some terminals; half a surrogate pair shows as nothing.
        final String text =
                "a"
                        + Character.toString(0x2028)
                        + "b"
                        + Character.toString(0x2029)
                        + "c"
                        + (char) 0xd800
                        + "d";
        assertThat(TerminalText.line(text)).isEqualTo("a\\u2028b\\u2029c\\ud800d");
    }

    @Test
    void controlsShowAsTheirByteAndPrintableTextStays() {
        assertThat(TerminalText.line("\u001b[8m\r\b\u007f\u009b\tgrüß א"))
                .isEqualTo("\\x1b[8m\\x0d\\x08\\x7f\\x9b\tgrüß א");
    }
}
