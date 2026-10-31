package org.fuin.porelo.core.process;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.channels.SocketChannel;
import org.fuin.porelo.wire.varlink.VarlinkServer;
import sun.misc.Unsafe;

/** Reads Linux peer credentials without trusting anything sent by the client. */
public final class SocketPeer {
    private static final int SOL_SOCKET = 1;
    private static final int SO_PEERCRED = 17;
    private static final int UCRED_BYTES = 12;
    private static final Linker LINKER = Linker.nativeLinker();
    private static final MethodHandle GETSOCKOPT =
            LINKER.downcallHandle(
                    LINKER.defaultLookup().find("getsockopt").orElseThrow(),
                    FunctionDescriptor.of(
                            ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS));
    private static final MethodHandle GETUID =
            LINKER.downcallHandle(
                    LINKER.defaultLookup().find("getuid").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT));

    private SocketPeer() {}

    /** Returns the kernel's uid and pid, authorizing the daemon's own account. */
    public static VarlinkServer.Peer read(SocketChannel channel) {
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment credentials = arena.allocate(UCRED_BYTES, 4);
            final MemorySegment length = arena.allocate(ValueLayout.JAVA_INT);
            length.set(ValueLayout.JAVA_INT, 0, UCRED_BYTES);
            if ((int)
                                    GETSOCKOPT.invokeExact(
                                            descriptor(channel),
                                            SOL_SOCKET,
                                            SO_PEERCRED,
                                            credentials,
                                            length)
                            != 0
                    || length.get(ValueLayout.JAVA_INT, 0) != UCRED_BYTES) {
                throw new IllegalStateException("Cannot read the unix socket peer credentials");
            }
            final long pid = Integer.toUnsignedLong(credentials.get(ValueLayout.JAVA_INT, 0));
            final long uid = Integer.toUnsignedLong(credentials.get(ValueLayout.JAVA_INT, 4));
            return new VarlinkServer.Peer(uid, pid, uid == ownUid());
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Throwable ex) {
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

    private static int descriptor(SocketChannel channel) throws ReflectiveOperationException {
        // SocketChannel exposes uid/gid but omits pid; the descriptor is needed for SO_PEERCRED.
        final var field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        final Unsafe unsafe = (Unsafe) field.get(null);
        return unsafe.getInt(
                channel, unsafe.objectFieldOffset(channel.getClass().getDeclaredField("fdVal")));
    }
}
