package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.fuin.porelo.wire.LimitFactors;
import org.junit.jupiter.api.Test;

/** The organization area's time limits under the account's factors. */
class OrganizationTimeFactorsTest {

    @Test
    void lookingForANewForkAndRefreshingAgainFollowTheRetriesFactor() {
        final LimitFactors doubled = LimitFactors.parse("retries=2");

        assertThat(ForgeCardProposals.forkTries(doubled)).isEqualTo(60);
        assertThat(ForgeCardProposals.forkPause(doubled)).isEqualTo(Duration.ofMillis(500));
        assertThat(OrgRefreshCommand.retry(doubled)).isEqualTo(Duration.ofSeconds(20));
        assertThat(ForgeCardProposals.forkTries(LimitFactors.DEFAULT)).isEqualTo(30);
        assertThat(ForgeCardProposals.forkPause(LimitFactors.DEFAULT))
                .isEqualTo(Duration.ofMillis(250));
        assertThat(OrgRefreshCommand.retry(LimitFactors.DEFAULT)).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void aRequestToTheForgeFollowsTheServicesFactor() {
        assertThat(ForgeCardProposals.requestLimit(LimitFactors.parse("services=2")))
                .isEqualTo(Duration.ofSeconds(20));
        assertThat(ForgeCardProposals.requestLimit(LimitFactors.DEFAULT))
                .isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void aRefreshAndItsWorkersEndFollowTheMachinesFactor() {
        final LimitFactors doubled = LimitFactors.parse("machines=2");

        assertThat(OrgRefreshCommand.refreshLimit(doubled, OrgRefreshCommand.LIMIT))
                .isEqualTo(Duration.ofMinutes(4));
        assertThat(OrgRefreshCommand.workerEnd(doubled)).isEqualTo(Duration.ofSeconds(20));
        assertThat(OrgRefreshCommand.refreshLimit(LimitFactors.DEFAULT, OrgRefreshCommand.LIMIT))
                .isEqualTo(Duration.ofMinutes(2));
        assertThat(OrgRefreshCommand.workerEnd(LimitFactors.DEFAULT))
                .isEqualTo(Duration.ofSeconds(10));
    }
}
