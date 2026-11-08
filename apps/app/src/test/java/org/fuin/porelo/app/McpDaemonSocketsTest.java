package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** The per-task socket opener porelod installs. */
class McpDaemonSocketsTest {

    @Test
    @SuppressWarnings("NullAway")
    void noOpenerIsRefusedAndTheOneInPlaceStays() {
        final McpDaemonSockets.Opener before = McpDaemonSockets.current();

        assertThatThrownBy(() -> McpDaemonSockets.install(null))
                .isInstanceOf(NullPointerException.class);
        assertThat(McpDaemonSockets.current()).isSameAs(before);
    }
}
