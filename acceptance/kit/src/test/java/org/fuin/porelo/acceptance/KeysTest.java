package org.fuin.porelo.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/** Tests the keys a scenario can press, as the bytes an {@code xterm-256color} sends for them. */
class KeysTest {

    @Test
    void eachKeyIsTheBytesXtermSendsForIt() {
        // As measured on 2026-10-09 with a program logging every byte it received behind the task's
        // tmux.
        assertThat(hex("Esc")).isEqualTo("1b");
        assertThat(hex("PageUp")).isEqualTo("1b5b357e");
        assertThat(hex("PageDown")).isEqualTo("1b5b367e");
        assertThat(hex("Shift+PageUp")).isEqualTo("1b5b353b327e");
        assertThat(hex("Shift+PageDown")).isEqualTo("1b5b363b327e");
        assertThat(hex("Shift+Enter")).as("the CSI u form").isEqualTo("1b5b31333b3275");
        assertThat(hex("Shift+Enter, xterm's form")).isEqualTo("1b5b32373b323b31337e");
        assertThat(hex("Ctrl+Right")).isEqualTo("1b5b313b3543");
        assertThat(hex("Alt+x")).isEqualTo("1b78");
        assertThat(hex("focus in")).isEqualTo("1b5b49");
        assertThat(hex("focus out")).isEqualTo("1b5b4f");
    }

    @Test
    void aKeyTheTableDoesNotHaveIsRefusedNamingTheOnesItHas() {
        assertThatThrownBy(() -> Keys.bytes("Ctrl+Alt+Del"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Ctrl+Alt+Del")
                .hasMessageContaining("PageUp");
    }

    @Test
    void aKeyIsWrittenRawWithNoLineEnd() throws IOException {
        final ByteArrayOutputStream sent = new ByteArrayOutputStream();
        final Terminal terminal =
                new Terminal(new ByteArrayInputStream(new byte[0]), sent, () -> {});

        terminal.press(Keys.bytes("Esc"));

        assertThat(HexFormat.of().formatHex(sent.toByteArray())).isEqualTo("1b");
    }

    private static String hex(final String key) {
        return HexFormat.of().formatHex(Keys.bytes(key));
    }
}
