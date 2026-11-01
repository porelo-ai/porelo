package org.fuin.porelo.core.process;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Holds the peer the kernel names to the account that bound the socket. */
class SocketPeerTest {
    @Test
    void readsTheConnectingProcessFromTheKernel(@TempDir Path dir) throws Exception {
        final Path socket = dir.resolve("peer.sock");
        try (ServerSocketChannel listener = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            listener.bind(UnixDomainSocketAddress.of(socket));
            try (SocketChannel caller = SocketChannel.open(UnixDomainSocketAddress.of(socket));
                    SocketChannel accepted = listener.accept()) {
                final var peer = SocketPeer.read(accepted);
                assertThat(peer.uid())
                        .as("the connecting process runs as this account, whose uid is answered")
                        .isEqualTo(
                                ((Number) java.nio.file.Files.getAttribute(dir, "unix:uid"))
                                        .longValue());
                assertThat(peer.sameUser()).as("the connecting uid is this process's uid").isTrue();
            }
        }
    }

    @Test
    void theCallerOfASocketReadsTheServerAsThisAccountFromAnyWorkingDirectory(@TempDir Path dir)
            throws Exception {
        final Path socket = dir.resolve("peer.sock");
        try (ServerSocketChannel listener = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            listener.bind(UnixDomainSocketAddress.of(socket));
            try (SocketChannel caller = SocketChannel.open(UnixDomainSocketAddress.of(socket));
                    SocketChannel accepted = listener.accept()) {
                // The calling end has no path of its own: the server is judged, not "." (the
                // working directory, which is root's on a host's helper and refused the server).
                final var server = SocketPeer.read(caller);
                assertThat(server.sameUser()).as("the server runs as this account").isTrue();
                assertThat(server.uid()).isEqualTo(SocketPeer.ownUid());
            }
        }
    }
}
