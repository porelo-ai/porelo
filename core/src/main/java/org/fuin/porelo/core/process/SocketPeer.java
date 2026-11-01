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
     * compared with the owner of the socket's file, whose numeric uid must be this process's: the
     * end that bound the socket names it as its own address, the calling end as the other's, whose
     * own has no path (an empty one read as the working directory). The pid it gave was never read;
     * the answer carries {@code -1} for it, and for the uid of anybody else.
     *
     * @param channel A connection on a Unix domain socket, accepted or made.
     * @return The peer.
     */
    public static VarlinkServer.Peer read(SocketChannel channel) {
        try {
            final var peer = channel.getOption(ExtendedSocketOptions.SO_PEERCRED).user();
            final Path socket = socketOf(channel);
            final long own = ownUid();
            final boolean ours =
                    ((Number) Files.getAttribute(socket, "unix:uid", LinkOption.NOFOLLOW_LINKS))
                                    .longValue()
                            == own;
            final var owner = Files.getOwner(socket, LinkOption.NOFOLLOW_LINKS);
            // Principals of the same kind compare by their numeric id, never by a looked-up name.
            final boolean same = ours && peer.equals(owner) && owner.equals(peer);
            return new VarlinkServer.Peer(same ? own : -1, -1, same);
        } catch (IOException | ClassCastException | UnsupportedOperationException ex) {
            throw new IllegalStateException("Cannot read the unix socket peer credentials", ex);
        }
    }

    /** The socket's file: this end's own address when it bound it, else the other end's. */
    private static Path socketOf(final SocketChannel channel) throws IOException {
        final Path local = ((UnixDomainSocketAddress) channel.getLocalAddress()).getPath();
        if (!local.toString().isEmpty()) {
            return local;
        }
        final Path remote = ((UnixDomainSocketAddress) channel.getRemoteAddress()).getPath();
        if (remote.toString().isEmpty()) {
            throw new IOException("neither end of the connection names the socket's file");
        }
        return remote;
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
