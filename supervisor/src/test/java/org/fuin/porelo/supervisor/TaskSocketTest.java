package org.fuin.porelo.supervisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The relay binds the vault socket through a container's root as that root's process, which on the
 * host is the account itself: a link it followed would put the socket, or a directory, wherever the
 * link points.
 */
class TaskSocketTest {

    private static final String INSIDE = "/run/porelo-vault/vault.sock";

    @Test
    void bindsTheSocketInsideTheRootWhereTheAgentMayConnectButNotWrite(@TempDir Path root)
            throws Exception {
        run(root);

        try (ServerSocketChannel server = TaskSocket.bind(root, INSIDE)) {
            final Path socket = root.resolve("run/porelo-vault/vault.sock");
            assertThat(Files.getAttribute(socket, "unix:mode", LinkOption.NOFOLLOW_LINKS))
                    .as("a socket, which the agent's account may connect to")
                    .isEqualTo(0140666);
            assertThat(
                            PosixFilePermissions.toString(
                                    Files.getPosixFilePermissions(socket.getParent())))
                    .as("a directory only its owner writes, so the agent cannot replace the socket")
                    .isEqualTo("rwxr-xr-x");
            try (SocketChannel client = SocketChannel.open(UnixDomainSocketAddress.of(socket))) {
                assertThat(client.isConnected()).as("it answers at that path").isTrue();
            }
        }
    }

    @Test
    void refusesALinkWhereTheDirectoryGoesAndMakesNothingWhereItPoints(
            @TempDir Path root, @TempDir Path elsewhere) throws Exception {
        run(root);
        Files.createSymbolicLink(root.resolve("run/porelo-vault"), elsewhere);

        assertThatThrownBy(() -> TaskSocket.bind(root, INSIDE))
                .as("a link inside the root is refused, never followed")
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("porelo-vault");
        try (var listed = Files.list(elsewhere)) {
            assertThat(listed).as("nothing is made where the link points").isEmpty();
        }
    }

    @Test
    void refusesALinkAboveTheDirectory(@TempDir Path root, @TempDir Path elsewhere)
            throws Exception {
        Files.createSymbolicLink(root.resolve("run"), elsewhere);

        assertThatThrownBy(() -> TaskSocket.bind(root, INSIDE))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("/run");
        try (var listed = Files.list(elsewhere)) {
            assertThat(listed).as("nothing is made where the link points").isEmpty();
        }
    }

    @Test
    void refusesADirectoryOthersMayWriteIn(@TempDir Path root) throws Exception {
        final Path run = Files.createDirectories(root.resolve("run"));
        Files.setPosixFilePermissions(run, PosixFilePermissions.fromString("rwxrwxrwx"));

        assertThatThrownBy(() -> TaskSocket.bind(root, INSIDE))
                .as("whoever writes in it could swap the directory under the relay")
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("/run");
    }

    @Test
    void refusesADirectoryAboveTheGroupMayWriteIn(@TempDir Path root) throws Exception {
        // Whoever may rename in a directory above can swap the socket's directory for a link
        // between the check and the bind, or move it aside afterwards and bind a stand-in.
        final Path run = Files.createDirectories(root.resolve("run"));
        Files.setPosixFilePermissions(run, PosixFilePermissions.fromString("rwxrwxr-x"));

        assertThatThrownBy(() -> TaskSocket.bind(root, INSIDE))
                .as("a directory above that its group may write is refused")
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("/run");
        assertThat(root.resolve("run/porelo-vault")).as("nothing is made below it").doesNotExist();
    }

    @Test
    void refusesAPathThatClimbsOutOfTheRoot(@TempDir Path base) throws Exception {
        final Path root = run(Files.createDirectory(base.resolve("root"))).getParent();

        assertThatThrownBy(() -> TaskSocket.bind(root, "/run/../../outside/vault.sock"))
                .as("a path with '..' is refused, never resolved")
                .isInstanceOf(java.io.IOException.class);
        assertThat(base.resolve("outside")).as("nothing is made outside the root").doesNotExist();
    }

    @Test
    void replacesTheSocketAnEarlierRelayLeftButNoOtherFile(@TempDir Path root) throws Exception {
        final Path directory = Files.createDirectory(run(root).resolve("porelo-vault"));
        final Path socket = directory.resolve("vault.sock");
        try (ServerSocketChannel earlier = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            earlier.bind(UnixDomainSocketAddress.of(socket));
        }

        try (ServerSocketChannel server = TaskSocket.bind(root, INSIDE)) {
            assertThat(server.isOpen()).as("a stale socket is the relay's own to replace").isTrue();
        }

        Files.delete(socket);
        Files.writeString(socket, "not a socket");
        assertThatThrownBy(() -> TaskSocket.bind(root, INSIDE))
                .as("anything else at the path is not the relay's, and is left")
                .isInstanceOf(java.io.IOException.class);
        assertThat(socket).as("left as it was").hasContent("not a socket");
    }

    /** A container's {@code /run} as podman makes it: its root's, which no one else writes. */
    private static Path run(final Path root) throws java.io.IOException {
        final Path run = Files.createDirectory(root.resolve("run"));
        Files.setPosixFilePermissions(run, PosixFilePermissions.fromString("rwxr-xr-x"));
        return run;
    }
}
