package org.fuin.porelo.wire.varlink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.fuin.porelo.wire.Json;
import org.junit.jupiter.api.Test;

/** Tests for {@link VarlinkNumbers}: whole numbers read exactly, never cut. */
class VarlinkNumbersTest {

    @Test
    void aWholeNumberAsAPeerSendsItIsReadExactly() {
        assertThat(VarlinkNumbers.whole(Json.parseExact("12")))
                .as("a whole number arrives as a Long and is read as it is")
                .hasValue(12L);
        assertThat(VarlinkNumbers.wholeInt(Json.parseExact("-3"))).hasValue(-3);
        assertThat(VarlinkNumbers.whole(Json.parseExact("1.0")))
                .as("1.0 is the whole number 1 as the wire reads it")
                .hasValue(1L);
    }

    @Test
    void aFractionIsNoWholeNumberHoweverCloseItLies() {
        for (final String sent : new String[] {"1.5", "10.9", "1.00000000000000001", "-0.5"}) {
            assertThat(VarlinkNumbers.whole(Json.parseExact(sent)))
                    .as(sent + " is refused, never cut to a whole number")
                    .isEmpty();
        }
        assertThat(VarlinkNumbers.whole("3")).as("text is no number").isEmpty();
        assertThat(VarlinkNumbers.whole(null)).isEmpty();
    }

    @Test
    void aWholeNumberPastAnIntIsNoInt() {
        assertThat(VarlinkNumbers.wholeInt(1L << 32))
                .as("2^32 is refused, never read as 0 or held at the bound")
                .isEmpty();
        assertThat(VarlinkNumbers.wholeInt((long) Integer.MAX_VALUE)).hasValue(Integer.MAX_VALUE);
    }

    @Test
    void aParameterGivenAsAFractionIsRefusedUnderItsName() {
        assertThat(VarlinkNumbers.intParameter(Map.of(), "last"))
                .as("a parameter not given is left to its default")
                .isNull();
        assertThat(VarlinkNumbers.intParameter(Map.of("last", 20L), "last")).isEqualTo(20);
        assertThatThrownBy(() -> VarlinkNumbers.intParameter(Map.of("last", 1.5), "last"))
                .as("a fraction is the method's wrong parameter")
                .isInstanceOf(VarlinkException.class)
                .hasMessageContaining("InvalidParameter");
        assertThatThrownBy(() -> VarlinkNumbers.longParameter(Map.of(), "bytes"))
                .as("a required one not given is refused too")
                .isInstanceOf(VarlinkException.class)
                .hasMessageContaining("InvalidParameter");
    }
}
