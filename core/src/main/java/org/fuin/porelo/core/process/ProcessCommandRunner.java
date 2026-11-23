package org.fuin.porelo.core.process;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Runs external programs with {@link ProcessBuilder}.
 *
 * <p>Output is drained on virtual threads. A program that fills a pipe buffer while nobody reads it
 * blocks forever, and {@code podman build} on a large base image fills one easily.
 */
public class ProcessCommandRunner implements CommandRunner {

    private final Duration timeout;

    /** Constructor with a default timeout of ten minutes. */
    public ProcessCommandRunner() {
        this(Duration.ofMinutes(10));
    }

    /**
     * Constructor with a timeout.
     *
     * @param timeout How long a program may run before it is killed.
     */
    public ProcessCommandRunner(Duration timeout) {
        this.timeout = timeout;
    }

    @Override
    public CommandResult run(Command command) {
        return running(command, null, null);
    }

    @Override
    public CommandResult run(
            final Command command, final java.util.function.Consumer<String> lines) {
        return running(command, lines, null);
    }

    @Override
    public CommandResult streamOutput(
            final Command command, final java.util.function.Consumer<String> lines) {
        return running(command, null, lines);
    }

    private CommandResult running(
            final Command command,
            final java.util.function.@org.jspecify.annotations.Nullable Consumer<String> lines,
            final java.util.function.@org.jspecify.annotations.Nullable Consumer<String> output) {

        final ProcessBuilder builder = new ProcessBuilder(command.arguments());
        if (command.workingDirectory() != null) {
            builder.directory(command.workingDirectory().toFile());
        }
        if (!command.inheritsEnvironment()) {
            // A chosen environment: nothing of the caller's reaches it but what the command names.
            builder.environment().clear();
        }
        builder.environment().putAll(command.environment());

        try {

            final Process process = start(builder);

            final Streamed streamed =
                    output == null ? null : new Streamed(output, () -> end(process));
            final Drain out = new Drain(process.getInputStream(), null, streamed);
            final Handing handing = lines == null ? null : Handing.to(lines);
            final Drain err = new Drain(process.getErrorStream(), handing, null);
            final Thread outThread = Thread.ofVirtual().start(out);
            final Thread errThread = Thread.ofVirtual().start(err);

            // Written on a thread of its own, so the limit runs from the start: written first, a
            // child that did not read it blocked the caller unbounded, and one that exited without
            // reading it failed a command that had succeeded.
            final Thread inputThread = Thread.ofVirtual().start(() -> writeInput(process, command));

            // The shorter of the runner's limit and the command's own.
            final Duration own = command.timeout();
            final Duration limit = own != null && own.compareTo(timeout) < 0 ? own : timeout;
            try {
                if (!process.waitFor(limit.toMillis(), TimeUnit.MILLISECONDS)) {
                    end(process);
                    finish(process, outThread, errThread);
                    throw CommandException.timedOut(command, limit, err.text());
                }
                finish(process, outThread, errThread);
                if (streamed != null) {
                    return streamedResult(command, process, streamed, err.text());
                }
                return new CommandResult(
                        command, process.exitValue(), out.text(), err.text(), out.cut());
            } catch (InterruptedException ex) {
                // Called off: the program and everything it started end now, and the caller hears
                // a cancellation, not a failure of the program's own.
                end(process);
                Thread.currentThread().interrupt();
                throw CommandException.cancelled(command, err.text());
            } finally {
                joinInput(inputThread);
                if (handing != null) {
                    handing.end();
                }
                if (streamed != null) {
                    streamed.close();
                }
            }

        } catch (IOException ex) {
            throw new CommandException(command, ex);
        }
    }

    /**
     * What a run whose standard output was handed over left behind, once every line of it was: one
     * still held open by a grandchild, cut short by a line too long, or ended by its consumer is
     * not.
     */
    private static CommandResult streamedResult(
            final Command command,
            final Process process,
            final Streamed streamed,
            final String standardError) {
        if (!streamed.close()) {
            throw new CommandException(
                    command,
                    new IOException("its standard output did not end with it"),
                    standardError);
        }
        final Exception failure = streamed.failure();
        if (failure instanceof RuntimeException consumer) {
            throw consumer;
        }
        if (failure != null) {
            throw new CommandException(command, failure, standardError);
        }
        return new CommandResult(command, process.exitValue(), "", standardError);
    }

    /** Starts the child; replaceable for tests of pipe shutdown ordering. */
    protected Process start(ProcessBuilder builder) throws IOException {
        return builder.start();
    }

    /**
     * How long the output is waited for once the program has ended: a grandchild may hold it open
     * for ever.
     */
    private static final long OUTPUT_GRACE_MILLIS = 2000;

    /** Ends a program and everything it started that is still its own. */
    private static void end(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private static void joinInput(Thread thread) {
        boolean interrupted = false;
        while (thread.isAlive()) {
            try {
                thread.join();
            } catch (InterruptedException ex) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Waits a while for the output, and then takes what has arrived: a grandchild that inherited it
     * - a helper of git's against an upstream that feeds slowly - kept it open, and waiting for it
     * without a limit held the caller for ever. Its reader ends by itself once the grandchild does.
     */
    private static void finish(Process process, Thread outThread, Thread errThread)
            throws InterruptedException {
        outThread.join(OUTPUT_GRACE_MILLIS);
        errThread.join(OUTPUT_GRACE_MILLIS);
    }

    private static void writeInput(Process process, Command command) {
        try (OutputStream in = process.getOutputStream()) {
            if (command.binaryInput() != null) {
                in.write(command.binaryInput());
            } else if (command.input() != null) {
                in.write(command.input().getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException ex) {
            // The program closed its input without reading all of it, which it is entitled to do.
        }
    }

    /**
     * Takes lines to a consumer on a thread of its own, so a consumer that is slow or stuck holds
     * neither the program nor what it said, and ends with the run: a grandchild holding the stream
     * open reaches nobody after that. Lines beyond what waits here are lost to the consumer only.
     */
    private static final class Handing {

        private static final int WAITING = 1024;

        private final java.util.concurrent.BlockingQueue<String> waiting =
                new java.util.concurrent.ArrayBlockingQueue<>(WAITING);

        private final java.util.function.Consumer<String> consumer;

        private volatile boolean ended;

        private volatile boolean abandoned;

        private @org.jspecify.annotations.Nullable Thread thread;

        private Handing(final java.util.function.Consumer<String> consumer) {
            this.consumer = consumer;
        }

        private static Handing to(final java.util.function.Consumer<String> consumer) {
            final Handing handing = new Handing(consumer);
            handing.thread = Thread.ofVirtual().start(handing::hand);
            return handing;
        }

        private void offer(final String line) {
            if (!ended) {
                waiting.offer(line);
            }
        }

        private void hand() {
            try {
                while (!abandoned) {
                    final String line =
                            waiting.poll(50, java.util.concurrent.TimeUnit.MILLISECONDS);
                    if (line != null) {
                        take(line);
                    } else if (ended) {
                        return;
                    }
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }

        private void take(final String line) {
            try {
                consumer.accept(line);
            } catch (RuntimeException ex) {
                // Only that line is lost: what the program does is not the consumer's to end.
            }
        }

        /** No line is taken any more, and what still waits has a moment to arrive. */
        private void end() {
            ended = true;
            final Thread running = java.util.Objects.requireNonNull(thread);
            boolean interrupted = Thread.interrupted();
            try {
                running.join(OUTPUT_GRACE_MILLIS);
            } catch (InterruptedException ex) {
                interrupted = true;
            }
            abandoned = true;
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Hands every line of a stream to a consumer on the thread that reads it, in order and whole,
     * so none waits and none is lost. A line longer than the longest held, or a consumer that
     * fails, ends the program and is the run's failure; no line is handed once the run has ended.
     */
    private static final class Streamed {

        /** The longest line held: a program's output may hold no end at all. */
        private static final int LONGEST = 1024 * 1024;

        private final java.util.function.Consumer<String> consumer;

        private final Runnable stop;

        private final java.io.ByteArrayOutputStream line = new java.io.ByteArrayOutputStream();

        private volatile @org.jspecify.annotations.Nullable Exception failure;

        private boolean ended;

        private boolean closed;

        private Streamed(final java.util.function.Consumer<String> consumer, final Runnable stop) {
            this.consumer = consumer;
            this.stop = stop;
        }

        private void take(final byte[] bytes, final int length) {
            for (int index = 0; index < length && failure == null; index++) {
                if (bytes[index] == '\n') {
                    hand();
                } else if (line.size() < LONGEST) {
                    line.write(bytes[index]);
                } else {
                    fail(
                            new IOException(
                                    "a line of its standard output is longer than "
                                            + LONGEST
                                            + " bytes"));
                }
            }
        }

        private void hand() {
            final String said = line.toString(StandardCharsets.UTF_8).replaceAll("\\r$", "");
            line.reset();
            synchronized (this) {
                if (closed) {
                    return;
                }
                try {
                    consumer.accept(said);
                } catch (RuntimeException ex) {
                    fail(ex);
                }
            }
        }

        private void fail(final Exception why) {
            if (failure == null) {
                failure = why;
                stop.run();
            }
        }

        /** The stream has ended: its last line, if it has no end of its own, is handed too. */
        private void end() {
            if (failure == null && line.size() > 0) {
                hand();
            }
            synchronized (this) {
                ended = true;
            }
        }

        /**
         * No line is handed any more.
         *
         * @return Whether the stream had ended by then.
         */
        private synchronized boolean close() {
            closed = true;
            return ended;
        }

        private @org.jspecify.annotations.Nullable Exception failure() {
            return failure;
        }
    }

    /** Reads one stream to the end, keeping whatever it managed to read if the stream breaks. */
    private static final class Drain implements Runnable {

        private final InputStream stream;

        private final java.io.ByteArrayOutputStream collected = new java.io.ByteArrayOutputStream();

        /**
         * How much of a stream is kept: its end, which is where a failure says why. Held whole, a
         * hostile server's messages through git could run the daemon out of memory.
         */
        private static final int KEPT = 8 * 1024 * 1024;

        private long cut;

        /**
         * Whether the cut fell inside a line, whose rest then opens what is kept: without its start
         * it lost what said whose words it was, such as a forge's {@code remote:}, so it is not
         * kept.
         */
        private boolean cutMidLine;

        private volatile @org.jspecify.annotations.Nullable String text;

        /** The longest line handed over; cut, a secret in it would pass redaction line by line. */
        private static final int LONGEST_LINE = 16 * 1024;

        private final @org.jspecify.annotations.Nullable Handing lines;

        private final java.io.ByteArrayOutputStream line = new java.io.ByteArrayOutputStream();

        private boolean tooLong;

        /** Takes the stream instead of keeping it, or {@code null} when it is kept. */
        private final @org.jspecify.annotations.Nullable Streamed streamed;

        private Drain(
                InputStream stream,
                @org.jspecify.annotations.Nullable Handing lines,
                @org.jspecify.annotations.Nullable Streamed streamed) {
            this.stream = stream;
            this.lines = lines;
            this.streamed = streamed;
        }

        /** Whether the start of what was read is no longer kept. */
        private boolean cut() {
            synchronized (collected) {
                return cut > 0;
            }
        }

        private void handOver(final byte[] bytes, final int length) {
            if (lines == null) {
                return;
            }
            for (int index = 0; index < length; index++) {
                if (bytes[index] == '\n') {
                    endOfLine();
                } else if (line.size() < LONGEST_LINE) {
                    line.write(bytes[index]);
                } else {
                    tooLong = true;
                }
            }
        }

        private void endOfLine() {
            final String said =
                    tooLong
                            ? "[a line too long to show]"
                            : line.toString(StandardCharsets.UTF_8).replaceAll("\\r$", "");
            line.reset();
            tooLong = false;
            java.util.Objects.requireNonNull(lines).offer(said);
        }

        @Override
        public void run() {
            try (stream) {
                final byte[] buffer = new byte[8192];
                int read;
                while ((read = stream.read(buffer)) != -1) {
                    if (streamed != null) {
                        streamed.take(buffer, read);
                        continue;
                    }
                    synchronized (collected) {
                        collected.write(buffer, 0, read);
                        if (collected.size() > 2 * KEPT) {
                            final byte[] all = collected.toByteArray();
                            cut += all.length - KEPT;
                            cutMidLine = all[all.length - KEPT - 1] != '\n';
                            collected.reset();
                            collected.write(all, all.length - KEPT, KEPT);
                        }
                    }
                    handOver(buffer, read);
                }
            } catch (IOException ex) {
                // The process is gone. Whatever was read before that is still the best diagnostic
                // available, so this is not turned into a failure of its own.
                //
                // It used to say exactly that and then throw the bytes away: 'readAllBytes' either
                // returns everything or throws, and the catch set the result to the empty string.
                // So a command that died mid-output was reported with no output at all - the one
                // case where the output is worth most. Reading incrementally is what makes the
                // comment above true.
            } finally {
                if (streamed != null) {
                    streamed.end();
                }
                if (lines != null && (line.size() > 0 || tooLong)) {
                    endOfLine();
                }
                text = snapshot();
            }
        }

        /**
         * What was read: all of it once the stream ended, what has arrived so far while something
         * still holds it.
         */
        private String text() {
            final String done = text;
            return done != null ? done : snapshot();
        }

        private String snapshot() {
            synchronized (collected) {
                if (cut == 0) {
                    return collected.toString(StandardCharsets.UTF_8);
                }
                final byte[] all = collected.toByteArray();
                int from = 0;
                if (cutMidLine) {
                    while (from < all.length && all[from] != '\n') {
                        from++;
                    }
                    from = Math.min(from + 1, all.length);
                }
                return "["
                        + (cut + from)
                        + " bytes cut before this]\n"
                        + new String(all, from, all.length - from, StandardCharsets.UTF_8);
            }
        }
    }
}
