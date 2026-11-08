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
        final Path relative = workingDirectory.relativize(directory);
        return relative.toString().length() < directory.toString().length() ? relative : directory;
    }
}
