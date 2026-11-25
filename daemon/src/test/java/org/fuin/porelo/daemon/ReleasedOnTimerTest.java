package org.fuin.porelo.daemon;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * What the owner released is sent on the daemon's timer too, so a release decided on the command
 * line, which calls no daemon method, and a retry whose time passed go out without a call.
 */
class ReleasedOnTimerTest {

    @Test
    void theTimerStartsASendPassWithNoDecisionThroughTheDaemon() throws Exception {
        final CountDownLatch passes = new CountDownLatch(2);

        try (ReadingSweep sweep = new ReadingSweep(Duration.ofMillis(10))) {
            GateMethods.sendOnTimer(sweep, passes::countDown);

            assertThat(passes.await(30, TimeUnit.SECONDS))
                    .as("send passes started by the timer alone, again and again")
                    .isTrue();
        }
    }
}
