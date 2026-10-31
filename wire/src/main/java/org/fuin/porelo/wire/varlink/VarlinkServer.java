package org.fuin.porelo.wire.varlink;

import java.io.IOException;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.fuin.porelo.wire.SocketContext;
import org.jspecify.annotations.Nullable;

/**
 * Serves varlink methods over a unix socket.
 *
 * <p>Implements {@code org.varlink.service.GetInfo} itself, because that is what makes a service
 * introspectable by {@code varlinkctl} and by anything else that speaks the protocol - which in
 * turn is what makes an independent conformance check possible without writing one.
 */
public class VarlinkServer implements AutoCloseable, Runnable {

    @FunctionalInterface
    interface UidReader {
        long read() throws IOException;
    }

    /** Kernel credentials and account authorization for one connection. */
    public record Peer(long uid, long pid, boolean sameUser) {}

    /** Reads a connected socket's credentials, refusing if they cannot be established. */
    @FunctionalInterface
    public interface PeerReader {
        /** Reads this connection's peer. */
        Peer read(SocketChannel channel) throws IOException;
    }

    /** Handles one method call. */
    @FunctionalInterface
    public interface Method {

        /**
         * Runs the method.
         *
         * @param parameters Call parameters.
         * @param replies Where to send replies; call it more than once only for a streaming method.
         * @throws IOException If a reply cannot be sent.
         */
        void call(Map<String, Object> parameters, Replies replies) throws IOException;
    }

    /** Sends replies for one call. */
    public interface Replies {

        /**
         * Sends a reply that is not the last one.
         *
         * @param parameters Reply parameters.
         * @throws IOException If sending fails.
         */
        void more(Map<String, Object> parameters) throws IOException;

        /**
         * Sends the final reply.
         *
         * @param parameters Reply parameters.
         * @throws IOException If sending fails.
         */
        void last(Map<String, Object> parameters) throws IOException;

        /**
         * Tells whether the caller asked for a stream.
         *
         * @return {@code true} if the call carried {@code more}.
         */
        boolean streaming();

        /**
         * Tells whether the client is still there, without waiting.
         *
         * <p>A stream that sends only when something changes would otherwise notice a client had
         * gone only when it next sent - and a client that reconnected left its old streams asking
         * podman every half second for the daemon's life. Such a stream asks this between its
         * waits, and ends when it says no.
         *
         * @return Whether it is.
         */
        boolean open();
    }

    private final Path socket;

    private final String interfaceName;

    private final Map<String, Method> methods = new ConcurrentHashMap<>();

    /** The IDL this service serves, or {@code null} until one is given. */
    private volatile @Nullable String description;

    /** What {@code GetInfo} reports as the version. */
    private volatile String version = "unknown";

    private final ServerSocketChannel channel;

    private final PeerReader peerReader;

    private volatile boolean running = true;

    /**
     * Binds the socket.
     *
     * @param socket Where to create it.
     * @param interfaceName Interface this service implements, for {@code GetInfo}.
     * @throws VarlinkException If the socket cannot be created.
     */
    public VarlinkServer(Path socket, String interfaceName) {
        this(socket, interfaceName, null);
    }

    /** Binds a socket using the supplied kernel credential reader. */
    public VarlinkServer(Path socket, String interfaceName, @Nullable PeerReader peerReader) {
        this(socket, interfaceName, peerReader, VarlinkServer::ownUser);
    }

    /**
     * Returns the directory holding a socket as a path whose last name is that directory's own: a
     * trailing {@code .} is dropped, so a check without following links looks at the directory
     * itself and not through it.
     *
     * @param given The socket, as given.
     * @return Its directory, absolute but not normalized: a link's {@code ..} stays the kernel's.
     * @throws IOException If the path ends its directory in {@code ..}, which names another one.
     */
    public static Path directoryOf(Path given) throws IOException {
        // Read again from its text: a path made from a link's target keeps that target's slashes.
        final Path socket = given.getFileSystem().getPath(given.toString());
        final Path name = socket.getFileName();
        if (name == null
                || name.toString().isEmpty()
                || name.toString().equals(".")
                || name.toString().equals("..")) {
            throw new IOException("The socket must be named");
        }
        Path parent = socket.toAbsolutePath().getParent();
        while (parent != null
                && parent.getFileName() != null
                && parent.getFileName().toString().equals(".")) {
            parent = parent.getParent();
        }
        if (parent == null
                || parent.getFileName() != null && parent.getFileName().toString().equals("..")) {
            throw new IOException("The socket's directory must be named, not reached by ..");
        }
        return parent;
    }

    /**
     * Refuses a socket directory another account could rename out of the way: every directory the
     * kernel passes on the way to it, and every one above those, must be this account's or root's,
     * and none may be written by others without the sticky bit. The path is followed as the kernel
     * follows it - {@code ..} from where a link led, each link by its own target, name by name, at
     * most 40 links - so every directory passed is judged, those a link's target passes too. A link
     * must be this account's or root's, and lead somewhere; below a name that does not exist yet
     * only names follow, no {@code ..}. Group write is allowed on this account's own directory
     * under a group of its own, as a umask of 002 makes it, and on root's under root's group.
     *
     * <p>An owner neither this account nor root is refused, also where a user namespace shows an
     * unmapped owner as overflowuid: that is every other account of the host too.
     *
     * @param parent The socket's directory, which need not exist yet.
     * @param ownUid This account.
     */
    static void refuseForeignAbove(@Nullable Path parent, long ownUid) throws IOException {
        final Path above = parent == null ? null : parent.toAbsolutePath().getParent();
        if (above == null) {
            return;
        }
        final java.util.Set<Path> judged = new java.util.LinkedHashSet<>();
        final Path root = java.util.Objects.requireNonNull(above.getRoot());
        judged.add(root);
        try {
            walk(root, above, judged, ownUid, new int[] {0}, false);
            for (final Path each : judged) {
                judge(each, ownUid);
            }
        } catch (java.nio.file.FileSystemException ex) {
            // Its message names where a link led, which may be another account's.
            throw new IOException("A directory on the way to the socket cannot be read");
        }
    }

    /**
     * Follows names from a directory reached without a link, as the kernel does: {@code ..} from
     * there, each link by its own target, name by name. Every directory reached is put into {@code
     * judged}, so the walk never needs a resolved path and its jumps.
     *
     * @return Where the names lead, or {@code null} when one of them does not exist yet.
     */
    private static @Nullable Path walk(
            Path from,
            Path names,
            java.util.Set<Path> judged,
            long ownUid,
            int[] links,
            boolean fromLink)
            throws IOException {
        Path current = from;
        boolean missing = false;
        for (final Path name : names) {
            // A link's target keeps its slashes in each name ("../"), as readlink gave them.
            final String step = name.toString().replaceAll("/+$", "");
            // A target is read as bytes; one that does not read back as text is not followed blind.
            if (fromLink && step.indexOf('\uFFFD') >= 0) {
                throw new IOException("A name on the way to the socket is not readable as text");
            }
            if (missing) {
                if (step.equals("..")) {
                    throw new IOException(
                            "The socket's directory must be made by names alone below one that"
                                    + " does not exist");
                }
                continue;
            }
            if (step.equals(".")) {
                continue;
            }
            if (step.equals("..")) {
                current = current.getParent() == null ? current : current.getParent();
                continue;
            }
            final Path next = current.resolve(step);
            if (Files.isSymbolicLink(next)) {
                if (++links[0] > 40) {
                    throw new IOException("Too many links on the way to the socket");
                }
                final long owner =
                        Integer.toUnsignedLong(
                                ((Number)
                                                Files.getAttribute(
                                                        next,
                                                        "unix:uid",
                                                        java.nio.file.LinkOption.NOFOLLOW_LINKS))
                                        .intValue());
                if (owner != ownUid && owner != 0) {
                    throw new IOException(
                            "A link on the way to the socket must belong to this uid");
                }
                final Path target = Files.readSymbolicLink(next);
                final Path start =
                        target.isAbsolute()
                                ? java.util.Objects.requireNonNull(target.getRoot())
                                : current;
                judged.add(start);
                final Path reached = walk(start, target, judged, ownUid, links, true);
                if (reached == null) {
                    throw new IOException("A link on the way to the socket leads nowhere");
                }
                current = reached;
                continue;
            }
            if (!Files.isDirectory(next, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                // The rest is made here, private; the check after making it judges it.
                missing = true;
                continue;
            }
            current = next;
            judged.add(current);
        }
        return missing ? null : current;
    }

    private static void judge(Path directory, long ownUid) throws IOException {
        // Not followed: a directory the walk reached that is a link now was swapped meanwhile.
        final java.nio.file.LinkOption here = java.nio.file.LinkOption.NOFOLLOW_LINKS;
        if (Files.isSymbolicLink(directory)) {
            throw new IOException("A directory on the way to the socket became a link");
        }
        final java.nio.file.attribute.PosixFileAttributes attributes =
                Files.readAttributes(
                        directory, java.nio.file.attribute.PosixFileAttributes.class, here);
        final long owner =
                Integer.toUnsignedLong(
                        ((Number) Files.getAttribute(directory, "unix:uid", here)).intValue());
        final long group =
                Integer.toUnsignedLong(
                        ((Number) Files.getAttribute(directory, "unix:gid", here)).intValue());
        final int mode = ((Number) Files.getAttribute(directory, "unix:mode", here)).intValue();
        if (owner != ownUid && owner != 0) {
            throw new IOException("The directory above the socket's must belong to this uid");
        }
        final boolean sticky = (mode & 01000) != 0;
        final boolean groupOfItsOwn =
                owner == 0
                        ? group == 0
                        : attributes.group().getName().equals(attributes.owner().getName());
        if (!sticky && ((mode & 0002) != 0 || (mode & 0020) != 0 && !groupOfItsOwn)) {
            throw new IOException(
                    "The directory above the socket's must not be writable by others");
        }
    }

    VarlinkServer(
            Path socket, String interfaceName, @Nullable PeerReader peerReader, UidReader ownUid) {
        this.socket = socket;
        this.interfaceName = interfaceName;
        try {
            final Path parent = directoryOf(socket);
            refuseForeignAbove(parent, ownUid.read());
            Files.createDirectories(
                    parent,
                    PosixFilePermissions.asFileAttribute(
                            PosixFilePermissions.fromString("rwx------")));
            // Again, now that every directory exists: one put there meanwhile is judged too.
            refuseForeignAbove(parent, ownUid.read());
            if (Files.isSymbolicLink(parent)) {
                throw new IOException("The socket directory must not be a symbolic link");
            }
            if (Integer.toUnsignedLong(
                            ((Number)
                                            Files.getAttribute(
                                                    parent,
                                                    "unix:uid",
                                                    java.nio.file.LinkOption.NOFOLLOW_LINKS))
                                    .intValue())
                    != ownUid.read()) {
                throw new IOException("The socket directory must belong to this uid");
            }
            final var owner = Files.getOwner(parent, java.nio.file.LinkOption.NOFOLLOW_LINKS);
            this.peerReader =
                    peerReader != null
                            ? peerReader
                            : client ->
                                    new Peer(
                                            -1,
                                            -1,
                                            client.getOption(
                                                            jdk.net.ExtendedSocketOptions
                                                                    .SO_PEERCRED)
                                                    .user()
                                                    .equals(owner));
            Files.setPosixFilePermissions(parent, PosixFilePermissions.fromString("rwx------"));
            if (Files.isSymbolicLink(socket)) {
                throw new IOException("The socket must not be a symbolic link");
            }
            refuseToStealFrom(socket);
            Files.deleteIfExists(socket);
            // Labelled as it is created: SELinux checks connectto against the socket, not the file.
            channel = SocketContext.openUnixSocket();
            channel.bind(UnixDomainSocketAddress.of(socket));
            Files.setPosixFilePermissions(socket, PosixFilePermissions.fromString("rwx------"));
        } catch (IOException ex) {
            throw new VarlinkException("Cannot create the varlink socket at " + socket, ex);
        }
        methods.put(
                "org.varlink.service.GetInfo",
                (parameters, replies) -> {
                    final Map<String, Object> info = new LinkedHashMap<>();
                    info.put("vendor", "fuin.org");
                    info.put("product", "Porelo");
                    info.put("version", version);
                    info.put("url", "https://github.com/porelo-ai/porelo");
                    info.put("interfaces", List.of("org.varlink.service", interfaceName));
                    replies.last(info);
                });
        methods.put(
                "org.varlink.service.GetInterfaceDescription",
                (parameters, replies) -> {
                    final Object wanted = parameters.get("interface");
                    final String idl = description;
                    if (idl == null || !interfaceName.equals(wanted)) {
                        throw new VarlinkException(
                                "org.varlink.service.InterfaceNotFound",
                                Map.of("interface", String.valueOf(wanted)));
                    }
                    replies.last(Map.of("description", idl));
                });
    }

    private static long ownUser() throws IOException {
        // A nondumpable process's /proc entries can be owned by root instead of its uid.
        for (String line : Files.readAllLines(Path.of("/proc/self/status"))) {
            if (line.startsWith("Uid:")) {
                return Long.parseLong(line.trim().split("\\s+")[1]);
            }
        }
        throw new IOException("Cannot establish this process's uid");
    }

    /**
     * Gives this service the version to report.
     *
     * <p>Not read from anywhere here: {@code wire} is a library and does not know which product it
     * has been built into. It was a literal until a client had a reason to ask - a frontend
     * connecting to whichever daemon happens to be installed needs to know which one that is.
     *
     * @param value Version string, as a build produced it.
     * @return This instance.
     */
    public VarlinkServer reporting(String value) {
        this.version = value;
        return this;
    }

    /**
     * Gives this service its interface description.
     *
     * <p>Served verbatim, because the point of it is that a client can read the contract off a
     * running daemon rather than off a copy that may be older than what is answering. A service
     * without one answers {@code InterfaceNotFound}, which is what a caller has to handle anyway.
     *
     * @param idl The interface definition, in varlink's own syntax.
     * @return This instance.
     */
    public VarlinkServer describedBy(String idl) {
        this.description = idl;
        return this;
    }

    /**
     * Registers a method.
     *
     * @param name Method name without the interface prefix.
     * @param method What to run.
     * @return This instance.
     */
    public VarlinkServer method(String name, Method method) {
        methods.put(interfaceName + "." + name, method);
        return this;
    }

    /**
     * Returns every method this service answers, fully qualified.
     *
     * <p>For a test that holds the registrations against the interface description: a method that
     * exists and is not described is one an interface cannot know about, and a described method
     * that does not exist is one a client will call and be refused.
     *
     * @return Method names, including the {@code org.varlink.service} ones.
     */
    public java.util.Set<String> methodNames() {
        return java.util.Set.copyOf(methods.keySet());
    }

    /**
     * Returns the socket path.
     *
     * @return Path.
     */
    public Path socketPath() {
        return socket;
    }

    @Override
    public void run() {
        while (running) {
            try {
                final SocketChannel client = channel.accept();
                // A platform thread per connection unless told otherwise: in the native binary a
                // virtual thread blocked reading a call now and then never woke, and the caller
                // waited until its own limit
                // - an agent that
                // answered in a tenth of a second, and once a daemon for four minutes. Connections
                // are few: a lookup's one, an interface's streams. Switchable, so one binary is
                // measured both ways.
                ServingThreads.start("varlink-connection", () -> serve(client));
            } catch (IOException ex) {
                if (running) {
                    throw new VarlinkException("The varlink socket failed", ex);
                }
                return;
            }
        }
    }

    private void serve(SocketChannel client) {
        try (VarlinkConnection connection = new VarlinkConnection(client)) {
            final Peer peer = peerReader.read(client);
            if (!peer.sameUser()) {
                return;
            }
            while (running) {
                final Map<String, Object> call = connection.receive();
                if (call == null) {
                    return;
                }
                handle(connection, call, peer);
            }
        } catch (IOException | RuntimeException ex) {
            // One broken client does not take the service down.
        }
    }

    @SuppressWarnings("unchecked")
    private void handle(VarlinkConnection connection, Map<String, Object> call, Peer peer)
            throws IOException {

        final Object name = call.get("method");
        if (!(name instanceof String methodName)) {
            error(
                    connection,
                    "org.varlink.service.InvalidParameter",
                    Map.of("parameter", "method"));
            return;
        }
        final Method method = methods.get(methodName);
        if (method == null) {
            error(connection, "org.varlink.service.MethodNotFound", Map.of("method", methodName));
            return;
        }

        final Object raw = call.get("parameters");
        final Map<String, Object> parameters =
                raw instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        final boolean more = Boolean.TRUE.equals(call.get("more"));

        try {
            method.call(
                    parameters,
                    new Replies() {

                        @Override
                        public void more(Map<String, Object> values) throws IOException {
                            final Map<String, Object> reply = new LinkedHashMap<>();
                            reply.put("parameters", values);
                            reply.put("continues", Boolean.TRUE);
                            connection.send(reply);
                        }

                        @Override
                        public void last(Map<String, Object> values) throws IOException {
                            connection.send(Map.of("parameters", values));
                        }

                        @Override
                        public boolean streaming() {
                            return more;
                        }

                        private boolean asked;

                        @Override
                        public boolean open() {
                            // The first time always: a client that has said all it will say -
                            // 'porelo daemon connect' ends its half when its input ends - reads
                            // exactly like one that has gone, and still hears what is open now.
                            // After that, the end of its half ends the stream.
                            if (!asked) {
                                asked = true;
                                return true;
                            }
                            return !connection.peerGone();
                        }
                    });
        } catch (VarlinkException ex) {
            error(
                    connection,
                    ex.getErrorName() == null ? interfaceName + ".Failed" : ex.getErrorName(),
                    ex.getParameters() == null ? Map.of() : ex.getParameters());
        } catch (RuntimeException ex) {
            error(
                    connection,
                    interfaceName + ".Failed",
                    Map.of("message", String.valueOf(ex.getMessage())));
        }
    }

    private static void error(
            VarlinkConnection connection, String name, Map<String, Object> parameters)
            throws IOException {
        final Map<String, Object> message = new LinkedHashMap<>();
        message.put("error", name);
        message.put("parameters", parameters);
        connection.send(message);
    }

    /**
     * Refuses to bind over a socket something else is still answering on.
     *
     * <p><strong>Unlinking it unconditionally is worse than it looks.</strong> The file is only a
     * name: unlinking it does not stop the process holding the bound socket, it makes that process
     * unreachable. A second start therefore used to leave the first one running, serving nobody,
     * with nothing anywhere saying so - and the operator's own daemon was the one that disappeared.
     *
     * <p>The test is a connection, because nothing else distinguishes the two cases. A socket file
     * whose process is gone refuses the connection, and that one is stale and ours to remove; one
     * that accepts belongs to somebody who is still using it.
     *
     * @param socket The path about to be bound.
     * @throws VarlinkException If something is listening there.
     */
    private static void refuseToStealFrom(Path socket) {
        try {
            org.fuin.porelo.wire.LiveSocket.refuseToStealFrom(socket, "server");
        } catch (IOException ex) {
            throw new VarlinkException(
                    ex.getMessage() != null ? ex.getMessage() : ex.toString(), ex);
        }
    }

    @Override
    public void close() {
        running = false;
        try {
            channel.close();
        } catch (IOException ex) {
            // Shutting down.
        }
        try {
            Files.deleteIfExists(socket);
        } catch (IOException ex) {
            // The next start unlinks first.
        }
    }
}
