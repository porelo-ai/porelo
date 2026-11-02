package org.fuin.porelo.organization.contract;

/** An honest copy passes every offline check. */
class HonestStandInOfflineTest extends OfflineContract {

    @Override
    protected OfflineFixture fixture() {
        return new StandIn();
    }
}
