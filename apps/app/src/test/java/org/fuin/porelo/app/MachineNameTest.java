package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.junit.jupiter.api.Test;

/** Holds the identity used by transports and clearing steady across the move. */
class MachineNameTest {
    @Test
    void theMachineKeepsItsHostNameOrLocalhostWhenItCannotBeResolved() {
        // A changed identity addresses transports and cleanup notices to a different machine.
        String expected;
        try {
            expected = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException ex) {
            expected = "localhost";
        }
        assertThat(MachineName.hostName())
                .as("the machine identity remains the local host name with the same fallback")
                .isEqualTo(expected);
    }

    @Test
    void twoAccountsOnOneHostGoByTwoNames() {
        // One name for both would let the second account's enroll meet the first one's account.
        assertThat(MachineName.of("Build-1", "alice"))
                .as("the host and the account, folded to lower case")
                .isEqualTo("build-1-alice");
        assertThat(MachineName.of("Build-1", "bob"))
                .as("another account on the same host")
                .isNotEqualTo(MachineName.of("Build-1", "alice"));
    }

    @Test
    void anAccountOutsideAMachineNamesCharactersIsRefusedBeforeATransportIsAsked() {
        // A transport is handed the name as an argument; a space or an option must never reach it.
        assertThatThrownBy(() -> MachineName.of("build-1", "al ice"))
                .as("a space is no machine name's character")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("al ice");
        assertThat(MachineName.of("build-1", "a".repeat(55)))
                .as("63 characters is still a name")
                .hasSize(63);
        assertThatThrownBy(() -> MachineName.of("build-1", "a".repeat(56)))
                .as("64 characters is not")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void eachHalfMustBeANameOfItsOwnSoTheHostCannotMaskTheAccount() {
        // Joined first, "build-1-" or "build-1---help" would pass as one name.
        assertThatThrownBy(() -> MachineName.of("build-1", ""))
                .as("an account with no name: two such accounts would share one")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MachineName.of("build-1", "--help"))
                .as("an account named like an option")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MachineName.of("-x", "alice"))
                .as("a host named like an option")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MachineName.of("build-1", "alice\n"))
                .as("a control character, shown as '?' in the refusal")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("alice?");
    }
}
