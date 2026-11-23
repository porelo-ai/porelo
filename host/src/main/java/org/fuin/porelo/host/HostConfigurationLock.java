package org.fuin.porelo.host;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import java.time.Duration;
import org.fuin.porelo.wire.LimitFactors;
import org.jspecify.annotations.Nullable;

/** Linux flock, interoperable with the host's existing readers and writers. */
final class HostConfigurationLock implements AutoCloseable {
    private static final Linker LINKER = Linker.nativeLinker();
    private static final MethodHandle OPEN =
            downcall(
                    "open",
                    FunctionDescriptor.of(
                            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
    private static final MethodHandle FLOCK =
            downcall(
                    "flock",
                    FunctionDescriptor.of(
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
    private static final MethodHandle CLOSE =
            downcall("close", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));

    /** The pause between tries while a timed lock is taken. */
    static final Duration PAUSE = Duration.ofMillis(20);

    private final int descriptor;

    private HostConfigurationLock(int descriptor) {
        this.descriptor = descriptor;
    }

    private static MethodHandle downcall(String name, FunctionDescriptor descriptor) {
        return LINKER.downcallHandle(LINKER.defaultLookup().find(name).orElseThrow(), descriptor);
    }

    static HostConfigurationLock acquire(Path file, boolean shared) throws IOException {
        return acquire(file, shared, null, PAUSE);
    }

    /**
     * Takes the lock within a time, looking again after a pause that follows the polling factor.
     *
     * @param file The lock file.
     * @param shared Whether a shared lock suffices.
     * @param timeout How long to keep trying.
     * @param limits The factors this run of porelo-host read from its host configuration.
     * @return The lock, held until closed.
     * @throws IOException When it cannot be taken in time.
     */
    static HostConfigurationLock acquire(
            Path file, boolean shared, Duration timeout, LimitFactors limits) throws IOException {
        return acquire(file, shared, timeout, limits.polling(PAUSE));
    }

    private static HostConfigurationLock acquire(
            Path file, boolean shared, @Nullable Duration timeout, Duration pause)
            throws IOException {
        final int descriptor = open(file, 0x80000 | 0x20000);
        final HostConfigurationLock lock = new HostConfigurationLock(descriptor);
        try {
            final long deadline = System.nanoTime() + (timeout == null ? 0 : timeout.toNanos());
            final int operation = (shared ? 1 : 2) | (timeout == null ? 0 : 4);
            while ((int) FLOCK.invokeExact(descriptor, operation) != 0) {
                if (timeout == null || System.nanoTime() >= deadline)
                    throw new IOException("Cannot acquire configuration lock");
                Thread.sleep(pause);
            }
            return lock;
        } catch (Throwable ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            try {
                lock.close();
            } catch (IOException closing) {
                ex.addSuppressed(closing);
            }
            throw new IOException("Cannot acquire configuration lock", ex);
        }
    }

    /** Opens a no-follow, nonblocking read descriptor; no flock is taken on the data file. */
    static HostConfigurationLock openRead(Path file) throws IOException {
        return new HostConfigurationLock(open(file, 0x80000 | 0x20000 | 0x800));
    }

    Path descriptorPath() {
        return Path.of("/proc/self/fd/" + descriptor);
    }

    private static int open(Path file, int flags) throws IOException {
        final int descriptor;
        try (Arena arena = Arena.ofConfined()) {
            descriptor = (int) OPEN.invokeExact(arena.allocateFrom(file.toString()), flags);
        } catch (Throwable ex) {
            throw new IOException("Cannot open host file", ex);
        }
        if (descriptor < 0) throw new IOException("Cannot open host file");
        return descriptor;
    }

    /** Releases the flock by closing its private descriptor. */
    @Override
    public void close() throws IOException {
        try {
            if ((int) CLOSE.invokeExact(descriptor) != 0) {
                throw new IOException("Cannot close configuration lock");
            }
        } catch (Throwable ex) {
            throw new IOException("Cannot close configuration lock", ex);
        }
    }
}
