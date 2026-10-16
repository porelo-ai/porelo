package org.fuin.sokar.shield;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link NetlinkSocket}, against the real kernel.
 * <p>
 * A bind is made to fail the way the kernel fails it: a second socket asking for a port another socket of this process
 * already holds is refused with {@code EADDRINUSE}. Nothing is simulated, so what is counted afterwards is what the
 * kernel still holds open.
 */
class NetlinkSocketTest {

    /** A netlink port no other socket of this test asks for. */
    private static final int PORT = 0x7a11c0de;

    private static long openDescriptors() throws IOException {
        try (Stream<Path> descriptors = Files.list(Path.of("/proc/self/fd"))) {
            return descriptors.count();
        }
    }

    @Test
    void aFailedBindClosesTheSocketItOpened() throws IOException {

        try (NetlinkSocket holder = new NetlinkSocket(PORT)) {
            final long before = openDescriptors();

            assertThatThrownBy(() -> new NetlinkSocket(PORT))
                    .isInstanceOf(NetlinkException.class)
                    .hasMessageContaining("bind(AF_NETLINK) failed");

            assertThat(openDescriptors()).as("descriptors still open after the refused bind").isEqualTo(before);
        }
    }

    @Test
    void bindsToAPortTheKernelChoosesByDefault() {
        try (NetlinkSocket first = new NetlinkSocket(); NetlinkSocket second = new NetlinkSocket()) {
            assertThat(first).isNotNull();
            assertThat(second).isNotNull();
        }
    }
}
