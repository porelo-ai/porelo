package org.fuin.porelo.vault;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A token's line in a log or an exception: what it is for, and not a character of it. */
class PhantomTokenLineTest {

    @Test
    void aLineItLandsInHoldsNotOneCharacterOfIt() {
        final PhantomToken token =
                new TokenBroker(() -> Map.of("credential", "secret"))
                        .mint("credential", "task", Duration.ofHours(1));

        assertThat(token.toString())
                .doesNotContain(token.value().substring(0, PhantomToken.PREFIX.length() + 1))
                .doesNotContain("porelo_pt_")
                .contains("credential", "task");
    }
}
