package org.fuin.porelo.organization.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** An honest store passes every check, so a check that fails is about what it was broken for. */
class HonestStandInTest extends OrganizationContract {

    @Override
    protected OrganizationFixture fixture() {
        return new StandIn();
    }

    @Test
    void theListSeparatesOwnersFromOrganizationAdmins() throws Exception {
        final StandIn store = new StandIn();
        store.found("mia");
        store.fileCard("mia");
        store.fileCard("carol");
        assertThat(store.read().getOrganizationAdmins()).isEmpty();
        assertThat(store.read().isOrganizationAdmin("mia")).isTrue();
        store.listOrganizationAdmins("mia", "carol");
        assertThat(store.read().getOrganizationAdmins()).contains(List.of("carol"));
        assertThat(store.read().isOrganizationAdmin("mia")).isFalse();
        assertThat(store.read().isOrganizationAdmin("carol")).isTrue();
        store.fileCard("alice");
        store.fileCard("bob");
        store.makeProject("mia", "blocked", "alice");
        assertThat(store.read().projectAdmins("blocked"))
                .as("an unlisted owner creates no project")
                .isEmpty();
        store.makeProject("carol", "demo", "alice");
        store.addMember("alice", "demo", "bob", "developer");
        store.addMember("alice", "demo", "bob", "admin");
        assertThat(store.read().isProjectMember("demo", "bob"))
                .as("a Project Admin cannot promote a developer")
                .contains("developer");
        store.lockMember("carol", "demo", "alice");
        assertThat(store.read().isProjectMember("demo", "alice"))
                .as("the Organization Admin locks project admins")
                .isEmpty();
        store.lockMember("carol", "demo", "bob");
        assertThat(store.read().isProjectMember("demo", "bob"))
                .as("the Organization Admin cannot lock a developer")
                .contains("developer");
        store.listOrganizationAdmins("carol", "mia");
        assertThat(store.read().getOrganizationAdmins()).contains(List.of("carol"));
        store.listOrganizationAdmins("mia");
        assertThat(store.read().getOrganizationAdmins()).contains(List.of());
        assertThat(store.read().isOrganizationAdmin("mia")).isFalse();
    }
}
