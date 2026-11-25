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
import org.fuin.porelo.wire.Directories;
import org.fuin.porelo.wire.SocketContext;
import org.jspecify.annotations.Nullable;

/**
 * Serves varlink methods over a unix socket.
 *
 * <p>Implements {@code org.varlink.service.GetInfo} itself, because that is what makes a service
 * introspectable by {@code varlinkctl} and by anything else that speaks the protocol - which in
 * turn is what makes an independent conformance check possible without writing one.
 *
 * <p>One connection's calls run one at a time, on the connection's own thread. A method registered
 * with {@link #methodEndingWithItsCaller(String, Method)} is watched while it runs: when its
 * caller's side of the connection ends, its thread is interrupted, what it throws then is not sent,
 * and the connection is closed. A call that has ended is never told, and an answer that finds the
 * connection closed is dropped, as for every other method.
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

    /**
     * A registered method, and whether it ends with its caller: kept as one value, so a method
     * registered again never runs with the flag of the one it replaced.
     */
    private record Registered(Method method, boolean endingWithCaller) {}

    /** The methods, fully qualified. */
    private final Map<String, Registered> methods = new ConcurrentHashMap<>();

    private static final String SERVICE = "org.varlink.service";

    /** Each further interface served, by name, with its IDL, in the order given. */
    private final Map<String, String> further = new LinkedHashMap<>();

    /** The IDL this service serves, or {@code null} until one is given. */
    private volatile @Nullable String description;

    /** What {@code GetInfo} reports as the version. */
    private volatile String version = "unknown";

    private final ServerSocketChannel channel;

    private final PeerReader peerReader;

    private volatile boolean running = true;

    /** Where this server reports what goes wrong beyond a call's answer: its standard error. */
    private volatile java.util.function.Consumer<String> log = System.err::println;

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
            Directories.create(
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
                new Registered(
                        (parameters, replies) -> {
                            final Map<String, Object> info = new LinkedHashMap<>();
                            info.put("vendor", "fuin.org");
                            info.put("product", "Porelo");
                            info.put("version", version);
                            info.put("url", "https://github.com/porelo-ai/porelo");
                            final List<String> interfaces = new java.util.ArrayList<>();
                            interfaces.add(SERVICE);
                            interfaces.add(interfaceName);
                            synchronized (further) {
                                interfaces.addAll(further.keySet());
                            }
                            info.put("interfaces", List.copyOf(interfaces));
                            replies.last(info);
                        },
                        false));
        methods.put(
                "org.varlink.service.GetInterfaceDescription",
                new Registered(
                        (parameters, replies) -> {
                            final Object wanted = parameters.get("interface");
                            final @Nullable String idl;
                            if (interfaceName.equals(wanted)) {
                                idl = description;
                            } else {
                                synchronized (further) {
                                    idl = further.get(wanted);
                                }
                            }
                            if (idl == null) {
                                throw new VarlinkException(
                                        "org.varlink.service.InterfaceNotFound",
                                        Map.of("interface", String.valueOf(wanted)));
                            }
                            replies.last(Map.of("description", idl));
                        },
                        false));
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
     * Sends what this server must report about itself, an error in a call above all, somewhere else
     * than its standard error, which a daemon's log reads.
     *
     * @param log Takes each line.
     * @return This instance.
     */
    public VarlinkServer reportingTo(java.util.function.Consumer<String> log) {
        this.log = log;
        return this;
    }

    /**
     * Registers a method under the first interface, the one the constructor names.
     *
     * @param name Method name without the interface prefix.
     * @param method What to run.
     * @return This instance.
     */
    public VarlinkServer method(String name, Method method) {
        methods.put(interfaceName + "." + name, new Registered(method, false));
        return this;
    }

    /**
     * Serves a further interface on this socket: {@code GetInfo} names it after the first, and
     * {@code GetInterfaceDescription} answers its description.
     *
     * <p>So that one executable can answer two contracts on one socket, such as a forge adapter's
     * build and review interfaces. Its methods are registered with {@link #method(String, String,
     * Method)}; a failure in one of them is answered as that interface's {@code Failed}.
     *
     * <p>Given again with the same name, the description given last replaces the earlier one.
     *
     * @param name The interface's full name; neither the first one nor {@code org.varlink.service}.
     * @param idl Its interface definition, in varlink's own syntax, served verbatim; its {@code
     *     interface} line names {@code name}.
     * @return This instance.
     * @throws IllegalArgumentException If the name is the first interface's or the service's own,
     *     or the definition declares another interface or none.
     */
    public VarlinkServer serving(String name, String idl) {
        if (name.equals(interfaceName) || name.equals(SERVICE)) {
            throw new IllegalArgumentException(
                    name + " is served already; a further interface needs a name of its own");
        }
        final String declared = declaredName(idl);
        if (!name.equals(declared)) {
            throw new IllegalArgumentException(
                    "The description given for "
                            + name
                            + " declares "
                            + (declared == null ? "no interface" : "the interface " + declared));
        }
        synchronized (further) {
            further.put(name, idl);
        }
        return this;
    }

    /**
     * Registers a method under a named interface: the first one, or one given to {@link
     * #serving(String, String)}.
     *
     * @param iface The interface's full name.
     * @param name Method name without the interface prefix.
     * @param method What to run.
     * @return This instance.
     * @throws IllegalArgumentException If this server does not serve that interface.
     */
    public VarlinkServer method(String iface, String name, Method method) {
        methods.put(served(iface) + "." + name, new Registered(method, false));
        return this;
    }

    /** Returns the interface if this server serves it, and refuses it otherwise. */
    private String served(String iface) {
        if (iface.equals(interfaceName)) {
            return iface;
        }
        synchronized (further) {
            if (!further.containsKey(iface)) {
                throw new IllegalArgumentException(
                        "This server does not serve " + iface + "; give it with serving first");
            }
        }
        return iface;
    }

    /**
     * Returns the name an interface definition gives in its {@code interface} line: the first line
     * that is neither blank nor a comment.
     */
    private static @Nullable String declaredName(String idl) {
        for (final String line : idl.split("\\R")) {
            final String text = line.strip();
            if (text.isEmpty() || text.startsWith("#")) {
                continue;
            }
            final String[] words = text.split("\\s+");
            return words.length == 2 && words[0].equals("interface") ? words[1] : null;
        }
        return null;
    }

    /** The interface whose {@code Failed} answers a failure in this method. */
    private String interfaceOf(String methodName) {
        final int dot = methodName.lastIndexOf('.');
        final String iface = dot < 0 ? interfaceName : methodName.substring(0, dot);
        synchronized (further) {
            return further.containsKey(iface) ? iface : interfaceName;
        }
    }

    /**
     * Registers a method that ends with its caller: while it runs, its connection is watched, and
     * when the caller's side ends - the client closed, or ended its half - before the method has
     * returned, the method's thread is interrupted. A program it runs and waits for is ended by
     * that, and what it throws then is never sent: the caller is not there to read it. One line in
     * this server's log says the caller went.
     *
     * <p>For a long reading nobody waits for once its caller has gone, such as a review. Not for a
     * method that must run to its end once asked, nor for one a script asks through a bridge that
     * ends its half when its input ends: to this server that reads as a caller that went.
     *
     * @param name Method name without the interface prefix.
     * @param method What to run.
     * @return This instance.
     */
    public VarlinkServer methodEndingWithItsCaller(String name, Method method) {
        methods.put(interfaceName + "." + name, new Registered(method, true));
        return this;
    }

    /**
     * Registers a method that ends with its caller, as {@link #methodEndingWithItsCaller(String,
     * Method)} does, under a named interface: the first one, or one given to {@link
     * #serving(String, String)}.
     *
     * @param iface The interface's full name.
     * @param name Method name without the interface prefix.
     * @param method What to run.
     * @return This instance.
     * @throws IllegalArgumentException If this server does not serve that interface.
     */
    public VarlinkServer methodEndingWithItsCaller(String iface, String name, Method method) {
        methods.put(served(iface) + "." + name, new Registered(method, true));
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
                if (!handle(connection, call, peer)) {
                    // An error in a call leaves nothing on this connection to trust.
                    return;
                }
            }
        } catch (IOException | RuntimeException ex) {
            // One broken client does not take the service down.
        } catch (Error ex) {
            report(interfaceName, "serving a connection", ex);
            throw ex;
        }
    }

    /** Says in the log what went wrong beyond a call's answer. */
    private void report(final String iface, final String doing, final Throwable ex) {
        try {
            log.accept(iface + ": " + doing + " failed: " + text(ex));
        } catch (RuntimeException | Error lost) {
            // The log is where this would have gone; nothing is left to say it with.
        }
    }

    /**
     * What a failure says of itself: its message, or its class's name when it has none, so a Failed
     * never goes out empty.
     */
    static String text(final Throwable ex) {
        final String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return ex.getClass().getName();
        }
        return ex instanceof Error ? ex.getClass().getName() + ": " + message : message;
    }

    /**
     * The parameters of a Failed: those given, with a {@code message} added from the failure when
     * they hold none.
     */
    private static Map<String, Object> failedParameters(
            final @Nullable Map<String, Object> given, final Throwable ex) {
        final Map<String, Object> parameters = new LinkedHashMap<>();
        if (given != null) {
            parameters.putAll(given);
        }
        final Object message = parameters.get("message");
        if (!(message instanceof String said) || said.isBlank()) {
            parameters.put("message", text(ex));
        }
        return parameters;
    }

    /**
     * Answers one call.
     *
     * @return Whether the connection serves further calls: not after an error in the call.
     */
    @SuppressWarnings("unchecked")
    private boolean handle(VarlinkConnection connection, Map<String, Object> call, Peer peer)
            throws IOException {

        final Object name = call.get("method");
        if (!(name instanceof String methodName)) {
            error(
                    connection,
                    "org.varlink.service.InvalidParameter",
                    Map.of("parameter", "method"));
            return true;
        }
        final Registered registered = methods.get(methodName);
        if (registered == null) {
            error(connection, "org.varlink.service.MethodNotFound", Map.of("method", methodName));
            return true;
        }

        final Object raw = call.get("parameters");
        final Map<String, Object> parameters =
                raw instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        final boolean more = Boolean.TRUE.equals(call.get("more"));

        final Method method = registered.method();
        if (!registered.endingWithCaller()) {
            return answer(connection, methodName, method, parameters, more, null);
        }
        final CallerWatch watch = CallerWatch.start(connection);
        final boolean serves;
        try {
            serves = answer(connection, methodName, method, parameters, more, watch);
        } catch (IOException | RuntimeException ex) {
            if (!watch.end()) {
                throw ex;
            }
            callerWent(methodName);
            return false;
        }
        if (watch.end()) {
            callerWent(methodName);
            return false;
        }
        return serves;
    }

    /** Says in the log, once, that a call ended with its caller, which is no failure. */
    private void callerWent(final String methodName) {
        try {
            log.accept(methodName + ": the caller went, and the call was stopped");
        } catch (RuntimeException | Error lost) {
            // The log is where this would have gone; nothing is left to say it with.
        }
    }

    /**
     * Runs one call's method and sends its answer.
     *
     * @param watch The watch of its caller, or {@code null} for a method that does not end with it.
     * @return Whether the connection serves further calls: not after an error in the call, nor
     *     after its caller went.
     */
    private boolean answer(
            final VarlinkConnection connection,
            final String methodName,
            final Method method,
            final Map<String, Object> parameters,
            final boolean more,
            final @Nullable CallerWatch watch)
            throws IOException {
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
                            // Ended before the answer goes, so a caller that reads it and leaves
                            // at once is not taken for one that went during the call.
                            if (watch != null && watch.end()) {
                                return;
                            }
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
                            // A watched call's connection is read by its watch alone.
                            return watch == null ? !connection.peerGone() : !watch.callerGone();
                        }
                    });
        } catch (VarlinkException ex) {
            if (watch != null && watch.end()) {
                // Nobody is there to read what it says.
                return false;
            }
            final String failed = interfaceOf(methodName) + ".Failed";
            final String errorName = ex.getErrorName() == null ? failed : ex.getErrorName();
            error(
                    connection,
                    errorName,
                    failed.equals(errorName)
                            ? failedParameters(ex.getParameters(), ex)
                            : ex.getParameters() == null ? Map.of() : ex.getParameters());
        } catch (RuntimeException ex) {
            if (watch != null && watch.end()) {
                return false;
            }
            error(connection, interfaceOf(methodName) + ".Failed", failedParameters(null, ex));
        } catch (Error ex) {
            report(interfaceOf(methodName), "the call " + methodName, ex);
            try {
                error(connection, interfaceOf(methodName) + ".Failed", failedParameters(null, ex));
            } catch (IOException | RuntimeException | Error lost) {
                // Said in the log; the caller hears the connection end.
            }
            return false;
        }
        return true;
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
