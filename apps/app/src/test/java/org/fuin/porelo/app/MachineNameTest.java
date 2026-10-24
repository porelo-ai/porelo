package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

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
}
