package org.fuin.porelo.daemon;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.FileSystemException;
import java.util.Locale;

/**
 * Words for an answer when a file could not be read or written: what failed and the kind of error,
 * never where.
 *
 * <p>A file system error's message is the absolute path of its file, and an answer reaches a window
 * a person reads, or an agent's tool; the path is this machine's business. The whole error goes to
 * the daemon's own log instead.
 */
final class FileFailure {

    private FileFailure() {}

    /**
     * Returns what failed and the kind of error, and writes the whole error to the daemon's log.
     *
     * @param what What could not be done, naming the task or file by its name: "the logs of task
     *     uc-work cannot be archived".
     * @param ex The error.
     * @return The answer's words, with no path in them.
     */
    static String said(final String what, final IOException ex) {
        return said(what, ex, System.err);
    }

    /**
     * Returns what failed and the kind of error, and writes the whole error to a log.
     *
     * @param what What could not be done.
     * @param ex The error.
     * @param log Where the whole error goes.
     * @return The answer's words, with no path in them.
     */
    static String said(final String what, final IOException ex, final PrintStream log) {
        log.println("porelod: " + what + ": " + ex);
        log.flush();
        return what + ": " + kind(ex);
    }

    /**
     * Returns the kind of a file error, in words: "no such file", "access denied".
     *
     * @param ex The error.
     * @return The words; an error's own message only when it names no path.
     */
    static String kind(final IOException ex) {
        if (ex instanceof FileSystemException found) {
            if (found.getClass() != FileSystemException.class) {
                return words(found.getClass().getSimpleName());
            }
            final String reason = found.getReason();
            return reason == null || reason.isBlank() || named(reason)
                    ? "a file system error"
                    : reason;
        }
        final String message = ex.getMessage();
        return message == null || message.isBlank() || named(message)
                ? "an input or output error"
                : message;
    }

    /** Whether the text may hold a path of this machine. */
    private static boolean named(final String text) {
        return text.indexOf('/') >= 0;
    }

    /** "NoSuchFileException" as "no such file". */
    private static String words(final String name) {
        final String bare =
                name.endsWith("Exception")
                        ? name.substring(0, name.length() - "Exception".length())
                        : name;
        return bare.isEmpty()
                ? "a file system error"
                : bare.replaceAll("(?<=[a-z])(?=[A-Z])", " ").toLowerCase(Locale.ROOT);
    }
}
