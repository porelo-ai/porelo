package org.fuin.porelo.host;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.nio.file.Path;

/** The host follow's Linux flock, shared by its writer and read-only progress probes. */
final class HostFollowLock implements AutoCloseable {
    /** The stable inode a follow holds until all its work ends. */
    public static final String PATH = "/run/porelo-host/follow.lock";

    private static final Linker LINKER = Linker.nativeLinker();
    private static final MemoryLayout ERRNO = Linker.Option.captureStateLayout();
    private static final VarHandle ERROR =
            ERRNO.varHandle(MemoryLayout.PathElement.groupElement("errno"));
    private static final MethodHandle OPEN =
            LINKER.downcallHandle(
                    LINKER.defaultLookup().find("open").orElseThrow(),
                    FunctionDescriptor.of(
                            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
    private static final MethodHandle CLOSE =
            LINKER.downcallHandle(
                    LINKER.defaultLookup().find("close").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
    private static final MethodHandle FLOCK =
            LINKER.downcallHandle(
                    LINKER.defaultLookup().find("flock").orElseThrow(),
                    FunctionDescriptor.of(
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
                    Linker.Option.captureCallState("errno"));

    private final int descriptor;

    private HostFollowLock(Path path) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            descriptor =
                    (int)
                            OPEN.invokeExact(
                                    arena.allocateFrom(path.toString()), 0x80000 | 0x20000 | 0x800);
        } catch (Throwable ex) {
            throw new IOException("Cannot open the host follow lock", ex);
        }
        if (descriptor < 0) throw new IOException("Cannot open the host follow lock");
    }

    /**
     * Holds an exclusive flock on an existing file whose root-owned path the caller checked.
     *
     * @param path The stable lock file, opened without following symlinks.
     * @return The lock, held until closed.
     * @throws IOException If opening or locking fails.
     */
    public static HostFollowLock acquire(Path path) throws IOException {
        final var lock = new HostFollowLock(path);
        try {
            lock.flock(2);
            return lock;
        } catch (IOException ex) {
            try {
                lock.close();
            } catch (IOException closing) {
                ex.addSuppressed(closing);
            }
            throw ex;
        }
    }

    /**
     * Probes once with a shared, nonblocking flock, without creating or changing the file.
     *
     * @param path An existing lock file whose root-owned path the caller checked.
     * @return Whether an exclusive flock prevents the shared probe.
     * @throws IOException If the probe fails for any reason other than lock contention.
     */
    public static boolean live(Path path) throws IOException {
        try (var lock = new HostFollowLock(path)) {
            return lock.flock(1 | 4) == 11;
        }
    }

    private int flock(int operation) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            final var state = arena.allocate(ERRNO);
            if ((int) FLOCK.invokeExact(state, descriptor, operation) == 0) return 0;
            final int error = (int) ERROR.get(state, 0L);
            if (operation == (1 | 4) && error == 11) return error;
            throw new IOException("Cannot acquire the host follow lock (errno " + error + ")");
        } catch (IOException ex) {
            throw ex;
        } catch (Throwable ex) {
            throw new IOException("Cannot probe the host follow lock", ex);
        }
    }

    /** Releases the flock and its private file descriptor. */
    @Override
    public void close() throws IOException {
        try {
            if ((int) CLOSE.invokeExact(descriptor) != 0)
                throw new IOException("Cannot close the host follow lock");
        } catch (IOException ex) {
            throw ex;
        } catch (Throwable ex) {
            throw new IOException("Cannot close the host follow lock", ex);
        }
    }
}
