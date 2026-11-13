package org.fuin.porelo.supervisor;

import java.io.IOException;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.fuin.porelo.wire.SocketContext;

/**
 * Binds a socket file inside a task's own filesystem, reached through its root from the host side.
 *
 * <p>A socket bind-mounted from the host is a file in the account's directory, which any process of
 * the account can replace before the task starts. One bound inside the task lies in the task's own
 * mount namespace: only the task's root can replace it there, and a process on the host has to join
 * the task first. The relay binds it through {@code /proc/<pid>/root} as the container's root under
 * {@code podman unshare}, so it joins no mount namespace and its own connect still reaches the
 * broker's socket on the host.
 *
 * <p><strong>No link is followed.</strong> The relay is the account itself on the host, and an
 * absolute link inside the container's root resolves against the host's: followed, it would make a
 * directory or a socket wherever it points. Every part of the path below the root is checked not to
 * be a link and to be the task root's alone, since whoever else may write in one could swap it
 * between the check and the bind; a {@code ..} is refused before anything is resolved.
 */
public final class TaskSocket {

    /** The socket: every account in the task may connect, which is how the agent reaches it. */
    private static final String SOCKET_MODE = "rw-rw-rw-";

    /** Its directory: only its owner, the task's root, may add, remove or replace an entry. */
    private static final String DIRECTORY_MODE = "rwxr-xr-x";

    /** {@code S_IFSOCK}, the file type of a socket in {@code unix:mode}. */
    private static final int SOCKET_TYPE = 0140000;

    /** {@code S_IFMT}, the file type bits of {@code unix:mode}. */
    private static final int TYPE_BITS = 0170000;

    /** Write permission for its group or others in {@code unix:mode}. */
    private static final int SHARED_WRITE = 022;

    /** This process, whose owner is the task's root where the relay runs. */
    private static final Path SELF = Path.of("/proc/self");

    private TaskSocket() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Binds a socket at a path inside a root, making its directory where it is missing.
     *
     * <p>A socket already at the path is one an earlier relay left, and is replaced; anything else
     * there is refused and left as it is.
     *
     * @param root The task's root as the host reaches it, {@code /proc/<pid>/root}.
     * @param inside Absolute path of the socket inside the task.
     * @return The bound channel, labeled where the policy allows it.
     * @throws IOException If a part of the path is a link or writable by others, something that is
     *     not a socket stands at the path, or the bind fails.
     */
    public static ServerSocketChannel bind(final Path root, final String inside)
            throws IOException {
        final Path relative = Path.of(inside);
        if (!relative.isAbsolute() || relative.getNameCount() < 2) {
            throw new IOException(
                    "'" + inside + "' is not an absolute path to a socket in a directory");
        }
        for (final Path name : relative) {
            // Resolved against the container's root as plain names, a '..' would climb out of it.
            if (".".equals(name.toString()) || "..".equals(name.toString())) {
                throw new IOException("'" + inside + "' names '" + name + "', refused");
            }
        }
        Path directory = root;
        final Path parent =
                java.util.Objects.requireNonNull(
                        relative.getParent(), "an absolute path of two names has a parent");
        for (int part = 0; part < parent.getNameCount(); part++) {
            directory = directory.resolve(parent.getName(part).toString());
            final String shown = "/" + parent.subpath(0, part + 1);
            final boolean last = part == parent.getNameCount() - 1;
            if (last) {
                make(directory, shown);
            }
            held(directory, shown, last);
        }
        final Path socket = directory.resolve(relative.getFileName().toString());
        clear(socket, inside);
        final ServerSocketChannel server = SocketContext.openUnixSocket();
        try {
            server.bind(UnixDomainSocketAddress.of(socket));
            Files.setPosixFilePermissions(socket, PosixFilePermissions.fromString(SOCKET_MODE));
            return server;
        } catch (IOException ex) {
            server.close();
            throw ex;
        }
    }

    private static void make(final Path directory, final String shown) throws IOException {
        try {
            Files.createDirectory(directory);
        } catch (FileAlreadyExistsException ex) {
            // An image's own, or an earlier relay's: held to the same checks below.
            if (Files.isSymbolicLink(directory)) {
                throw new IOException(shown + " in the task is a link, refused");
            }
        }
    }

    private static void held(final Path directory, final String shown, final boolean socket)
            throws IOException {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(shown + " in the task is a link or not a directory, refused");
        }
        // Every directory on the way is the task root's alone: whoever else may rename in one
        // could swap what lies below it for a link, or move the socket's directory aside.
        final Object owner = Files.getAttribute(directory, "unix:uid", LinkOption.NOFOLLOW_LINKS);
        if (!owner.equals(Files.getAttribute(SELF, "unix:uid"))) {
            throw new IOException(
                    shown
                            + " in the task is not its root's, so the agent could replace what"
                            + " lies below it, refused");
        }
        if (socket) {
            // Its own, made or found: only its owner adds, removes or replaces an entry.
            Files.setPosixFilePermissions(
                    directory, PosixFilePermissions.fromString(DIRECTORY_MODE));
            return;
        }
        final int mode =
                (Integer) Files.getAttribute(directory, "unix:mode", LinkOption.NOFOLLOW_LINKS);
        if ((mode & SHARED_WRITE) != 0) {
            throw new IOException(
                    shown
                            + " in the task may be written by its group or others, who could swap"
                            + " what lies below it, refused");
        }
    }

    private static void clear(final Path socket, final String inside) throws IOException {
        if (!Files.exists(socket, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        final int mode =
                (Integer) Files.getAttribute(socket, "unix:mode", LinkOption.NOFOLLOW_LINKS);
        if ((mode & TYPE_BITS) != SOCKET_TYPE) {
            throw new IOException(inside + " in the task is not a socket, left as it is");
        }
        Files.delete(socket);
    }
}
