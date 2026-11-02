package org.fuin.porelo.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** A test's JVM cannot be handed the paths of the account it runs under. */
class RealAccountTest {

    @Test
    void theBuildRefusesTheRealAccountToThisJvm() {
        assertThat(RealAccount.refused())
                .as("the build sets %s for a unit test", RealAccount.REFUSED)
                .isTrue();
    }

    @Test
    void theAccountsDirectoriesAreRefused() {
        assertThat(RealAccount.refused()).as("otherwise this resolves the real ones").isTrue();

        assertThatThrownBy(XdgPaths::current)
                .as("nothing resolved from the real environment reaches a test")
                .isInstanceOf(RealAccount.Refused.class)
                .hasMessageContaining("XdgPaths.current()");
    }

    @org.junit.jupiter.api.Test
    void thePersonsAgentAndSessionBusAreNotInherited() {

        // A start reads the agent's socket from the process itself, and would sign with their key.
        org.assertj.core.api.Assertions.assertThat(System.getenv("SSH_AUTH_SOCK")).isNull();
        org.assertj.core.api.Assertions.assertThat(System.getenv("DBUS_SESSION_BUS_ADDRESS"))
                .isNull();
    }

    /** A key with a passphrase would otherwise open a window on the person's desktop. */
    @Test
    void nothingAsksThePersonOnTheirDesktop() {
        org.assertj.core.api.Assertions.assertThat(System.getenv("DISPLAY")).isNull();
        org.assertj.core.api.Assertions.assertThat(System.getenv("WAYLAND_DISPLAY")).isNull();
        org.assertj.core.api.Assertions.assertThat(System.getenv("SSH_ASKPASS")).isNull();
        org.assertj.core.api.Assertions.assertThat(System.getenv("SSH_ASKPASS_REQUIRE"))
                .isEqualTo("never");
    }
}
