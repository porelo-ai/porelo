package org.fuin.porelo.daemon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.Map;
import org.fuin.porelo.app.TaskLaunch;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.junit.jupiter.api.Test;

/** Clients decide what to offer from error values, never a terminal's sentence or exit code. */
class StartCredentialErrorsTest {
    @Test
    void aMissingModelCredentialNamesTheAgentAndTheRunningSignIn() {
        // A client must attach to the existing sign-in rather than authorize another one.
        final VarlinkException refused =
                catchThrowableOfType(
                        () ->
                                TaskMethods.credentialRefused(
                                        new TaskLaunch.CredentialRefusal(
                                                "example", false, "porelo-login-1000000000001")),
                        VarlinkException.class);
        assertThat(refused).as("a missing model credential must be a typed refusal").isNotNull();
        assertThat(refused.getErrorName())
                .as("the client can offer sign-in without parsing a sentence")
                .isEqualTo(PoreloDaemon.INTERFACE + ".SignInNeeded");
        assertThat(refused.getParameters())
                .as("the refusal identifies the agent and the existing sign-in")
                .isEqualTo(Map.of("agent", "example", "running", "porelo-login-1000000000001"));
    }

    @Test
    void aLockedVaultNeverAsksTheClientToSignInAgain() {
        // A locked vault must offer unlocking instead of risking another authorization.
        final VarlinkException refused =
                catchThrowableOfType(
                        () ->
                                TaskMethods.credentialRefused(
                                        new TaskLaunch.CredentialRefusal("example", true, null)),
                        VarlinkException.class);
        assertThat(refused).as("a locked vault must be a typed refusal").isNotNull();
        assertThat(refused.getErrorName())
                .as("unlocking is distinct from signing in")
                .isEqualTo(PoreloDaemon.INTERFACE + ".VaultLocked");
        assertThat(refused.getParameters())
                .as("VaultLocked carries no guessed agent or sign-in")
                .isEmpty();
    }
}
