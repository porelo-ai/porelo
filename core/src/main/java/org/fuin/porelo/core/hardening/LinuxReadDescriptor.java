package org.fuin.porelo.core.hardening;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;

/** A pinned, nonblocking Linux read descriptor with no-follow relative child opens. */
public final class LinuxReadDescriptor implements AutoCloseable {
    private final int fd;

    private LinuxReadDescriptor(int fd) {
        this.fd = fd;
    }

    /** Opens a path without following its last component or waiting on a FIFO. */
    public static LinuxReadDescriptor open(Path file, boolean directory) throws IOException {
        return open(-100, file.toString(), directory);
    }

    /** Opens exactly one child of this pinned directory. */
    public LinuxReadDescriptor child(String name, boolean directory) throws IOException {
        if (name.isEmpty() || name.equals(".") || name.equals("..") || name.contains("/"))
            throw new IOException("Expected one descriptor-relative name");
        return open(fd, name, directory);
    }

    static int flags(String architecture, boolean directory) throws IOException {
        if (!java.util.Set.of("amd64", "x86_64", "aarch64").contains(architecture))
            throw new IOException("No safe descriptor flags for this architecture");
        // The AArch64 ABI assigns different directory and no-follow bits.
        return 0x80000
                | 0x800
                | (architecture.equals("aarch64")
                        ? 0x8000 | (directory ? 0x4000 : 0)
                        : 0x20000 | (directory ? 0x10000 : 0));
    }

    private static LinuxReadDescriptor open(int parent, String name, boolean directory)
            throws IOException {
        if (!System.getProperty("os.name").equals("Linux"))
            throw new IOException("Linux descriptors are required");
        final int flags = flags(System.getProperty("os.arch"), directory);
        try (Arena arena = Arena.ofConfined()) {
            final int fd = (int) Native.OPENAT.invokeExact(parent, arena.allocateFrom(name), flags);
            if (fd < 0) throw new IOException("Cannot open read descriptor");
            return new LinuxReadDescriptor(fd);
        } catch (Throwable ex) {
            throw new IOException("Cannot open read descriptor", ex);
        }
    }

    /** Names this open inode for descriptor metadata checks and bounded reads. */
    public Path path() {
        return Path.of("/proc/self/fd/" + fd);
    }

    @Override
    public void close() throws IOException {
        try {
            if ((int) Native.CLOSE.invokeExact(fd) != 0)
                throw new IOException("Cannot close read descriptor");
        } catch (Throwable ex) {
            throw new IOException("Cannot close read descriptor", ex);
        }
    }

    private static final class Native {
        private static final Linker LINKER = Linker.nativeLinker();
        private static final MethodHandle OPENAT =
                LINKER.downcallHandle(
                        LINKER.defaultLookup().find("openat").orElseThrow(),
                        FunctionDescriptor.of(
                                ValueLayout.JAVA_INT,
                                ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS,
                                ValueLayout.JAVA_INT));
        private static final MethodHandle CLOSE =
                LINKER.downcallHandle(
                        LINKER.defaultLookup().find("close").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
    }
}
