package org.fuin.porelo.app;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * This process's standard input, and whether a person types into it.
 *
 * <p>Asked of {@code /proc/self/fd/0} rather than of {@link System#console()}: the console asks
 * about standard output too, so a command whose output is piped would take a terminal on its input
 * for none, and read a passphrase someone types there with echo on.
 *
 * @param stream What it reads.
 * @param terminal Whether it is a terminal.
 */
public record StandardInput(InputStream stream, boolean terminal) {

    /**
     * Returns this process's own.
     *
     * @return Standard input.
     */
    public static StandardInput real() {
        return new StandardInput(System.in, isTerminal(Path.of("/proc/self/fd/0")));
    }

    /**
     * Returns an input that holds nothing and is no terminal, which is what a test's context reads
     * unless it says otherwise.
     *
     * @return An empty input.
     */
    public static StandardInput none() {
        return new StandardInput(new ByteArrayInputStream(new byte[0]), false);
    }

    /**
     * Tells whether a file descriptor's link names a terminal device.
     *
     * @param descriptor The link, such as {@code /proc/self/fd/0}.
     * @return Whether it is a terminal; false when it cannot be read.
     */
    static boolean isTerminal(final Path descriptor) {
        try {
            final String target = Files.readSymbolicLink(descriptor).toString();
            return target.startsWith("/dev/pts/") || target.startsWith("/dev/tty");
        } catch (final java.io.IOException | UnsupportedOperationException ex) {
            return false;
        }
    }
}
