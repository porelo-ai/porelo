package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Holds a file's lock across threads and processes: the daemon's pass and a command at the terminal work on one
 * mailbox, and a lock of either kind alone leaves the other way open.
 */
final class FileLocks {

    /** One lock per file in this process: a second channel's lock of the same file throws instead of waiting. */
    private static final ConcurrentHashMap<Path, ReentrantLock> HELD = new ConcurrentHashMap<>();

    private FileLocks() {
    }

    /** What runs while the lock is held. */
    @FunctionalInterface
    interface Locked<T> {

        /**
         * Runs.
         *
         * @return Its answer.
         * @throws IOException Reading or writing failed.
         */
        T run() throws IOException;
    }

    /**
     * Runs something while holding the lock on a file, made if it is not there.
     *
     * @param <T> What it answers.
     * @param lock The lock file.
     * @param locked What runs.
     * @return Its answer.
     * @throws IOException Locking, reading or writing failed.
     */
    static <T> T holding(final Path lock, final Locked<T> locked) throws IOException {
        final ReentrantLock here = HELD.computeIfAbsent(lock.toAbsolutePath().normalize(), path -> new ReentrantLock());
        here.lock();
        try {
            if (here.getHoldCount() > 1) {
                return locked.run();
            }
            Files.createDirectories(lock.toAbsolutePath().getParent());
            try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    FileLock held = channel.lock()) {
                return locked.run();
            }
        } finally {
            here.unlock();
        }
    }

    /**
     * Says whether nobody holds a lock file now, without waiting: a lock that is held - by this process or another -
     * marks what is in use, which nothing may remove.
     *
     * @param lock The lock file.
     * @return {@code true} when it could be taken this moment; {@code false} when it is held or cannot be opened.
     */
    public static boolean free(final Path lock) {
        if (HELD.containsKey(lock.toAbsolutePath().normalize())
                && HELD.get(lock.toAbsolutePath().normalize()).isLocked()) {
            return false;
        }
        try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.WRITE);
                FileLock taken = channel.tryLock()) {
            return taken != null;
        } catch (final java.nio.channels.OverlappingFileLockException ex) {
            return false;
        } catch (final IOException ex) {
            return false;
        }
    }
}
