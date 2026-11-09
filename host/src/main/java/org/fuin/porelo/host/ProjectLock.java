package org.fuin.porelo.host;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/** Linux flock, shared with the follow's inherited open file description. */
final class ProjectLock implements AutoCloseable {
    private static final Linker LINKER = Linker.nativeLinker();
    private static final MethodHandle OPEN =
            function("open", ValueLayout.ADDRESS, ValueLayout.JAVA_INT);
    private static final MethodHandle FLOCK =
            function("flock", ValueLayout.JAVA_INT, ValueLayout.JAVA_INT);
    private static final MethodHandle CLOSE = function("close", ValueLayout.JAVA_INT);
    private final int descriptor;
    private final boolean owned;

    private ProjectLock(int descriptor, boolean owned) {
        this.descriptor = descriptor;
        this.owned = owned;
    }

    static ProjectLock acquire(Path conf, @Nullable Integer inherited) throws IOException {
        if (inherited != null) {
            try {
                final var actual =
                        Files.readAttributes(Path.of("/proc/self/fd/" + inherited), "unix:dev,ino");
                final var expected =
                        Files.readAttributes(conf, "unix:dev,ino", LinkOption.NOFOLLOW_LINKS);
                if (!actual.equals(expected)) {
                    throw new HostRefusal(65, "the inherited project lock is not forge.conf");
                }
                if (flock(inherited, 2 | 4) != 0) {
                    throw new HostRefusal(69, "the inherited project lock is unavailable");
                }
                return new ProjectLock(inherited, false);
            } catch (IOException ex) {
                throw new HostRefusal(69, "the inherited project lock is unavailable");
            }
        }
        final int fd;
        try (Arena arena = Arena.ofConfined()) {
            // O_RDONLY | O_CLOEXEC | O_NOFOLLOW: children must never retain the project's lock.
            fd = (int) OPEN.invokeExact(arena.allocateFrom(conf.toString()), 02000000 | 0400000);
        } catch (Throwable ex) {
            throw new IOException("Project lock could not be opened");
        }
        if (fd < 0) {
            throw new IOException("Project lock could not be opened");
        }
        final ProjectLock lock = new ProjectLock(fd, true);
        if (flock(fd, 2) != 0) {
            lock.close();
            throw new IOException("Project lock could not be acquired");
        }
        return lock;
    }

    private static int flock(int fd, int operation) throws IOException {
        try {
            return (int) FLOCK.invokeExact(fd, operation);
        } catch (Throwable ex) {
            throw new IOException("Project lock unavailable");
        }
    }

    private static MethodHandle function(String name, java.lang.foreign.MemoryLayout... arguments) {
        return LINKER.downcallHandle(
                LINKER.defaultLookup().find(name).orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, arguments));
    }

    @Override
    public void close() throws IOException {
        if (owned) {
            try {
                final int result = (int) CLOSE.invokeExact(descriptor);
                if (result != 0) {
                    throw new IOException("Project lock could not be closed");
                }
            } catch (Throwable ex) {
                throw new IOException("Project lock could not be closed");
            }
        }
    }
}
