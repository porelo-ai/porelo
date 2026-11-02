package org.fuin.porelo.organization.contract;

/** An honest store passes every check, so a check that fails is about what it was broken for. */
class HonestWritesStandInTest extends WritesContract {

    @Override
    protected WritesFixture fixture() {
        return new WritesStandIn();
    }
}
