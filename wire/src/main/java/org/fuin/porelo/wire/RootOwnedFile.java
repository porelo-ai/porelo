package org.fuin.porelo.wire;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Reads bounded host data whose entire path is controlled by root. */
public final class RootOwnedFile {

    private RootOwnedFile() {}

    /**
     * Reads a regular file without following symbolic links, after checking root ownership and
     * absence of group/other write permission on the file and every ancestor through the root. Root
     * ownership requires the initial user namespace; unmapped owners are refused because root
     * cannot be distinguished from another unmapped account.
     *
     * @param file Absolute, normalized file path.
     * @param maximumBytes Positive read limit, smaller than {@link Integer#MAX_VALUE}.
     * @return The complete file contents.
     * @throws UncheckedIOException If any check or the read fails, including an oversized file.
     */
    public static byte[] read(Path file, int maximumBytes) {
        try {
            return read(
                    file,
                    maximumBytes,
                    path -> Files.readAttributes(path, "unix:uid,mode", LinkOption.NOFOLLOW_LINKS),
                    SystemProgram.rootAsSeenHere());
        } catch (IOException ex) {
            throw new UncheckedIOException(ex.getMessage(), ex);
        }
    }

    /** Supplies no-follow Unix attributes, allowing a caller's fixture to model root ownership. */
    @FunctionalInterface
    public interface Attributes {
        /**
         * Returns the file's numeric {@code uid} and Unix {@code mode}, without following links.
         */
        java.util.Map<String, Object> read(Path path) throws IOException;
    }

    /**
     * Reads with an explicit attribute source and root uid. All path, type, ownership, permission
     * and size checks of {@link #read(Path, int)} still apply. An explicit nonzero root uid is
     * refused; the attribute source and a zero root uid are a trusted caller's ownership fixture.
     */
    public static byte[] read(Path file, int maximumBytes, Attributes attributes, int root)
            throws IOException {
        if (!file.isAbsolute() || !file.equals(file.normalize())) {
            throw new IllegalArgumentException("Expected an absolute normalized file path");
        }
        if (maximumBytes < 1 || maximumBytes == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Expected a positive bounded read limit");
        }
        checkPath(file, attributes, root);
        // Checked ancestors cannot be replaced by an unprivileged caller between check and open.
        try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            return contents(in, maximumBytes);
        }
    }

    /** Validates the entire root-owned path before a caller opens its own no-follow descriptor. */
    public static void checkPath(Path file, Attributes attributes, int root) throws IOException {
        if (!file.isAbsolute() || !file.equals(file.normalize())) {
            throw new IllegalArgumentException("Expected an absolute normalized file path");
        }
        for (Path parent = file.getParent(); parent != null; parent = parent.getParent()) {
            if ((SystemProgram.check(parent, attributes::read, root) & 0170000) != 0040000) {
                throw new IOException("Refusing " + parent + ": not a directory");
            }
        }
        if ((SystemProgram.check(file, attributes::read, root) & 0170000) != 0100000) {
            throw new IOException("Refusing " + file + ": not a regular file");
        }
    }

    /**
     * Reads an already open Linux descriptor, validating that descriptor's metadata before reading.
     * The caller owns and keeps the descriptor open throughout this call, and checks the original
     * path's ancestors before opening it. The attribute source follows the procfs descriptor link,
     * which names the open inode even when the original file has been renamed or replaced.
     */
    public static byte[] readOpened(
            Path descriptor, int maximumBytes, Attributes attributes, int root) throws IOException {
        if (!descriptor.toString().matches("/proc/self/fd/[0-9]+")
                || maximumBytes < 1
                || maximumBytes == Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "Expected an open descriptor and bounded read limit");
        }
        if ((SystemProgram.check(descriptor, attributes::read, root) & 0170000) != 0100000) {
            throw new IOException("Refusing open host file: not a regular file");
        }
        // Only this pinned descriptor link is followed, never the original pathname.
        try (InputStream in = Files.newInputStream(descriptor)) {
            return contents(in, maximumBytes);
        }
    }

    private static byte[] contents(InputStream in, int maximumBytes) throws IOException {
        final byte[] bytes = in.readNBytes(maximumBytes + 1);
        if (bytes.length > maximumBytes) {
            java.util.Arrays.fill(bytes, (byte) 0);
            throw new IOException("Refusing host file: exceeds read limit");
        }
        return bytes;
    }
}
