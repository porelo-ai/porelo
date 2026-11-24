package org.fuin.porelo.daemon;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What a page position is a position in: a task log, or the recording of a terminal session.
 *
 * <p>A position alone holds for whatever lies at the path now, and a new run's log or a session
 * begun anew can have grown past it: an earlier client then read the new one's lines as the old
 * one's. So every reply that gives a position names what it is a position in, and a page asked with
 * another name is answered as gone. The name is opaque: a digest of the file's key and, where its
 * file store keeps a birth time, its creation time, which a log written to and a recording added to
 * keep, and a file made anew in its place does not.
 *
 * <p>Where the file store keeps no birth time, or the daemon has no directory of its own on it to
 * find out, the file key alone names the file. A file made anew under a reused key is then the same
 * log, and a caller pages the new file.
 */
final class LogIdentity {

    private static final Map<FileStore, Boolean> PROBED = new ConcurrentHashMap<>();

    private static volatile Map<FileStore, Path> probeDirectories = Map.of();

    private LogIdentity() {}

    /** Finds out whether a file store keeps a birth time, by a probe in a directory. */
    @FunctionalInterface
    interface Probe {

        /**
         * Probes.
         *
         * @param directory A directory on the file store that only the daemon can write.
         * @return Whether the store keeps a birth time.
         * @throws IOException When the probe cannot run.
         */
        boolean kept(Path directory) throws IOException;
    }

    /**
     * Names the directories the daemon probes file stores in, one per file store: each is made
     * private (mode 0700) if absent and must lie where no task can write. A file on any other store
     * is named by its file key alone.
     *
     * @param directories The directories; each is made if absent.
     */
    static void probingIn(final Path... directories) {
        final Map<FileStore, Path> found = new HashMap<>();
        for (final Path directory : directories) {
            try {
                makePrivate(directory);
                found.putIfAbsent(Files.getFileStore(directory), directory);
            } catch (IOException ex) {
                System.err.println(
                        "porelod: no probe directory for a file store's birth time: "
                                + ex.getMessage());
                System.err.flush();
            }
        }
        probeDirectories = Map.copyOf(found);
    }

    /**
     * Returns the identity of a log file or of a recording's directory.
     *
     * @param path The file or directory, never followed as a link.
     * @return 32 lowercase hexadecimal digits.
     * @throws IOException When its attributes cannot be read, as for one gone, or its file system
     *     names no file key.
     */
    static String of(final Path path) throws IOException {
        return of(path, probeDirectories, PROBED, LogIdentity::probe);
    }

    /**
     * Returns the identity of a file, with what finds out about its file store given.
     *
     * @param path The file or directory, never followed as a link.
     * @param directories The directory to probe in, per file store; a file on another store is
     *     named by its key alone.
     * @param cache What is known of each file store, a failed probe included.
     * @param probe What finds it out.
     * @return 32 lowercase hexadecimal digits.
     * @throws IOException When its attributes cannot be read, or its file system names no key.
     */
    static String of(
            final Path path,
            final Map<FileStore, Path> directories,
            final Map<FileStore, Boolean> cache,
            final Probe probe)
            throws IOException {
        final BasicFileAttributes attributes =
                Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        final FileStore store = Files.getFileStore(path);
        final Path directory = directories.get(store);
        final boolean kept = directory != null && birthTimeKept(store, directory, cache, probe);
        return of(attributes, kept);
    }

    /**
     * Returns the identity of a file as its attributes and its file store's birth time tell it.
     *
     * <p>Where the file store keeps no birth time, Java gives the modification time as the creation
     * time, so a log written to would be another log with every line: the file key alone names it
     * then. Where the store keeps a birth time, the creation time stays in the name even when it
     * equals the modification time, as for a file not yet written to.
     *
     * @param attributes The file's attributes.
     * @param birthTimeKept Whether its file store keeps a birth time.
     * @return 32 lowercase hexadecimal digits.
     * @throws IOException When the file system names no file key.
     */
    static String of(final BasicFileAttributes attributes, final boolean birthTimeKept)
            throws IOException {
        final Object key = attributes.fileKey();
        if (key == null) {
            throw new IOException("the file system names no file key");
        }
        final String said =
                key + (birthTimeKept ? "@" + attributes.creationTime().toInstant() : "");
        try {
            final byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(said.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Every Java runtime has SHA-256", ex);
        }
    }

    /**
     * Tells whether a file store keeps a birth time, decided once per store and remembered: a probe
     * that fails is logged once and remembered as no birth time, so one store never answers two
     * ways.
     *
     * @param store The file store.
     * @param directory The daemon's own directory on it.
     * @param cache What is known.
     * @param probe What finds it out.
     * @return Whether the store keeps a birth time.
     */
    static boolean birthTimeKept(
            final FileStore store,
            final Path directory,
            final Map<FileStore, Boolean> cache,
            final Probe probe) {
        return cache.computeIfAbsent(
                store,
                known -> {
                    try {
                        return probe.kept(directory);
                    } catch (IOException | RuntimeException ex) {
                        System.err.println(
                                "porelod: birth time of file store "
                                        + known
                                        + " not probed, files there are named by their key: "
                                        + ex.getMessage());
                        System.err.flush();
                        return false;
                    }
                });
    }

    /**
     * Probes by a temporary file in the directory: its modification time is set into the past, and
     * a creation time that moved with it means no birth time.
     *
     * @param directory The daemon's own directory, made private if absent.
     * @return Whether the file store keeps a birth time.
     * @throws IOException When the probe cannot run.
     */
    static boolean probe(final Path directory) throws IOException {
        makePrivate(directory);
        final Path file = Files.createTempFile(directory, ".birth-probe-", ".tmp");
        try {
            final FileTime past = FileTime.from(Instant.parse("2001-01-01T00:00:00Z"));
            Files.setLastModifiedTime(file, past);
            return !Files.readAttributes(file, BasicFileAttributes.class)
                    .creationTime()
                    .equals(past);
        } finally {
            try {
                Files.deleteIfExists(file);
            } catch (IOException ex) {
                // The answer stands: a probe file left behind is only clutter in a private
                // directory.
                System.err.println("porelod: probe file not removed: " + ex.getMessage());
            }
        }
    }

    private static void makePrivate(final Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            Files.createDirectories(
                    directory,
                    PosixFilePermissions.asFileAttribute(
                            PosixFilePermissions.fromString("rwx------")));
        }
    }
}
