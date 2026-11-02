package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.fuin.porelo.organization.gittree.FetchAccess;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Harm guarded: a change signed and then refused at the push, for want of the person's agent. */
class IdentityOrganizationTest {
    @TempDir Path dir;
    private RegistryCheckout machine;

    @BeforeEach
    void machine() throws Exception {
        machine = new RegistryCheckout(dir);
    }

    @AfterEach
    void tidyUp() throws Exception {
        machine.close();
    }

    /** Git pushes with what the lease says alone, so an ssh address needs the agent in it. */
    @Test
    void the_lease_of_a_push_carries_the_persons_agent() throws Exception {
        machine.agent();

        final FetchAccess access = IdentityOrganization.lent(machine.context());
        try (FetchAccess.Lease lease = access.lend(RegistryCheckout.ORIGIN)) {
            assertThat(lease.environment())
                    .as("the agent of the person who acts")
                    .containsEntry("SSH_AUTH_SOCK", machine.environment().apply("SSH_AUTH_SOCK"));
        }
    }
}
