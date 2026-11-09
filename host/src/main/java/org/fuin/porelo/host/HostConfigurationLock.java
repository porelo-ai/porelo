package org.fuin.porelo.host;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;

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
    private final int descriptor;

    private HostConfigurationLock(int descriptor) {
        this.descriptor = descriptor;
    }

    private static MethodHandle downcall(String name, FunctionDescriptor descriptor) {
        return LINKER.downcallHandle(LINKER.defaultLookup().find(name).orElseThrow(), descriptor);
    }

    static HostConfigurationLock acquire(Path file, boolean shared) throws IOException {
        final int descriptor;
        try (Arena arena = Arena.ofConfined()) {
            final MemorySegment name = arena.allocateFrom(file.toString());
            // Never create the mutex, follow a link, or lend its descriptor to a helper.
            descriptor = (int) OPEN.invokeExact(name, 0x80000 | 0x20000);
        } catch (Throwable ex) {
            throw new IOException("Cannot open configuration lock", ex);
        }
        if (descriptor < 0) {
            throw new IOException("Cannot open configuration lock");
        }
        final HostConfigurationLock lock = new HostConfigurationLock(descriptor);
        try {
            if ((int) FLOCK.invokeExact(descriptor, shared ? 1 : 2) != 0) {
                throw new IOException("Cannot acquire configuration lock");
            }
            return lock;
        } catch (Throwable ex) {
            try {
                lock.close();
            } catch (IOException closing) {
                ex.addSuppressed(closing);
            }
            throw new IOException("Cannot acquire configuration lock", ex);
        }
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
