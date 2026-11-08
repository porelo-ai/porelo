package org.fuin.porelo.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Sends the lines an unattended run writes to its log while it runs, as the agent shows them.
 *
 * <p><strong>It never holds the run.</strong> The log is read on one thread and the lines are sent
 * on another, through a bounded queue: a caller that stops reading blocks only the sending thread,
 * and lines that do not fit are counted and said, not waited for. The run and its timeout go on as
 * if nobody followed it. The log is read in bounded pieces, a line longer than {@link #MOST_LINE}
 * is sent cut, and a log that is a link is not read.
 */
public final class RunFollower implements AutoCloseable {

    /** The most lines waiting to be sent; more are counted and dropped. */
    public static final int MOST_WAITING = 1000;

    /** The most bytes of one line sent; a longer one is sent in pieces. */
    public static final int MOST_LINE = 16 * 1024;

    /** How long the end waits for the waiting lines to be sent to a caller that reads slowly. */
    public static final Duration LAST_WAIT = Duration.ofSeconds(2);

    private static final int PIECE = 64 * 1024;

    private final Path log;
    private final Function<List<String>, List<@Nullable String>> formatting;
    private final Duration poll;
    private final BlockingQueue<String> waiting = new ArrayBlockingQueue<>(MOST_WAITING);
    private final AtomicBoolean ending = new AtomicBoolean();
    private final AtomicLong dropped = new AtomicLong();
    private final java.util.concurrent.atomic.AtomicInteger sending =
            new java.util.concurrent.atomic.AtomicInteger();
    private final ByteArrayOutputStream partial = new ByteArrayOutputStream();
    private long position;
    private final Thread reader;
    private final Thread sender;

    private RunFollower(
            final Path log,
            final Function<List<String>, List<@Nullable String>> formatting,
            final Consumer<String> send,
            final Duration poll) {
        this.log = log;
        this.formatting = formatting;
        this.poll = poll;
        this.sender =
                Thread.ofVirtual()
                        .name("run-lines-send")
                        .unstarted(
                                () -> {
                                    try {
                                        while (true) {
                                            final String line = waiting.take();
                                            sending.incrementAndGet();
                                            try {
                                                send.accept(line);
                                            } finally {
                                                sending.decrementAndGet();
                                            }
                                        }
                                    } catch (InterruptedException ex) {
                                        Thread.currentThread().interrupt();
                                    } catch (RuntimeException ex) {
                                        // A caller gone: nothing more is sent, the run goes on.
                                    }
                                });
        this.reader = Thread.ofVirtual().name("run-lines-read").unstarted(this::follow);
    }

    /**
     * Starts following a run's log.
     *
     * @param log The run's log, emptied before the run.
     * @param formatting How the agent shows its lines, {@code null} for a line it hides.
     * @param send Where each line goes; may block.
     * @param poll How often the log is looked at.
     * @return The follower, to close when the run ended.
     */
    public static RunFollower start(
            final Path log,
            final Function<List<String>, List<@Nullable String>> formatting,
            final Consumer<String> send,
            final Duration poll) {
        final RunFollower follower = new RunFollower(log, formatting, send, poll);
        follower.sender.start();
        follower.reader.start();
        return follower;
    }

    /**
     * Returns how many lines were not sent because the caller read too slowly.
     *
     * @return The count.
     */
    public long dropped() {
        return dropped.get() + waiting.size();
    }

    private void follow() {
        try {
            while (!ending.get()) {
                read(false);
                Thread.sleep(poll.toMillis());
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        // Once more, with the last line even without its end: the run was killed mid-line.
        read(true);
    }

    private void read(final boolean last) {
        final List<String> lines = new ArrayList<>();
        if (Files.isRegularFile(log, LinkOption.NOFOLLOW_LINKS)) {
            try (RandomAccessFile file = new RandomAccessFile(log.toFile(), "r")) {
                long length = file.length();
                if (length < position) {
                    // Emptied under the follower: start again from its beginning.
                    position = 0;
                    partial.reset();
                }
                final byte[] piece = new byte[PIECE];
                while (position < length) {
                    file.seek(position);
                    final int read = file.read(piece, 0, (int) Math.min(PIECE, length - position));
                    if (read <= 0) {
                        break;
                    }
                    position += read;
                    split(piece, read, lines);
                    if (!last && lines.size() >= MOST_WAITING) {
                        break;
                    }
                    length = file.length();
                }
            } catch (IOException ex) {
                // Looked at again on the next poll.
            }
        }
        if (last && partial.size() > 0) {
            lines.add(partial.toString(StandardCharsets.UTF_8));
            partial.reset();
        }
        queue(lines);
    }

    private void split(final byte[] piece, final int read, final List<String> lines) {
        for (int i = 0; i < read; i++) {
            final byte b = piece[i];
            if (b == '\n') {
                lines.add(partial.toString(StandardCharsets.UTF_8));
                partial.reset();
            } else {
                partial.write(b);
                if (partial.size() >= MOST_LINE) {
                    lines.add(partial.toString(StandardCharsets.UTF_8));
                    partial.reset();
                }
            }
        }
    }

    private void queue(final List<String> lines) {
        if (lines.isEmpty()) {
            return;
        }
        List<@Nullable String> shown;
        try {
            shown = formatting.apply(lines);
        } catch (RuntimeException ex) {
            shown = new ArrayList<>(lines);
        }
        for (final String line : shown) {
            if (line != null && !waiting.offer(line)) {
                dropped.incrementAndGet();
            }
        }
    }

    /** Reads what the run wrote last, sends it within {@link #LAST_WAIT}, and stops. */
    @Override
    public void close() {
        ending.set(true);
        reader.interrupt();
        try {
            reader.join(LAST_WAIT.toMillis());
            final long until = System.nanoTime() + LAST_WAIT.toNanos();
            while ((!waiting.isEmpty() || sending.get() > 0) && System.nanoTime() < until) {
                TimeUnit.MILLISECONDS.sleep(10);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } finally {
            sender.interrupt();
        }
    }
}
