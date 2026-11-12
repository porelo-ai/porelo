package org.fuin.porelo.app;

import java.io.PrintWriter;
import java.util.List;
import org.fuin.porelo.core.process.Command;
import org.jspecify.annotations.Nullable;

/** Returns a person's terminal to its saved settings after attaching to a task's session. */
final class TerminalAttach {

    static final String RESETS =
            "\033[?1000l\033[?1002l\033[?1003l\033[?1006l\033[?1015l"
                    + "\033[?1004l\033[?2004l\033[>4;0m\033[?1049l\033[?25h";

    /** No instances. */
    private TerminalAttach() {}

    /**
     * Runs an attach, restoring terminal settings and modes before returning or propagating its
     * failure. Without a terminal the command runs with no terminal commands or output added.
     *
     * <p>Settings that cannot be saved do not keep a person out of their task: the attach runs, the
     * modes are still reset after it, and one line says the settings were not.
     *
     * @param context Runs the attach with inherited I/O and stty with captured output.
     * @param arguments The attach's program and arguments.
     * @param terminal Whether standard input and output are a terminal.
     * @param out The attach's terminal output stream.
     * @param err Where a save or restore failure is reported, in one line each.
     * @return The attach's exit code.
     */
    static int run(
            final PoreloContext context,
            final List<String> arguments,
            final boolean terminal,
            final PrintWriter out,
            final PrintWriter err) {
        if (!terminal) {
            return context.exec().applyAsInt(arguments);
        }
        final String saved = save(context, err);
        try {
            return context.exec().applyAsInt(arguments);
        } finally {
            // An interrupted wait must not prevent the runner from waiting for stty's restore.
            final boolean interrupted = Thread.interrupted();
            try {
                if (saved != null) {
                    restore(context, saved, err);
                }
                out.print(RESETS);
                out.flush();
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /** Saves the controlling terminal's settings before it is handed over; null when it cannot. */
    private static @Nullable String save(final PoreloContext context, final PrintWriter err) {
        String saved;
        try {
            saved =
                    context.runner()
                            .runOrFail(Command.of("sh", "-c", "stty -g < /dev/tty"))
                            .trimmedOutput();
        } catch (RuntimeException ex) {
            saved = "";
        }
        if (saved.isEmpty()) {
            err.println(
                    "porelo: could not save terminal settings; they are not restored afterwards");
            err.flush();
            return null;
        }
        return saved;
    }

    /** Restores the saved settings without changing the attach's outcome. */
    private static void restore(
            final PoreloContext context, final String saved, final PrintWriter err) {
        try {
            // The saved value is an argument, never interpreted as shell code.
            context.runner()
                    .runOrFail(
                            Command.of(
                                    "sh",
                                    "-c",
                                    "stty \"$1\" < /dev/tty",
                                    "porelo-terminal",
                                    saved));
        } catch (RuntimeException ex) {
            err.println("porelo: could not restore terminal settings");
            err.flush();
        }
    }
}
