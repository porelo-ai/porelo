package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * A run's log, begun anew for each run as a file of its own.
 *
 * <p>Emptied in place, the last run's log kept its file, and a client holding a position in it was
 * shown the new run's lines there once they reached it. A new file in its place is told apart by
 * its file key and creation time, which a reader names its positions by.
 */
final class RunLog {

    /** The name the new file is made under before it takes the log's place. */
    static final String NEXT = ".next";

    private RunLog() {}

    /**
     * Puts an empty file in a log's place in one rename, so a reader never finds the log missing. A
     * link in its place, or under the name the new file is made under, is replaced, never followed.
     *
     * @param log The log.
     * @throws IOException When the new file cannot be made or put in place.
     */
    static void begin(final Path log) throws IOException {
        final Path next = log.resolveSibling(log.getFileName() + NEXT);
        Files.deleteIfExists(next);
        Files.newOutputStream(
                        next,
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS)
                .close();
        Files.move(next, log, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * Begins a helper's log anew and gives the redirect its output goes to: appended to a file of
     * its own, never the last run's emptied in place.
     *
     * @param log The helper's log.
     * @return The redirect for the helper's output.
     * @throws IOException When the new file cannot be made or put in place.
     */
    static ProcessBuilder.Redirect into(final Path log) throws IOException {
        begin(log);
        return ProcessBuilder.Redirect.appendTo(log.toFile());
    }
}
