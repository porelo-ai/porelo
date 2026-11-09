package org.fuin.porelo.host;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SocketChannel;
import java.util.Map;

/** Adapts systemd's first accepted descriptor to Java's inherited-channel API. */
final class HostSocketActivation {
    private HostSocketActivation() {}

    /** StandardInput=socket supplies fd 0, without LISTEN_FDS or a descriptor-3 handoff. */
    static SocketChannel standardInput() throws IOException {
        final var channel = System.inheritedChannel();
        if (!(channel instanceof SocketChannel socket)
                || !socket.isConnected()
                || !(socket.getRemoteAddress() instanceof UnixDomainSocketAddress)) {
            throw new IOException("The push judge requires an accepted Unix socket on stdin");
        }
        return socket;
    }

    /** The numeric uid from SO_PEERCRED, independent of NSS names or caller environment. */
    static long peerUid() throws IOException {
        return peerUid(0);
    }

    static long peerUid(int descriptor) throws IOException {
        final Linker linker = Linker.nativeLinker();
        final MethodHandle option =
                linker.downcallHandle(
                        linker.defaultLookup().find("getsockopt").orElseThrow(),
                        FunctionDescriptor.of(
                                ValueLayout.JAVA_INT,
                                ValueLayout.JAVA_INT,
                                ValueLayout.JAVA_INT,
                                ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS));
        try (Arena arena = Arena.ofConfined()) {
            final var peer = arena.allocate(12, 4);
            final var length = arena.allocate(ValueLayout.JAVA_INT);
            length.set(ValueLayout.JAVA_INT, 0, 12);
            if ((int) option.invokeExact(descriptor, 1, 17, peer, length) != 0
                    || length.get(ValueLayout.JAVA_INT, 0) != 12)
                throw new IOException("Cannot read the accepted socket's peer uid");
            return Integer.toUnsignedLong(peer.get(ValueLayout.JAVA_INT, 4));
        } catch (Throwable ex) {
            throw new IOException("Cannot read the accepted socket's peer uid", ex);
        }
    }

    static void check(Map<String, String> environment, long pid) {
        if (!"1".equals(environment.get("LISTEN_FDS"))
                || !Long.toString(pid).equals(environment.get("LISTEN_PID")))
            throw new HostRefusal(
                    1,
                    "porelo-forge-gate: one accepted socket from this process's socket activation is required");
    }

    static SocketChannel accepted(Map<String, String> environment, String mode) throws IOException {
        check(environment, ProcessHandle.current().pid());
        // Java examines stdin; systemd Accept=yes supplies the connected socket at descriptor 3.
        final Linker linker = Linker.nativeLinker();
        final MethodHandle duplicate =
                linker.downcallHandle(
                        linker.defaultLookup().find("dup2").orElseThrow(),
                        FunctionDescriptor.of(
                                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        try {
            if ((int) duplicate.invokeExact(3, 0) != 0)
                throw new IOException("Cannot inherit accepted socket");
        } catch (Throwable ex) {
            throw new IOException("Cannot inherit accepted socket", ex);
        }
        final var channel = System.inheritedChannel();
        if (!(channel instanceof SocketChannel socket) || !socket.isConnected()) {
            throw new HostRefusal(
                    1, "porelo-forge-gate: socket activation supplied no accepted connection");
        }
        checkFamily(socket, mode);
        return socket;
    }

    static void checkFamily(SocketChannel socket, String mode) throws IOException {
        if (!(socket.getRemoteAddress() instanceof UnixDomainSocketAddress)
                && !mode.equals("git")) {
            throw new HostRefusal(
                    1, "porelo-forge-gate: " + mode + " mode serves a Unix socket alone");
        }
    }
}
