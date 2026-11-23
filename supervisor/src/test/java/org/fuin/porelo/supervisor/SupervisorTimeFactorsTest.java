package org.fuin.porelo.supervisor;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.fuin.porelo.wire.LimitFactors;
import org.junit.jupiter.api.Test;

/** The supervisor's time limits under the account's factors. */
class SupervisorTimeFactorsTest {

    @Test
    void everyAnswerFromAServiceFollowsTheServicesFactor() {
        final LimitFactors doubled = LimitFactors.parse("services=2");

        assertThat(ClientRegistration.requestLimit(doubled)).isEqualTo(Duration.ofSeconds(60));
        assertThat(Grants.requestLimit(doubled)).isEqualTo(Duration.ofSeconds(60));
        assertThat(ServiceMetadata.requestLimit(doubled)).isEqualTo(Duration.ofSeconds(60));
        assertThat(TokenPurchase.requestLimit(doubled)).isEqualTo(Duration.ofSeconds(60));
        assertThat(UnixHttp.idle(doubled)).isEqualTo(Duration.ofMinutes(20));
        assertThat(VaultProxy.connect(doubled)).isEqualTo(Duration.ofSeconds(60));
        assertThat(VaultProxy.upstream(doubled)).isEqualTo(Duration.ofMinutes(20));

        final LimitFactors plain = LimitFactors.DEFAULT;
        assertThat(ClientRegistration.requestLimit(plain)).isEqualTo(Duration.ofSeconds(30));
        assertThat(Grants.requestLimit(plain)).isEqualTo(Duration.ofSeconds(30));
        assertThat(ServiceMetadata.requestLimit(plain)).isEqualTo(Duration.ofSeconds(30));
        assertThat(TokenPurchase.requestLimit(plain)).isEqualTo(Duration.ofSeconds(30));
        assertThat(UnixHttp.idle(plain)).isEqualTo(Duration.ofMinutes(10));
        assertThat(VaultProxy.connect(plain)).isEqualTo(Duration.ofSeconds(30));
        assertThat(VaultProxy.upstream(plain)).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void aBrokersConnectAndClosingFollowTheMachinesFactor() {
        final LimitFactors doubled = LimitFactors.parse("machines=2");

        assertThat(RepositoryProxy.connect(doubled)).isEqualTo(Duration.ofSeconds(60));
        assertThat(VaultProxy.closing(doubled)).isEqualTo(Duration.ofSeconds(4));
        assertThat(RepositoryProxy.connect(LimitFactors.DEFAULT)).isEqualTo(Duration.ofSeconds(30));
        assertThat(VaultProxy.closing(LimitFactors.DEFAULT)).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void howLongARefusalIsKeptFollowsThePollingFactor() {
        final LimitFactors doubled = LimitFactors.parse("polling=2");

        assertThat(TokenPurchase.refusalHeld(doubled)).isEqualTo(Duration.ofSeconds(10));
        assertThat(TokenPurchase.refusalHeld(LimitFactors.DEFAULT))
                .isEqualTo(Duration.ofSeconds(5));
    }
}
