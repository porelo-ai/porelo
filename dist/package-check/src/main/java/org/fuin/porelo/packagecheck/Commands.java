package org.fuin.porelo.packagecheck;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Runs the package managers' own tools, which remain the authority on what a package holds. */
final class Commands {

    /**
     * What a command printed, and how it ended.
     *
     * @param exit its exit code
     * @param output standard output and standard error, in the order they arrived
     */
    record Result(int exit, String output) {

        /**
         * Says whether the command succeeded.
         *
         * @return whether it exited zero
         */
        boolean ok() {
            return exit == 0;
        }
    }

    private Commands() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Runs one command to its end, keeping only what it wrote to standard output.
     *
     * <p>Standard error is discarded because a file list is read from standard output, and podman
     * reports an image pull on standard error.
     *
     * @param command the program and its arguments
     * @return what it printed and how it ended
     */
    static Result run(List<String> command) {
        return pipe(List.of(command), false);
    }

    /**
     * Runs one command to its end, keeping standard output and standard error together.
     *
     * @param command the program and its arguments
     * @return what it printed, both streams, and how it ended
     */
    static Result runMerged(List<String> command) {
        return pipe(List.of(command), true);
    }

    /**
     * How long one command may run: an image pull or an install that hangs is ended, so the checks
     * after it still run and report. The whole check took 109 to 133 seconds in four green builds
     * (2026-10-17), and the job allows twenty minutes.
     */
    static final Duration LIMIT = Duration.ofMinutes(5);

    /** The exit a command ended at its limit answers with, as {@code timeout(1)} does. */
    static final int TIMED_OUT = 124;

    /**
     * Runs commands with each one's output fed to the next, as a shell pipe does, within {@link
     * #LIMIT}.
     *
     * @param commands the commands, first to last
     * @param merged whether the last one's standard error is kept with its output
     * @return what the last one printed, and the first non-zero exit of any of them
     */
    static Result pipe(List<List<String>> commands, boolean merged) {
        return pipe(commands, merged, LIMIT);
    }

    /**
     * Runs commands with each one's output fed to the next, ending all of them at a limit.
     *
     * @param commands the commands, first to last
     * @param merged whether the last one's standard error is kept with its output
     * @param limit how long they may run together
     * @return what the last one printed, and the first non-zero exit of any of them; {@link
     *     #TIMED_OUT} with a line saying so when the limit was reached
     */
    static Result pipe(List<List<String>> commands, boolean merged, Duration limit) {
        final List<ProcessBuilder> builders = commands.stream().map(ProcessBuilder::new).toList();
        // Nobody reads it, and an unread pipe that fills blocks the process writing to it.
        builders.forEach(builder -> builder.redirectError(ProcessBuilder.Redirect.DISCARD));
        builders.getLast().redirectErrorStream(merged);
        try {
            final List<Process> processes = ProcessBuilder.startPipeline(builders);
            // Read beside the wait, so the limit holds while the last command still writes or
            // writes nothing.
            final ByteArrayOutputStream output = new ByteArrayOutputStream();
            final Thread reading =
                    Thread.ofVirtual()
                            .start(
                                    () -> {
                                        try (InputStream in =
                                                processes.getLast().getInputStream()) {
                                            in.transferTo(output);
                                        } catch (IOException ex) {
                                            // The command was ended at its limit, and what it wrote
                                            // until then is kept.
                                        }
                                    });
            final long deadline = System.nanoTime() + limit.toNanos();
            int exit = 0;
            for (final Process process : processes) {
                if (!process.waitFor(
                        Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) {
                    processes.forEach(Process::destroyForcibly);
                    reading.join(Duration.ofSeconds(5));
                    return new Result(
                            TIMED_OUT,
                            output.toString(StandardCharsets.UTF_8)
                                    + "\n"
                                    + commands.getLast()
                                    + " did not end within "
                                    + limit.toSeconds()
                                    + " s and was ended\n");
                }
                if (exit == 0) {
                    exit = process.exitValue();
                }
            }
            reading.join();
            return new Result(exit, output.toString(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new UncheckedIOException("could not run " + commands, ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted running " + commands, ex);
        }
    }
}
