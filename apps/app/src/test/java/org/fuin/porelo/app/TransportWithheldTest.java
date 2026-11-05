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

    @Test
    void aShortValueLeavesAWordThatHoldsItReadable() {
        assertThat(TransportLifecycle.withheld("cannot reach a host", Map.of("PIN", "a")))
                .isEqualTo("cannot reach [withheld] host");
    }

    @Test
    void whatIsWithheldIsNotWithheldAgainInsideTheMark() {
        assertThat(
                        TransportLifecycle.withheld(
                                "a-long-enough-secret and h",
                                Map.of(
                                        "LONG",
                                        "a-long-enough-secret",
                                        "A",
                                        "h",
                                        "B",
                                        "h",
                                        "C",
                                        "i")))
                .isEqualTo("[withheld] and [withheld]");
    }

    @Test
    void aLongValueIsWithheldInsideAWord() {
        assertThat(
                        TransportLifecycle.withheld(
                                "xa-long-enough-secretx", Map.of("LONG", "a-long-enough-secret")))
                .isEqualTo("x[withheld]x");
    }

    @Test
    void aSecretThatLooksLikeAPatternIsTakenAsItStands() {
        assertThat(TransportLifecycle.withheld("said (a+b)$1 here", Map.of("PW", "(a+b)$1")))
                .isEqualTo("said [withheld] here");
    }
}
