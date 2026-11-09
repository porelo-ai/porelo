package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.fuin.porelo.wire.Directories;

/**
 * Holds a file's lock across threads and processes: the daemon's pass and a command at the terminal
 * work on one mailbox, and a lock of either kind alone leaves the other way open.
 */
final class FileLocks {

    /**
     * One lock per file in this process: a second channel's lock of the same file throws instead of
     * waiting.
     */
    private static final ConcurrentHashMap<Path, ReentrantLock> HELD = new ConcurrentHashMap<>();

    private FileLocks() {}

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
        final ReentrantLock here =
                HELD.computeIfAbsent(
                        lock.toAbsolutePath().normalize(), path -> new ReentrantLock());
        here.lock();
        try {
            if (here.getHoldCount() > 1) {
                return locked.run();
            }
            Directories.create(lock.toAbsolutePath().getParent());
            while (true) {
                final Object before;
                try {
                    before = fileKey(lock);
                } catch (NoSuchFileException ex) {
                    try {
                        Files.createFile(lock);
                    } catch (FileAlreadyExistsException createdMeanwhile) {
                        // Another process made it; read its identity on the next pass.
                    }
                    continue;
                }
                final FileChannel channel;
                try {
                    channel =
                            FileChannel.open(
                                    lock, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                } catch (NoSuchFileException ex) {
                    continue;
                }
                try (channel;
                        FileLock held = channel.lock()) {
                    final Object after;
                    try {
                        after = fileKey(lock);
                    } catch (NoSuchFileException ex) {
                        continue;
                    }
                    if (before.equals(after)) {
                        return locked.run();
                    }
                    // A holder removed it while this process waited on the old inode.
                }
            }
        } finally {
            here.unlock();
        }
    }

    /**
     * Runs something while holding the lock on a file, made if it is not there, only when nobody
     * holds it now: a second sign-in of one agent says the first runs instead of queueing behind
     * it.
     *
     * @param <T> What it answers.
     * @param lock The lock file.
     * @param locked What runs.
     * @return Its answer, or empty when the lock is held - by another process, another thread, or
     *     this thread already.
     * @throws IOException Locking, reading or writing failed.
     */
    static <T> java.util.Optional<T> holdingIfFree(final Path lock, final Locked<T> locked)
            throws IOException {
        final ReentrantLock here =
                HELD.computeIfAbsent(
                        lock.toAbsolutePath().normalize(), path -> new ReentrantLock());
        try {
            // A probe holds it for a moment; a sign-in for minutes.
            if (!here.tryLock(PROBE.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
                return java.util.Optional.empty();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return java.util.Optional.empty();
        }
        try {
            if (here.getHoldCount() > 1) {
                return java.util.Optional.empty();
            }
            Directories.create(lock.toAbsolutePath().getParent());
            while (true) {
                final Object before;
                try {
                    before = fileKey(lock);
                } catch (NoSuchFileException ex) {
                    try {
                        Files.createFile(lock);
                    } catch (FileAlreadyExistsException createdMeanwhile) {
                        // Another process made it; read its identity on the next pass.
                    }
                    continue;
                }
                final FileChannel channel;
                try {
                    channel =
                            FileChannel.open(
                                    lock, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                } catch (NoSuchFileException ex) {
                    continue;
                }
                try (channel) {
                    final FileLock held;
                    try {
                        held = tryLockAWhile(channel);
                    } catch (java.nio.channels.OverlappingFileLockException ex) {
                        return java.util.Optional.empty();
                    }
                    if (held == null) {
                        return java.util.Optional.empty();
                    }
                    try (held) {
                        final Object after;
                        try {
                            after = fileKey(lock);
                        } catch (NoSuchFileException ex) {
                            continue;
                        }
                        if (before.equals(after)) {
                            return java.util.Optional.of(locked.run());
                        }
                    }
                }
            }
        } finally {
            here.unlock();
        }
    }

    /** How long a lock held by another process's probe may take to come free. */
    private static final java.time.Duration PROBE = java.time.Duration.ofSeconds(1);

    /** Takes a file lock, trying again for a moment: another process's probe holds it briefly. */
    private static @org.jspecify.annotations.Nullable FileLock tryLockAWhile(
            final FileChannel channel) throws IOException {
        final long until = System.nanoTime() + PROBE.toNanos();
        while (true) {
            final FileLock taken = channel.tryLock();
            if (taken != null || System.nanoTime() - until > 0) {
                return taken;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
    }

    private static Object fileKey(final Path lock) throws IOException {
        return java.util.Objects.requireNonNull(
                Files.readAttributes(lock, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
                        .fileKey(),
                "the lock filesystem must provide file identities");
    }

    /**
     * Runs and removes the lock file before releasing it, including when the operation fails. Call
     * this only as the last operation of any enclosing hold on the same path.
     *
     * @param <T> What it answers.
     * @param lock The lock file.
     * @param locked What runs.
     * @return Its answer.
     * @throws IOException Locking, reading, writing or unlinking failed.
     */
    static <T> T holdingThenRemoving(final Path lock, final Locked<T> locked) throws IOException {
        return holding(
                lock,
                () -> {
                    final T result;
                    try {
                        result = locked.run();
                    } catch (IOException | RuntimeException | Error ex) {
                        try {
                            Files.deleteIfExists(lock);
                        } catch (IOException cleanup) {
                            ex.addSuppressed(cleanup);
                        }
                        throw ex;
                    }
                    Files.deleteIfExists(lock);
                    return result;
                });
    }

    /**
     * Says whether nobody holds a lock file now, without waiting: a lock that is held - by this
     * process or another - marks what is in use, which nothing may remove.
     *
     * @param lock The lock file.
     * @return {@code true} when it could be taken this moment, or is not there, since nobody can
     *     hold a file that does not exist; {@code false} when it is held or cannot be opened.
     */
    public static boolean free(final Path lock) {
        final ReentrantLock here =
                HELD.computeIfAbsent(
                        lock.toAbsolutePath().normalize(), path -> new ReentrantLock());
        // Probed only under this process's own lock: a probe's channel closing while another
        // thread here holds the file drops that thread's lock with it.
        if (here.isLocked() || !here.tryLock()) {
            return false;
        }
        try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.WRITE);
                FileLock taken = channel.tryLock()) {
            return taken != null;
        } catch (final java.nio.channels.OverlappingFileLockException ex) {
            return false;
        } catch (final NoSuchFileException ex) {
            return true;
        } catch (final IOException ex) {
            return false;
        } finally {
            here.unlock();
        }
    }
}
