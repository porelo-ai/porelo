package org.fuin.porelo.core.process;

import java.io.IOException;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import jdk.net.ExtendedSocketOptions;
import org.fuin.porelo.wire.varlink.VarlinkServer;

/** Reads Linux peer credentials without trusting anything sent by the client. */
public final class SocketPeer {
    private static final Linker LINKER = Linker.nativeLinker();
    private static final MethodHandle GETUID =
            LINKER.downcallHandle(
                    LINKER.defaultLookup().find("getuid").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT));

    private SocketPeer() {}

    /**
     * Returns whether the kernel says the process at the other end runs as this account: the
     * caller, read by the daemon, or the daemon, read by its caller.
     *
     * <p>The kernel's {@code SO_PEERCRED}, asked through the JDK's own socket option: reading the
     * channel's descriptor for {@code getsockopt} took {@code sun.misc.Unsafe}, which the JDK warns
     * about at every start and will remove. The option answers a principal, not a number, so it is
     * compared with the owner of {@code /proc/self}, this process, whose numeric uid must be this
     * process's own. The socket's file is never asked: its name may be unlinked or another file by
     * the time the connection is read, and the kernel's answer is about the connection alone. The
     * pid it gave was never read; the answer carries {@code -1} for it, and for the uid of anybody
     * else.
     *
     * @param channel A connection on a Unix domain socket, accepted or made.
     * @return The peer.
     */
    public static VarlinkServer.Peer read(SocketChannel channel) {
        try {
            final var peer = channel.getOption(ExtendedSocketOptions.SO_PEERCRED).user();
            final Path self = Path.of("/proc/self");
            final long own = ownUid();
            final boolean ours =
                    Integer.toUnsignedLong(
                                    ((Number) Files.getAttribute(self, "unix:uid")).intValue())
                            == own;
            final var owner = Files.getOwner(self);
            // Principals of the same kind compare by their numeric id, never by a looked-up name.
            final boolean same = ours && peer.equals(owner) && owner.equals(peer);
            return new VarlinkServer.Peer(same ? own : -1, -1, same);
        } catch (IOException | ClassCastException | UnsupportedOperationException ex) {
            throw new IllegalStateException("Cannot read the unix socket peer credentials", ex);
        }
    }

    /** Returns the numeric account uid without a name service lookup. */
    public static long ownUid() {
        try {
            return Integer.toUnsignedLong((int) GETUID.invokeExact());
        } catch (Throwable ex) {
            throw new IllegalStateException("Cannot read this process's uid", ex);
        }
    }
}
