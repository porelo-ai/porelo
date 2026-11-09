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
     * ownership is interpreted in the current user namespace as in {@link SystemProgram}.
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

    static byte[] read(Path file, int maximumBytes, SystemProgram.Attributes attributes, int root)
            throws IOException {
        if (!file.isAbsolute() || !file.equals(file.normalize())) {
            throw new IllegalArgumentException("Expected an absolute normalized file path");
        }
        if (maximumBytes < 1 || maximumBytes == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Expected a positive bounded read limit");
        }
        for (Path parent = file.getParent(); parent != null; parent = parent.getParent()) {
            if ((SystemProgram.check(parent, attributes, root) & 0170000) != 0040000) {
                throw new IOException("Refusing " + parent + ": not a directory");
            }
        }
        if ((SystemProgram.check(file, attributes, root) & 0170000) != 0100000) {
            throw new IOException("Refusing " + file + ": not a regular file");
        }
        // Checked ancestors cannot be replaced by an unprivileged caller between check and open.
        try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            final byte[] bytes = in.readNBytes(maximumBytes + 1);
            if (bytes.length > maximumBytes) {
                throw new IOException("Refusing " + file + ": exceeds read limit");
            }
            return bytes;
        }
    }
}
