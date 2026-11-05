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

        // Exactly this: neither a prefix nor a suffix nor its length.
        assertThat(token.toString()).isEqualTo("PhantomToken[credential/task]");
    }
}
