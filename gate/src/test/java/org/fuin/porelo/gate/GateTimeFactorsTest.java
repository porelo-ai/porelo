package org.fuin.porelo.gate;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.fuin.porelo.wire.LimitFactors;
import org.junit.jupiter.api.Test;

/** The gate's own time limits under the account's factors. */
class GateTimeFactorsTest {

    @Test
    void theAskAfterAnUnansweredPushFollowsTheServicesFactor() {
        assertThat(GitGate.askingAfter(LimitFactors.parse("services=2")))
                .isEqualTo(Duration.ofSeconds(60));
        assertThat(GitGate.askingAfter(LimitFactors.DEFAULT)).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void gitAndSshKeygenAndAWaitingPushFollowTheMachinesFactor() {
        final LimitFactors doubled = LimitFactors.parse("machines=2");

        assertThat(GitSubprocess.timeout(doubled)).isEqualTo(600);
        assertThat(GitHttpServer.pushWait(doubled)).isEqualTo(Duration.ofMinutes(10));
        assertThat(SignedArrivals.check(doubled)).isEqualTo(Duration.ofSeconds(120));
        assertThat(GitSubprocess.timeout(LimitFactors.DEFAULT)).isEqualTo(300);
        assertThat(GitHttpServer.pushWait(LimitFactors.DEFAULT)).isEqualTo(Duration.ofMinutes(5));
        assertThat(SignedArrivals.check(LimitFactors.DEFAULT)).isEqualTo(Duration.ofSeconds(60));
    }
}
