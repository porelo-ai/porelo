package org.fuin.porelo.app;

import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandRunner;
import org.jspecify.annotations.Nullable;

/**
 * Holds a person's terminal with its echo off for the whole of a read, and gives it back as it was.
 *
 * <p><strong>Why not {@code Console.readPassword} alone.</strong> It switches echo off for one line
 * and back on before it returns. A key pasted at the prompt is many lines read one after the other,
 * so between two reads the terminal echoed again, and paste bytes arriving then landed in the
 * scrollback. Echo switched off once, before the prompt, and back after the last line closes that
 * gap; {@code readPassword} finds it off and leaves it so.
 *
 * <p><strong>Given back on a signal too.</strong> Ctrl-C ends the process without the {@code
 * finally}, and a terminal left without echo looks broken to whoever types next; the restore is
 * armed as a {@link Teardown}, as {@link TerminalAttach} saves and restores with the same {@code
 * stty} calls.
 */
final class EchoOff implements AutoCloseable {

    /** Holds nothing: the terminal could not be saved, so nothing is changed or restored. */
    private static final EchoOff NONE = new EchoOff(null);

    private final @Nullable Teardown restore;

    private EchoOff(final @Nullable Teardown restore) {
        this.restore = restore;
    }

    /**
     * Switches the controlling terminal's echo off until {@link #close}.
     *
     * <p>A terminal whose settings cannot be saved is left alone: changing what cannot be put back
     * would be worse, and {@code readPassword} still reads each line without echo.
     *
     * @param runner Runs {@code stty} against {@code /dev/tty}.
     * @return The hold; close it to give the terminal back.
     */
    static EchoOff hold(final CommandRunner runner) {
        final String saved;
        try {
            saved = runner.runOrFail(Command.of("sh", "-c", "stty -g < /dev/tty")).trimmedOutput();
        } catch (final RuntimeException ex) {
            return NONE;
        }
        if (saved.isEmpty()) {
            return NONE;
        }
        // Armed before echo goes off, so a signal at any point after finds the way back.
        final Teardown restore = Teardown.arm(() -> restore(runner, saved));
        try {
            runner.runOrFail(Command.of("sh", "-c", "stty -echo < /dev/tty"));
        } catch (final RuntimeException ex) {
            // Half done or not at all: the saved settings are what the terminal goes back to.
            restore.close();
            return NONE;
        }
        return new EchoOff(restore);
    }

    /** Gives the terminal back its saved settings, once. */
    @Override
    public void close() {
        if (restore != null) {
            restore.close();
        }
    }

    /** Restores the saved settings; a failure leaves nothing more to do. */
    private static void restore(final CommandRunner runner, final String saved) {
        try {
            // The saved value is an argument, never interpreted as shell code.
            runner.runOrFail(
                    Command.of("sh", "-c", "stty \"$1\" < /dev/tty", "porelo-terminal", saved));
        } catch (final RuntimeException ex) {
            // Nothing else can put it back; 'stty sane' or 'reset' is the person's way.
        }
    }
}
