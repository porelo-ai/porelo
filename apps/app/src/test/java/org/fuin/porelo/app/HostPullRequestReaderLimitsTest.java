package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.fuin.porelo.wire.LimitFactors;
import org.junit.jupiter.api.Test;

/** Tests for the time limit {@link HostPullRequestReader} keeps on reading a local mirror. */
class HostPullRequestReaderLimitsTest {

    @Test
    void readingAMakersCommitFromALocalMirrorFollowsTheMachinesFactor() {
        assertThat(HostPullRequestReader.makersBound(LimitFactors.parse("services=2")))
                .isEqualTo(Duration.ofSeconds(10));
        assertThat(HostPullRequestReader.makersBound(LimitFactors.parse("machines=2")))
                .isEqualTo(Duration.ofSeconds(20));
    }
}
