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
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Sends the lines an unattended run writes to its log while it runs, as the agent shows them.
 *
 * <p><strong>It never holds the run.</strong> The log is read on one thread and the lines are sent
 * on another, through a bounded queue: a caller that stops reading blocks only the sending thread,
 * and lines that do not fit are counted, not waited for. The log is read in bounded batches, each
 * formatted in parts well below a Varlink message's limit, a line longer than {@link #MOST_LINE} is
 * sent in pieces cut at a code point, and a log that is a link is not read.
 *
 * <p><strong>No thread is interrupted.</strong> Either may be in a socket's write, and an
 * interrupted write closes the socket: the agent's, which formats the lines, or the caller's, which
 * still waits for the final reply. Both stop by a flag instead. While a run lasts the sender is the
 * only one writing to the caller; {@link #close} returns once it has stopped, so whatever the start
 * writes next is written alone.
 */
public final class RunFollower implements AutoCloseable {

    /** The most lines waiting to be sent; more are counted and dropped. */
    public static final int MOST_WAITING = 1000;

    /** The most bytes of one line sent; a longer one is sent in pieces. */
    public static final int MOST_LINE = 16 * 1024;

    /** How long the end waits for the waiting lines to be sent before it drops the rest. */
    public static final Duration LAST_WAIT = Duration.ofSeconds(2);

    /** The most bytes read in one go, and four times the characters formatted in one call. */
    static final int MOST_BATCH = 512 * 1024;

    private static final int PIECE = 64 * 1024;

    private final Path log;
    private final Function<List<String>, List<@Nullable String>> formatting;
    private final Consumer<String> send;
    private final Duration poll;
    private final BlockingQueue<String> waiting = new ArrayBlockingQueue<>(MOST_WAITING);
    private final AtomicLong dropped = new AtomicLong();
    private final ByteArrayOutputStream partial = new ByteArrayOutputStream();
    private volatile boolean ending;
    private volatile boolean stopping;
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
        this.send = send;
        this.poll = poll;
        this.sender = Thread.ofVirtual().name("run-lines-send").unstarted(this::sending);
        this.reader = Thread.ofVirtual().name("run-lines-read").unstarted(this::following);
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
     * @return The count, final once {@link #close} returned.
     */
    public long dropped() {
        return dropped.get();
    }

    private void sending() {
        while (true) {
            final String line;
            try {
                line = waiting.poll(10, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
            if (line == null) {
                if (stopping || ending && !reader.isAlive()) {
                    return;
                }
            } else if (stopping) {
                dropped.incrementAndGet();
                return;
            } else {
                try {
                    send.accept(line);
                } catch (RuntimeException ex) {
                    // A caller gone: nothing more is sent, the run goes on.
                    stopping = true;
                }
            }
        }
    }

    private void following() {
        while (!ending) {
            read(false);
            try {
                Thread.sleep(poll.toMillis());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        // Once more, with the last line even without its end: the run was killed mid-line.
        read(true);
    }

    private void read(final boolean last) {
        boolean more = true;
        while (more) {
            final List<String> lines = new ArrayList<>();
            more = readBatch(lines);
            if (last && !more && partial.size() > 0) {
                lines.add(partial.toString(StandardCharsets.UTF_8));
                partial.reset();
            }
            queue(lines);
            // While the run lasts one batch a poll; at its end, all that is left, a batch at a
            // time.
            more = more && last;
        }
    }

    /** Reads at most {@link #MOST_BATCH} bytes; answers whether more is waiting. */
    private boolean readBatch(final List<String> lines) {
        if (!Files.isRegularFile(log, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try (RandomAccessFile file = new RandomAccessFile(log.toFile(), "r")) {
            final long length = file.length();
            if (length < position) {
                // Emptied under the follower: start again from its beginning.
                position = 0;
                partial.reset();
            }
            final byte[] piece = new byte[PIECE];
            long read = 0;
            while (position < length && read < MOST_BATCH) {
                file.seek(position);
                final int got = file.read(piece, 0, (int) Math.min(PIECE, length - position));
                if (got <= 0) {
                    break;
                }
                position += got;
                read += got;
                split(piece, got, lines);
            }
            return position < length;
        } catch (IOException ex) {
            // Looked at again on the next poll.
            return false;
        }
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
                    lines.add(cutAtCodePoint());
                }
            }
        }
    }

    /** Takes the partial line up to its last whole code point, keeping the rest for the next. */
    private String cutAtCodePoint() {
        final byte[] bytes = partial.toByteArray();
        int start = bytes.length - 1;
        while (start > 0 && (bytes[start] & 0b1100_0000) == 0b1000_0000) {
            start--;
        }
        final int lead = bytes[start] & 0xff;
        final int width =
                lead < 0x80 ? 1 : lead >= 0xf0 ? 4 : lead >= 0xe0 ? 3 : lead >= 0xc0 ? 2 : 1;
        final int end = start + width <= bytes.length ? bytes.length : start;
        partial.reset();
        partial.write(bytes, end, bytes.length - end);
        return new String(Arrays.copyOf(bytes, end), StandardCharsets.UTF_8);
    }

    private void queue(final List<String> lines) {
        for (final List<String> part : parts(lines)) {
            List<@Nullable String> shown;
            try {
                shown = formatting.apply(part);
            } catch (RuntimeException ex) {
                // A formatter that fails shows nothing it would have hidden: the lines are counted
                // as not sent rather than sent as they are.
                dropped.addAndGet(part.size());
                continue;
            }
            for (final String line : shown) {
                if (line != null && (stopping || !waiting.offer(line))) {
                    dropped.incrementAndGet();
                }
            }
        }
    }

    /**
     * Splits lines into parts of at most a quarter of {@link #MOST_BATCH} characters, so one format
     * call stays far below a Varlink message's limit even escaped.
     *
     * @param lines The lines.
     * @return The parts, in order.
     */
    static List<List<String>> parts(final List<String> lines) {
        final List<List<String>> parts = new ArrayList<>();
        List<String> part = new ArrayList<>();
        long size = 0;
        for (final String line : lines) {
            if (!part.isEmpty() && size + line.length() > MOST_BATCH / 4) {
                parts.add(part);
                part = new ArrayList<>();
                size = 0;
            }
            part.add(line);
            size += line.length();
        }
        if (!part.isEmpty()) {
            parts.add(part);
        }
        return parts;
    }

    /**
     * Reads what the run wrote last, sends what waits within {@link #LAST_WAIT}, drops the rest,
     * and returns once neither thread runs any more.
     *
     * <p>The sender may still be in one write to a caller that does not read; that write is waited
     * for, since the start's own final reply would wait for the same caller, and the two must not
     * write at once.
     */
    @Override
    public void close() {
        ending = true;
        try {
            reader.join();
            final long until = System.nanoTime() + LAST_WAIT.toNanos();
            while (!waiting.isEmpty() && sender.isAlive() && System.nanoTime() < until) {
                TimeUnit.MILLISECONDS.sleep(10);
            }
            stopping = true;
            sender.join();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } finally {
            dropped.addAndGet(waiting.size());
            waiting.clear();
        }
    }
}
