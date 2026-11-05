package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** What a transport said, as it is repeated: every value it was handed is gone from it. */
class TransportWithheldTest {

    @Test
    void aShortSecretIsWithheldLikeALongOne() {
        assertThat(
                        TransportLifecycle.withheld(
                                "login failed for abc123 with a-much-longer-secret",
                                Map.of("PIN", "abc123", "LONG", "a-much-longer-secret")))
                .isEqualTo("login failed for [withheld] with [withheld]");
    }

    @Test
    void aSecretOfManyLinesIsWithheldLineByLine() {
        assertThat(
                        TransportLifecycle.withheld(
                                "could not read one\nnor two", Map.of("KEY", "one\ntwo\n")))
                .isEqualTo("could not read [withheld]\nnor [withheld]");
    }

    @Test
    void anEmptyValueLeavesTheTextAlone() {
        assertThat(TransportLifecycle.withheld("it said this", Map.of("EMPTY", "", "BLANK", " ")))
                .isEqualTo("it said this");
    }
}
