package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link HashChain}, against hashes computed apart from it: a record written before it
 * moved here must still verify, so its material may not change.
 */
class HashChainTest {

    @Test
    void theMaterialIsEachFieldLengthPrefixedThePreviousFirst() {
        // sha256("0:" "9:delivered" "6:m.json" "2:id" "4:peer" "0:"), computed outside Java.
        assertThat(HashChain.link("", List.of("delivered", "m.json", "id", "peer", "")))
                .isEqualTo("0aa1049fcd3a94e1936eceddc03a46b04333d42cf7e7d0ca17c42813be042de1");
        // sha256("64:" + 64 x "a" + "7:message" "3:out").
        assertThat(HashChain.link("a".repeat(64), List.of("message", "out")))
                .isEqualTo("7f94f9d778e2049871a2c9f3acf2819443090836278db7ccac3eb48be07f893b");
    }
}
