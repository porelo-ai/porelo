package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Tests for what {@code porelo talk dismiss} says. */
class TalkDismissCommandTest {

    @Test
    void aDismissSaysTheMachineLeavesEveryProjectOnThisServer() {

        // It said "from <project>'s conversation" while the transport took the machine out of
        // every project's conversation on the server and revoked its token: a person who read it
        // would expect a second project of that machine to go on.
        assertThat(TalkDismissCommand.said("laptop", Map.of("removed", List.of("@porelo.laptop"))))
                .isEqualTo(
                        "dismissed laptop from every project's conversation on this server, its"
                                + " token revoked");
        assertThat(TalkDismissCommand.said("laptop", Map.of("removed", List.of())))
                .isEqualTo("nothing of laptop was left on this server");
    }
}
