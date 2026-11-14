package org.fuin.porelo.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WaitingStepsTest {

    private static final String LIST =
            """
            NAME                                   PROJECT        CLASS     STATE              ACTIVITY AGE  HELPERS MARK
            attended                               waits          guarded   Exited (143)       dead     3m    0       unsigned, unverified
            attended                               rests          guarded   Up About a minute  at rest  1m    6       unsigned, unverified
            attendedtoo                            works          guarded   Up 5 seconds       working  0m    6       unsigned, unverified
            """;

    @Test
    void aDeadRowOfAnotherProjectIsNotReadAsThisScenarios() {
        // Seen on a leg: one failed scenario's dead task stood above the next one's, and was read.
        assertThat(WaitingSteps.row(LIST, "attended", "rests"))
                .as("the row of the task's own project")
                .contains(" rests ")
                .contains(" at rest ")
                .doesNotContain("Exited (143)");
    }

    @Test
    void noRowOfThatProjectIsNoRow() {
        assertThat(WaitingSteps.row(LIST, "attended", "works")).as("not listed").isEmpty();
    }
}
