package org.fuin.porelo.supervisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link SocketRelay}: where the AppArmor task profile holds, the relay connects to the
 * host's socket only from a thread that entered {@code porelo-task}, so the kernel holds it to a
 * peer in {@code porelo} as it holds the task.
 */
class SocketRelayTest {

    @TempDir Path dir;

    private static final InetSocketAddress ANY_PORT = new InetSocketAddress("127.0.0.1", 0);

    /** The threads the relay asked to enter a profile on, and the profiles. */
    private final List<String> entered = new CopyOnWriteArrayList<>();

    private boolean entering(String profile) {
        entered.add(
                profile
                        + " on "
                        + Thread.currentThread().getName()
                        + (Thread.currentThread().isVirtual() ? " (virtual)" : ""));
        return true;
    }

    /** A broker stand-in on the host that answers each connection with one line. */
    private Path broker() throws IOException {
        final Path socket = dir.resolve("broker.sock");
        final ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(UnixDomainSocketAddress.of(socket));
        Thread.ofVirtual()
                .start(
                        () -> {
                            try (server) {
                                while (true) {
                                    try (SocketChannel client = server.accept()) {
                                        client.write(
                                                ByteBuffer.wrap(
                                                        "served\n"
                                                                .getBytes(
                                                                        StandardCharsets
                                                                                .US_ASCII)));
                                    }
                                }
                            } catch (IOException ex) {
                                // Closed with the test.
                            }
                        });
        return socket;
    }

    private static String through(SocketRelay relay) throws IOException {
        try (Socket client = new Socket()) {
            client.connect(relay.address(), 5_000);
            client.setSoTimeout(5_000);
            final OutputStream out = client.getOutputStream();
            out.flush();
            final InputStream in = client.getInputStream();
            return new String(in.readNBytes(7), StandardCharsets.US_ASCII);
        }
    }

    @Test
    void relaysAsBeforeWhereNoProfileHolds() throws IOException {
        try (SocketRelay relay = new SocketRelay(ANY_PORT, broker(), null, this::entering)) {
            Thread.ofVirtual().start(relay);

            assertThat(through(relay)).isEqualTo("served\n");
        }
        assertThat(entered).as("nothing entered where no profile is loaded").isEmpty();
    }

    @Test
    void entersTheTaskProfileOnceOnAThreadOfItsOwnBeforeItConnects() throws IOException {
        try (SocketRelay relay =
                new SocketRelay(ANY_PORT, broker(), "porelo-task", this::entering)) {
            assertThat(entered)
                    .as("entered before the first connection")
                    .singleElement()
                    .satisfies(
                            line ->
                                    assertThat(line)
                                            .startsWith("porelo-task on ")
                                            .doesNotContain("(virtual)")
                                            .doesNotContain(Thread.currentThread().getName()));
            Thread.ofVirtual().start(relay);

            assertThat(through(relay)).isEqualTo("served\n");
            assertThat(through(relay)).isEqualTo("served\n");
        }
        assertThat(entered).as("one thread, entered once, makes every connection").hasSize(1);
    }

    @Test
    void refusesToRelayWhereTheKernelRefusesTheProfile() throws IOException {

        // Unconfined, the relay would connect to whatever bound the socket: the lock the task is
        // held to would be open to an agent that reaches the broker by URL.
        final Path socket = broker();

        assertThatThrownBy(() -> new SocketRelay(ANY_PORT, socket, "porelo-task", profile -> false))
                .isInstanceOf(SupervisorException.class)
                .hasMessageContaining("porelo-task");
    }
}
