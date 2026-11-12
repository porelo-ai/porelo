package org.fuin.porelo.daemon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.junit.jupiter.api.Test;

/**
 * A start carries its caller's own key, refused before anything starts when no listing holds it.
 */
class TaskCallerKeyMethodsTest {

    @Test
    void aStartWithoutAKeyHasNone() {
        assertThat(TaskMethods.callerKey(Map.of())).as("callerKey is optional").isNull();
    }

    @Test
    void aStartsKeyIsTakenAsGiven() {
        // Opaque: neither trimmed nor judged, so the caller finds exactly what it gave.
        final String key = " offer 17/a:" + "x".repeat(188);
        assertThat(TaskMethods.callerKey(Map.of("callerKey", key))).isEqualTo(key);
    }

    @Test
    void aKeyTooLongWithAControlCharacterOrNotAStringIsRefused() {
        for (final Object given : List.of("k".repeat(201), "a\nb", "a\u0000b", "\u007f", 17)) {
            assertThatThrownBy(() -> TaskMethods.callerKey(Map.of("callerKey", given)))
                    .as("%s", given)
                    .isInstanceOf(VarlinkException.class)
                    .hasMessageContaining("InvalidParameter");
        }
    }
}
