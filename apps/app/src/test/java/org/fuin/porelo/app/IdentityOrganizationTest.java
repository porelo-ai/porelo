package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.io.IOException;
import java.nio.file.Path;
import org.fuin.porelo.organization.OrganizationQuestions;
import org.fuin.porelo.organization.gittree.FetchAccess;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Harm guarded: a change signed and then refused at the push, for want of the person's agent, and a
 * failure read as a key the person could serve, its change kept as never made.
 */
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

        final FetchAccess access = OrganizationAccess.lent(machine.context());
        try (FetchAccess.Lease lease = access.lend(RegistryCheckout.ORIGIN)) {
            assertThat(lease.environment())
                    .as("the agent of the person who acts")
                    .containsEntry("SSH_AUTH_SOCK", machine.environment().apply("SSH_AUTH_SOCK"));
        }
    }

    /** The store's typed answer is the whole of it, and a refusal comes before any push. */
    @Test
    void a_store_saying_no_key_is_no_key_with_nothing_pushed() throws Exception {
        final IdentityChange.Refused refused =
                catchThrowableOfType(
                        IdentityChange.Refused.class,
                        () ->
                                IdentityOrganization.filed(
                                        () -> {
                                            throw new OrganizationQuestions.Refused(
                                                    OrganizationQuestions.Refused.NO_KEY,
                                                    "the ssh-agent did not sign with SHA256:abc",
                                                    null);
                                        }));

        assertThat(refused.code()).isEqualTo(69);
        assertThat(refused.reason()).isEqualTo("no-key");
        assertThat(refused.nothingPushed()).as("the commit was never made").isTrue();
        assertThat(refused.words()).as("which key to serve").contains("SHA256:abc");
    }

    /**
     * Only the store's typed answer says a key is missing: words that merely read like it are a
     * failure, after which the change may have been made and the person keeps the id.
     */
    @Test
    void a_failure_worded_like_an_unsigned_commit_is_no_no_key() {
        final IdentityChange.Refused refused =
                catchThrowableOfType(
                        IdentityChange.Refused.class,
                        () ->
                                IdentityOrganization.filed(
                                        () -> {
                                            throw new IOException(
                                                    "cannot make the change: no space left");
                                        }));

        assertThat(refused.code()).isEqualTo(70);
        assertThat(refused.reason()).isEqualTo("failed");
        assertThat(refused.nothingPushed()).as("nobody knows that").isFalse();
    }

    @Test
    void the_org_commands_fetch_is_lent_the_agent_as_well() throws Exception {
        // Over ssh a fetch runs with a chosen environment, and has no other way to the person's
        // key: a person with no key in the vault could read no organization at an ssh address.
        machine.agent();
        try (FetchAccess.Lease lease =
                OrganizationAccess.lent(machine.context()).lend(RegistryCheckout.ORIGIN)) {
            assertThat(lease.environment())
                    .as("the agent of the person who reads")
                    .containsEntry("SSH_AUTH_SOCK", machine.environment().apply("SSH_AUTH_SOCK"));
        }
    }
}
