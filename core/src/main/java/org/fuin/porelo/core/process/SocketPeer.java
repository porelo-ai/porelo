package org.fuin.porelo.core.process;

import java.io.IOException;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
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
     * Returns whether the kernel says the connecting process runs as the account that bound the
     * socket, which is the daemon's own.
     *
     * <p>The kernel's {@code SO_PEERCRED}, asked through the JDK's own socket option: reading the
     * channel's descriptor for {@code getsockopt} took {@code sun.misc.Unsafe}, which the JDK warns
     * about at every start and will remove. The pid it gave was never read; the answer carries
     * {@code -1} for it, and for the uid of anybody else.
     *
     * @param channel An accepted connection on a Unix domain socket.
     * @return The peer.
     */
    public static VarlinkServer.Peer read(SocketChannel channel) {
        try {
            final var peer = channel.getOption(ExtendedSocketOptions.SO_PEERCRED).user();
            final var bound =
                    Files.getOwner(
                            ((UnixDomainSocketAddress) channel.getLocalAddress()).getPath(),
                            LinkOption.NOFOLLOW_LINKS);
            // Principals of the same kind compare by their numeric id, never by a looked-up name.
            final boolean same = peer.equals(bound) && bound.equals(peer);
            return new VarlinkServer.Peer(same ? ownUid() : -1, -1, same);
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
