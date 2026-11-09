package org.fuin.porelo.host;

import static org.assertj.core.api.Assertions.*;

import java.net.SocketTimeoutException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** Guards the socket deadline even when another request byte is immediately available. */
class GateConnectionTest {
    @Test
    void progressDoesNotRenewThePushConnectionsReadDeadline() throws Exception {
        final Path path = Path.of("target", "gc.sock");
        Files.deleteIfExists(path);
        try (var listener = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            listener.bind(UnixDomainSocketAddress.of(path));
            try (var peer = SocketChannel.open(UnixDomainSocketAddress.of(path));
                    var accepted = listener.accept()) {
                final var now = new AtomicLong();
                try (var connection = new GateConnection(accepted, false, 30, now::get)) {
                    connection.readDeadline(Duration.ofSeconds(30));
                    peer.write(ByteBuffer.wrap(new byte[] {1}));
                    assertThat(connection.input.read())
                            .as("the request starts within its budget")
                            .isEqualTo(1);
                    now.set(Duration.ofSeconds(31).toNanos());
                    peer.write(ByteBuffer.wrap(new byte[] {2}));
                    assertThatThrownBy(() -> connection.input.read())
                            .as("available bytes cannot extend the whole request's deadline")
                            .isInstanceOf(SocketTimeoutException.class);
                    connection.output.write(3);
                    final ByteBuffer response = ByteBuffer.allocate(1);
                    peer.read(response);
                    assertThat(response.get(0))
                            .as("the expired request still gets a fresh response budget")
                            .isEqualTo((byte) 3);
                }
            }
        } finally {
            Files.deleteIfExists(path);
        }
    }
}
