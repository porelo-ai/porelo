package org.fuin.porelo.supervisor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * A destination on this host, reached over a Unix socket: {@code unix:<path>}.
 *
 * <p><strong>Only under the person's own runtime directory.</strong> The gateway hands the
 * destination's key to whatever answers on the socket, so the socket must be one only this person
 * can have bound: a file directly in {@code $XDG_RUNTIME_DIR/porelo/mcp}, a directory of their own
 * that the kernel clears when they log out. A path anywhere else, a relative one, one with a {@code
 * ..}, or one that passes a symbolic link is refused, each with its own {@link Refusal}.
 *
 * <p>The traffic never leaves the host's side of the gateway: the task reaches the gateway's own
 * socket, and the gateway connects to this one. No address is involved, so the firewall has nothing
 * to allow.
 */
public final class UnixUpstream {

    /** What a Unix destination starts with. */
    public static final String SCHEME = "unix:";

    /** The directory under {@code $XDG_RUNTIME_DIR/porelo} a Unix destination must be in. */
    public static final String DIRECTORY = "mcp";

    /** The path every request is sent to on the socket. */
    public static final String PATH = "/mcp";

    /** Letters, digits and {@code . _ - /}: one file, and nothing that could carry a token. */
    private static final Pattern PLAIN = Pattern.compile("[A-Za-z0-9._/-]+");

    /** Why a Unix destination is refused. */
    public enum Refusal {

        /** The path does not start at the root. */
        RELATIVE,

        /** The path holds a {@code ..}. */
        PARENT,

        /** The path holds an empty or {@code .} segment, or ends in a slash. */
        NOT_NORMALIZED,

        /** The path holds a character other than letters, digits and {@code . _ - /}. */
        CHARACTER,

        /** The path is not a file directly in {@code $XDG_RUNTIME_DIR/porelo/mcp}. */
        OUTSIDE,

        /** A part of the path is a symbolic link. */
        LINK
    }

    /** A Unix destination refused, and why. */
    public static final class Refused extends IllegalArgumentException {

        private static final long serialVersionUID = 1L;

        /** Why. */
        private final Refusal reason;

        /**
         * Constructor.
         *
         * @param reason Why.
         * @param message What to say.
         */
        public Refused(Refusal reason, String message) {
            super(message);
            this.reason = reason;
        }

        /**
         * Returns why it was refused.
         *
         * @return The reason.
         */
        public Refusal reason() {
            return reason;
        }
    }

    private UnixUpstream() {}

    /**
     * Says whether a destination is a Unix socket rather than an address.
     *
     * @param upstream The destination.
     * @return Whether it starts with {@code unix:}.
     */
    public static boolean is(String upstream) {
        return upstream.startsWith(SCHEME);
    }

    /**
     * Reads the socket a Unix destination names, checking its form alone.
     *
     * @param upstream The destination, {@code unix:<path>}.
     * @return The socket's path.
     * @throws Refused If the path is relative, holds a {@code ..}, is not normalized or holds
     *     another character.
     */
    public static Path path(String upstream) {
        if (!is(upstream)) {
            throw new IllegalArgumentException("'" + upstream + "' is not a unix: destination");
        }
        final String text = upstream.substring(SCHEME.length());
        if (!text.startsWith("/")) {
            throw new Refused(
                    Refusal.RELATIVE, "the socket in '" + upstream + "' is not an absolute path");
        }
        final String[] segments = text.substring(1).split("/", -1);
        for (final String segment : segments) {
            if (segment.equals("..")) {
                throw new Refused(
                        Refusal.PARENT, "the socket in '" + upstream + "' steps out with '..'");
            }
        }
        if (!PLAIN.matcher(text).matches()) {
            throw new Refused(
                    Refusal.CHARACTER,
                    "the socket in '"
                            + upstream
                            + "' holds a character other than letters, digits and . _ - /");
        }
        for (final String segment : segments) {
            if (segment.isEmpty() || segment.equals(".")) {
                throw new Refused(
                        Refusal.NOT_NORMALIZED,
                        "the socket in '" + upstream + "' is not a normalized path");
            }
        }
        return Path.of(text);
    }

    /**
     * Returns the directory a Unix destination must be in.
     *
     * @param runtime The person's runtime directory, {@code $XDG_RUNTIME_DIR/porelo}.
     * @return {@code $XDG_RUNTIME_DIR/porelo/mcp}.
     */
    public static Path directory(Path runtime) {
        return runtime.toAbsolutePath().normalize().resolve(DIRECTORY);
    }

    /**
     * Reads the socket a Unix destination names, and refuses it unless it is a file directly in the
     * person's own {@code $XDG_RUNTIME_DIR/porelo/mcp} and no part of it is a link.
     *
     * @param upstream The destination, {@code unix:<path>}.
     * @param runtime The person's runtime directory, {@code $XDG_RUNTIME_DIR/porelo}.
     * @return The socket's path.
     * @throws Refused If it is refused, with the reason.
     */
    public static Path checked(String upstream, Path runtime) {
        final Path socket = path(upstream);
        final Path directory = directory(runtime);
        if (!directory.equals(socket.getParent())) {
            throw new Refused(
                    Refusal.OUTSIDE, "the socket in '" + upstream + "' is not in " + directory);
        }
        linkFree(socket);
        return socket;
    }

    /**
     * Refuses a path any existing part of which is a symbolic link, the socket itself included.
     *
     * <p>Asked again right before each connection, since a directory can be replaced between the
     * check and the use.
     *
     * @param socket An absolute path.
     * @throws Refused If a part of it is a link.
     */
    public static void linkFree(Path socket) {
        Path at = socket.getRoot();
        for (final Path part : socket) {
            at = at == null ? part : at.resolve(part);
            if (Files.isSymbolicLink(at)) {
                throw new Refused(
                        Refusal.LINK, "the socket " + socket + " passes the symbolic link " + at);
            }
        }
    }

    /**
     * Returns the request target on the socket for what the task asked: {@link #PATH}, followed by
     * the task's own target, where a bare {@code /} names {@link #PATH} itself.
     *
     * @param target The task's request target, starting with {@code /}.
     * @return The target sent on the socket.
     */
    public static String target(String target) {
        return target.equals("/") || target.startsWith("/?")
                ? PATH + target.substring(1)
                : PATH + target;
    }
}
