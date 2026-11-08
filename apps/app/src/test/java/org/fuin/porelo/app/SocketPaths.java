package org.fuin.porelo.app;

import java.nio.file.Path;

/** Where a test's sockets go, so their paths fit the Unix limit wherever the checkout lies. */
final class SocketPaths {

    private SocketPaths() {}

    /**
     * The shorter way to a directory of sockets: relative to the working directory, or absolute.
     *
     * @param directory The directory, absolute.
     * @return It, written the shorter way from the working directory.
     */
    static Path shortest(final Path directory) {
        return shortest(Path.of("").toAbsolutePath(), directory);
    }

    static Path shortest(final Path workingDirectory, final Path directory) {
        // ".." climbs from where the working directory really is, not from a link's name; the way
        // down follows links as any path does.
        Path from = workingDirectory;
        try {
            from = workingDirectory.toRealPath();
        } catch (final java.io.IOException ex) {
            // Not there, as in a test of the way alone: taken as written.
        }
        final Path relative = from.relativize(directory);
        return bytes(relative) < bytes(directory) ? relative : directory;
    }

    /** The limit of a socket's path is in bytes of UTF-8, not in characters. */
    private static int bytes(final Path path) {
        return path.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }
}
