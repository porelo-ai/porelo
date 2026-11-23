package org.fuin.porelo.daemon;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.fuin.porelo.wire.LimitFactors;
import org.junit.jupiter.api.Test;

/** The daemon's time limits under the account's factors. */
class DaemonTimeFactorsTest {

    @Test
    void everyLookAgainFollowsThePollingFactor() {
        final LimitFactors doubled = LimitFactors.parse("polling=2");

        assertThat(PoreloDaemon.watchInterval(doubled)).isEqualTo(Duration.ofSeconds(1));
        assertThat(PoreloDaemon.projectWatchInterval(doubled)).isEqualTo(Duration.ofSeconds(6));
        assertThat(PoreloDaemon.promptInterval(doubled)).isEqualTo(Duration.ofSeconds(1));
        assertThat(PoreloDaemon.tailInterval(doubled)).isEqualTo(Duration.ofMillis(400));
        assertThat(PoreloDaemon.talkInterval(doubled)).isEqualTo(Duration.ofSeconds(4));
        assertThat(GateMethods.pullRequestPoll(doubled)).isEqualTo(Duration.ofSeconds(10));
        assertThat(SelfReplacement.interval(doubled)).isEqualTo(Duration.ofSeconds(10));

        final LimitFactors plain = LimitFactors.DEFAULT;
        assertThat(PoreloDaemon.watchInterval(plain)).isEqualTo(Duration.ofMillis(500));
        assertThat(PoreloDaemon.projectWatchInterval(plain)).isEqualTo(Duration.ofSeconds(3));
        assertThat(PoreloDaemon.promptInterval(plain)).isEqualTo(Duration.ofMillis(500));
        assertThat(PoreloDaemon.tailInterval(plain)).isEqualTo(Duration.ofMillis(200));
        assertThat(PoreloDaemon.talkInterval(plain)).isEqualTo(Duration.ofSeconds(2));
        assertThat(GateMethods.pullRequestPoll(plain)).isEqualTo(Duration.ofSeconds(5));
        assertThat(SelfReplacement.interval(plain)).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void aTaskSocketsEndFollowsTheMachinesFactor() {
        assertThat(McpTaskSockets.threadEnd(LimitFactors.parse("machines=2")))
                .isEqualTo(Duration.ofSeconds(10));
        assertThat(McpTaskSockets.threadEnd(LimitFactors.DEFAULT)).isEqualTo(Duration.ofSeconds(5));
    }
}
