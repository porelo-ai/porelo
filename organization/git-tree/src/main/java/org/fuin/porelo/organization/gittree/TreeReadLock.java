package org.fuin.porelo.organization.gittree;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** Serializes the whole read transaction, so a late old fetch cannot undo a newer trust record. */
final class TreeReadLock {
    private static final ConcurrentHashMap<Path, ReentrantLock> HELD = new ConcurrentHashMap<>();

    private TreeReadLock() {}

    @FunctionalInterface
    interface Read<T> {
        T run() throws IOException;
    }

    static <T> T holding(Path state, Read<T> read) throws IOException {
        final Path path = state.toAbsolutePath().normalize().resolve("read.lock");
        final ReentrantLock local = HELD.computeIfAbsent(path, ignored -> new ReentrantLock());
        try {
            local.lockInterruptibly();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("the organization read was interrupted", ex);
        }
        try {
            Files.createDirectories(path.getParent());
            Files.setPosixFilePermissions(
                    path.getParent(), PosixFilePermissions.fromString("rwx------"));
            try (FileChannel channel =
                            FileChannel.open(
                                    path,
                                    java.util.Set.of(
                                            StandardOpenOption.CREATE,
                                            StandardOpenOption.WRITE,
                                            LinkOption.NOFOLLOW_LINKS),
                                    PosixFilePermissions.asFileAttribute(
                                            PosixFilePermissions.fromString("rw-------")));
                    FileLock held = channel.lock()) {
                return read.run();
            }
        } finally {
            local.unlock();
        }
    }
}
