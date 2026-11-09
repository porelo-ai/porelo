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
 * for none. Secret readers must refuse when this says terminal but no console can hide input.
 *
 * @param stream What it reads.
 * @param terminal Whether it is a terminal.
 * @param outputTerminal Whether standard output is a terminal that can show a question.
 */
public record StandardInput(InputStream stream, boolean terminal, boolean outputTerminal) {

    /**
     * Makes a context whose input and output have the same terminal state.
     *
     * @param stream What it reads.
     * @param terminal Whether input and output are terminals.
     */
    public StandardInput(InputStream stream, boolean terminal) {
        this(stream, terminal, terminal);
    }

    /**
     * Tells whether a question can be seen and answered on the standard streams.
     *
     * @return Whether both streams are terminals.
     */
    public boolean canPrompt() {
        return terminal && outputTerminal;
    }

    /**
     * Returns this process's own.
     *
     * @return Standard input.
     */
    public static StandardInput real() {
        return new StandardInput(
                System.in,
                isTerminal(Path.of("/proc/self/fd/0")),
                isTerminal(Path.of("/proc/self/fd/1")));
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
            return target.startsWith("/dev/pts/")
                    || target.startsWith("/dev/tty")
                    || target.equals("/dev/console");
        } catch (final java.io.IOException | UnsupportedOperationException ex) {
            return false;
        }
    }
}
