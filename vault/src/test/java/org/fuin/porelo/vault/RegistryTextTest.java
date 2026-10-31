package org.fuin.porelo.vault;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Guards against text from a registry or a forge moving a terminal's cursor or reordering it. */
class RegistryTextTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "\u001b[2J", // ESC, C0
                "\u007f", // DEL
                "\u009b31m", // CSI, C1
                "\u0085", // NEL, C1
                "‪",
                "‫",
                "‬",
                "‭",
                "‮", // embeddings and overrides
                "⁦",
                "⁧",
                "⁨",
                "⁩", // isolates
                " ", // line separator
                " ", // paragraph separator
                "​" // zero width space, a format character
            })
    void replacesEveryControlFormatAndSeparatorCharacter(String hostile) {
        // A C1 escape is read as a control sequence by many terminals; a bidi override shows a
        // commit or login reversed; a separator starts a line a person reads as Porelo's.
        assertThat(RegistryText.printable("a" + hostile + "b"))
                .startsWith("a?")
                .endsWith("b")
                .doesNotContain(hostile.substring(0, 1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"alice", "Jürgen Müller", "SHA256:abc+/=", "名前"})
    void keepsPrintableText(String text) {
        assertThat(RegistryText.printable(text)).isEqualTo(text);
    }
}
