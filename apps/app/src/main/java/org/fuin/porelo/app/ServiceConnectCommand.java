package org.fuin.porelo.app;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.net.ConnectException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import org.fuin.porelo.wire.RootOwnedFile;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Joins this process's standard input and output to a service the host serves on a Unix socket,
 * named by its role.
 *
 * <p>Run on a host over ssh, once per connection, so a window on another machine carries one
 * service through one ssh channel with no port of its own. It connects as the person, so the gate
 * behind the socket takes the login from the peer's credentials as it does for a local client: it
 * carries no credential, reads no card and resolves no name. Since those credentials are the
 * person's sign-in, a socket is connected to only where root alone could have placed it.
 */
@Command(
        name = "connect",
        mixinStandardHelpOptions = true,
        exitCodeOnInvalidInput = 64,
        description = {
            "Joins standard input and output to a service of this host, for ssh.",
            "Roles: forge-web (/run/porelo-forge/web.sock), forge-git (/run/porelo-forge/git.sock),"
                    + " homeserver (/run/porelo-matrix/client.sock).",
            "Exit 64 for an unknown role; 69 when the host serves none, its socket refuses, or the"
                    + " socket is not in root's directories alone."
        })
public class ServiceConnectCommand implements Callable<Integer> {

    /** The sockets porelo-host serves each role on. */
    static final Map<String, Path> HOST_SOCKETS = hostSockets();

    /** Enough for a whole answer in one read, and small enough not to matter if it is not. */
    private static final int BUFFER = 64 * 1024;

    @Parameters(
            index = "0",
            paramLabel = "ROLE",
            description = "forge-web, forge-git or homeserver.")
    private String role = "";

    @Spec private CommandSpec spec;

    private final Map<String, Path> sockets;

    private final RootOwnedFile.Attributes attributes;

    private final Root root;

    private final InputStream in;

    private final OutputStream out;

    /** For the command line: the host's own sockets, standard input and output. */
    public ServiceConnectCommand() {
        this(
                HOST_SOCKETS,
                path -> Files.readAttributes(path, "unix:uid,mode", LinkOption.NOFOLLOW_LINKS),
                ServiceConnectCommand::rootAsSeenHere,
                System.in,
                System.out);
    }

    /**
     * Constructor.
     *
     * @param sockets Each role's socket.
     * @param attributes Reads a path's owner and mode without following a link.
     * @param root Root's uid as this process sees it.
     * @param in What goes to the service.
     * @param out Where the service's bytes go, and nothing else.
     */
    ServiceConnectCommand(
            final Map<String, Path> sockets,
            final RootOwnedFile.Attributes attributes,
            final Root root,
            final InputStream in,
            final OutputStream out) {
        this.sockets = Map.copyOf(sockets);
        this.attributes = attributes;
        this.root = root;
        this.in = in;
        this.out = out;
    }

    /** Root's uid as this process sees it. */
    @FunctionalInterface
    interface Root {
        /**
         * Returns root's uid.
         *
         * @return It.
         * @throws IOException When root's files cannot be told from another's here.
         */
        int uid() throws IOException;
    }

    @Override
    public Integer call() {
        final PrintWriter err = spec.commandLine().getErr();
        final Path socket = sockets.get(role);
        if (socket == null) {
            return refuse(
                    err,
                    64,
                    "no service role '"
                            + role.replaceAll("\\p{Cntrl}", "?")
                            + "'; the roles are forge-web, forge-git and homeserver");
        }
        try {
            check(socket, root.uid());
        } catch (final NoSuchFileException ex) {
            return refuse(err, 69, role + ": this host serves none: no socket at " + socket);
        } catch (final IOException ex) {
            return refuse(err, 69, role + ": " + ex.getMessage());
        }
        final SocketChannel channel;
        try {
            channel = SocketChannel.open(StandardProtocolFamily.UNIX);
        } catch (final IOException ex) {
            return refuse(err, 70, role + ": " + ex.getMessage());
        }
        try (channel) {
            try {
                channel.connect(UnixDomainSocketAddress.of(socket));
            } catch (final ConnectException ex) {
                return refuse(err, 69, role + ": the socket at " + socket + " refuses connections");
            } catch (final IOException ex) {
                return refuse(
                        err,
                        69,
                        role + ": the socket at " + socket + " refuses: " + ex.getMessage());
            }
            carry(channel, in, out);
            return 0;
        } catch (final IOException ex) {
            return refuse(err, 70, role + ": " + ex.getMessage());
        }
    }

    /**
     * Refuses a socket a stranger could have placed: every directory above it root's and written by
     * root alone, and the socket a socket nobody else may write. Its owner is the service's own
     * account, so only the directory says root put it there.
     */
    private void check(final Path socket, final int rootUid) throws IOException {
        final Map<String, Object> own = attributes.read(socket);
        final int mode = mode(socket, own);
        if ((mode & 0170000) != 0140000) {
            throw new IOException("refusing " + socket + ": not a socket");
        }
        if ((mode & 0002) != 0) {
            throw new IOException("refusing " + socket + ": writable by others");
        }
        for (Path parent = socket.getParent(); parent != null; parent = parent.getParent()) {
            final Map<String, Object> values = attributes.read(parent);
            final int directory = mode(parent, values);
            if ((directory & 0170000) != 0040000) {
                throw new IOException("refusing " + parent + ": not a directory");
            }
            if (!Integer.valueOf(rootUid).equals(values.get("uid"))) {
                throw new IOException("refusing " + parent + ": not owned by root");
            }
            if ((directory & 0022) != 0) {
                throw new IOException("refusing " + parent + ": writable by group or others");
            }
        }
    }

    private static int mode(final Path path, final Map<String, Object> values) throws IOException {
        if (!(values.get("mode") instanceof Number mode)) {
            throw new IOException("refusing " + path + ": its mode cannot be read");
        }
        return mode.intValue();
    }

    /**
     * Copies bytes both ways: the input's end half-closes the socket, so a service waiting for the
     * rest of a request sees its end, and the service's end ends the command. A failure on either
     * way ends it too, as a failure: a request cut short is not a request ended.
     */
    private static void carry(
            final SocketChannel channel, final InputStream in, final OutputStream out)
            throws IOException {
        final OutputStream toService = Channels.newOutputStream(channel);
        final AtomicReference<String> failed = new AtomicReference<>();
        final Thread requests =
                Thread.ofVirtual().start(() -> send(channel, in, toService, failed));
        try {
            final ByteBuffer buffer = ByteBuffer.allocate(BUFFER);
            while (channel.read(buffer) >= 0) {
                buffer.flip();
                try {
                    out.write(
                            buffer.array(),
                            buffer.arrayOffset() + buffer.position(),
                            buffer.remaining());
                    // Every read: a page held back is indistinguishable from a service that
                    // stopped.
                    out.flush();
                } catch (final IOException ex) {
                    throw new IOException("writing standard output failed: " + ex.getMessage(), ex);
                }
                // System.out keeps its failures to itself.
                if (out instanceof PrintStream print && print.checkError()) {
                    throw new IOException("writing standard output failed");
                }
                buffer.clear();
            }
            final String failure = failed.get();
            if (failure != null) {
                throw new IOException(failure);
            }
        } finally {
            requests.interrupt();
        }
    }

    /**
     * Sends the input to the service until its end; on a failure keeps it and stops the service's
     * way too, so the relay does not wait for an answer to a request never finished.
     */
    private static void send(
            final SocketChannel channel,
            final InputStream in,
            final OutputStream toService,
            final AtomicReference<String> failed) {
        final byte[] bytes = new byte[BUFFER];
        try {
            while (true) {
                final int count;
                try {
                    count = in.read(bytes);
                } catch (final IOException ex) {
                    failed.set("reading standard input failed: " + ex.getMessage());
                    return;
                }
                if (count < 0) {
                    return;
                }
                try {
                    toService.write(bytes, 0, count);
                } catch (final IOException ex) {
                    failed.set("sending to the service failed: " + ex.getMessage());
                    return;
                }
            }
        } finally {
            if (failed.get() != null) {
                try {
                    // Unblocks the relay's read, which then finds the failure.
                    channel.shutdownInput();
                } catch (final IOException ex) {
                    // Already closed.
                }
            }
            try {
                channel.shutdownOutput();
            } catch (final IOException ex) {
                // Already closed.
            }
        }
    }

    /**
     * Returns uid 0 only in the initial user namespace, where an owner of 0 is root; in another an
     * unmapped owner reads as the overflow uid and a mapped root is not the host's.
     */
    private static int rootAsSeenHere() throws IOException {
        final String map;
        // Through a stream: procfs reports a size of 0, which a sized read takes as all.
        try (InputStream file = Files.newInputStream(Path.of("/proc/self/uid_map"))) {
            map = new String(file.readAllBytes(), StandardCharsets.US_ASCII);
        }
        final String[] fields = map.strip().split("\\s+");
        if (fields.length == 3
                && "0".equals(fields[0])
                && "0".equals(fields[1])
                && "4294967295".equals(fields[2])) {
            return 0;
        }
        throw new IOException("root's sockets cannot be told from another's in a user namespace");
    }

    private static Map<String, Path> hostSockets() {
        final Map<String, Path> roles = new LinkedHashMap<>();
        roles.put("forge-web", Path.of("/run/porelo-forge/web.sock"));
        roles.put("forge-git", Path.of("/run/porelo-forge/git.sock"));
        roles.put("homeserver", Path.of("/run/porelo-matrix/client.sock"));
        return roles;
    }

    private static int refuse(final PrintWriter err, final int code, final String message) {
        // On standard error, which ssh keeps apart: stdout carries the service's bytes alone.
        err.println("porelo: " + message);
        err.flush();
        return code;
    }
}
