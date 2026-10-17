package org.fuin.porelo.legs;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.LongSupplier;
import org.fuin.porelo.machines.Ssh;

/**
 * Runs a long phase of a leg on the machine with its output reaching the job's log as it is written.
 * <p>
 * {@link Ssh#run(String)} returns what a command wrote once it has ended: a build of half an hour reached the log as
 * one block at its end, and until then nobody could tell a slow build from a hung one. Here every line is printed as it
 * comes, output and errors side by side, and a phase that prints nothing for a while says what it is and how long it
 * has taken, once a minute.
 */
final class Streamed {

    /** How long a phase may be quiet before the leg says it is still in it. */
    static final Duration QUIET = Duration.ofMinutes(1);

    private Streamed() {
    }

    /**
     * Runs a command on the machine, printing its output as it comes.
     *
     * @param ssh The machine.
     * @param command What to run.
     * @param phase What the phase is called in the quiet line, such as {@code building}.
     * @return What the command exited with, {@code -1} when the machine did not say.
     * @throws IOException If the command cannot be run.
     */
    static int run(final Ssh ssh, final String command, final String phase) throws IOException {
        final Progress progress = new Progress(phase, System.out, System::currentTimeMillis, QUIET);
        final Thread ticking = Thread.ofPlatform().daemon().name("leg-progress").start(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    Thread.sleep(1_000);
                    progress.tick();
                }
            } catch (final InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        });
        try (var session = ssh.client().startSession()) {
            final var exec = session.exec(command);
            pump(exec.getInputStream(), exec.getErrorStream(), progress);
            exec.join();
            final Integer status = exec.getExitStatus();
            return status == null ? -1 : status;
        } finally {
            ticking.interrupt();
        }
    }

    /**
     * Prints a command's output and errors line by line, side by side, until both have ended.
     *
     * @param out Its output.
     * @param err Its errors.
     * @param progress Where each line goes.
     */
    static void pump(final InputStream out, final InputStream err, final Progress progress) {
        final Thread errors = Thread.ofPlatform().daemon().name("leg-stderr").start(() -> lines(err, progress));
        lines(out, progress);
        try {
            errors.join();
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static void lines(final InputStream in, final Progress progress) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                progress.line(line);
            }
        } catch (final IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /**
     * What a phase has printed, and when it last did.
     */
    static final class Progress {

        private final String phase;

        private final PrintStream log;

        private final LongSupplier millis;

        private final long quiet;

        private final long started;

        private long lastPrinted;

        /**
         * Starts a phase.
         *
         * @param phase What it is called.
         * @param log Where its lines go.
         * @param millis The clock.
         * @param quiet How long it may be quiet before it says so.
         */
        Progress(final String phase, final PrintStream log, final LongSupplier millis, final Duration quiet) {
            this.phase = phase;
            this.log = log;
            this.millis = millis;
            this.quiet = quiet.toMillis();
            this.started = millis.getAsLong();
            this.lastPrinted = started;
        }

        /**
         * Prints one line of the command's own.
         *
         * @param line The line.
         */
        synchronized void line(final String line) {
            log.println(line);
            log.flush();
            lastPrinted = millis.getAsLong();
        }

        /** Says the phase is still running when nothing was printed for the quiet time. */
        synchronized void tick() {
            final long now = millis.getAsLong();
            if (now - lastPrinted >= quiet) {
                log.println("  ... " + phase + ", " + (now - started) / 60_000 + " min so far");
                log.flush();
                lastPrinted = now;
            }
        }
    }
}
