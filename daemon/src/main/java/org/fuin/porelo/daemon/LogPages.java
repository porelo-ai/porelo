package org.fuin.porelo.daemon;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.wire.varlink.VarlinkException;

/**
 * Pages read back through a task log or a session recording, from a position this daemon gave out.
 *
 * <p>Read backwards in blocks, never whole: a client scrolling up in a log of 53 MB asks for a
 * page, and a page is what is read. A line longer than a block is still found whole, since its
 * start is looked for block by block.
 */
final class LogPages {

    /** How many lines a page holds at most. */
    static final int MOST_LINES = 1000;

    /** How much is read at a time while looking for line starts. */
    static final int BLOCK = 64 * 1024;

    /**
     * How many bytes a page carries at most. Its lines stay whole; only one line longer than this
     * alone is sent cut to its first part, so a line without end in a log cannot fill the daemon's
     * memory.
     */
    static final int MOST_BYTES = 4 * 1024 * 1024;

    private LogPages() {}

    /**
     * Bytes at positions a client was given, from the oldest one kept to the end.
     *
     * <p>A log holds every byte from 0; a recording cut at its bound holds only its newest part,
     * and its positions go on counting from where it began.
     */
    interface Bytes {

        /**
         * Returns the oldest position kept.
         *
         * @return The position; 0 for a log.
         */
        long start();

        /**
         * Returns the position after the last byte.
         *
         * @return The position.
         */
        long end();

        /**
         * Reads bytes that lie between {@link #start()} and {@link #end()}.
         *
         * @param position Where the first one is.
         * @param into Where they go, from its start.
         * @param length How many.
         * @throws IOException When they cannot be read.
         */
        void read(long position, byte[] into, int length) throws IOException;
    }

    /**
     * One page of lines.
     *
     * @param lines The lines, oldest first, without their line ends.
     * @param offset The position where the first of them starts.
     * @param atStart Whether nothing is kept before it.
     */
    record Page(List<String> lines, long offset, boolean atStart) {}

    /**
     * Reads the count, refusing one outside 1 to {@link #MOST_LINES}.
     *
     * @param parameters The call's parameters.
     * @return The count.
     * @throws VarlinkException InvalidParameter naming {@code lines}.
     */
    static int lines(final Map<String, Object> parameters) {
        final Integer lines =
                org.fuin.porelo.wire.varlink.VarlinkNumbers.intParameter(parameters, "lines");
        if (lines == null || lines < 1 || lines > MOST_LINES) {
            throw invalid("lines");
        }
        return lines;
    }

    /**
     * Reads the position, refusing one that is no whole number or below 0.
     *
     * @param parameters The call's parameters.
     * @return The position.
     * @throws VarlinkException InvalidParameter naming {@code before}.
     */
    static long before(final Map<String, Object> parameters) {
        final long before =
                org.fuin.porelo.wire.varlink.VarlinkNumbers.longParameter(parameters, "before");
        if (before < 0) {
            throw invalid("before");
        }
        return before;
    }

    /**
     * Returns the lines before a position.
     *
     * <p>A position past the end belongs to what was there before - a log replaced since, a
     * recording begun again - and is answered as gone. One that does not follow a line end is not
     * one this daemon gave out, and is refused rather than read from as a guess. One older than the
     * oldest kept answers that nothing older is kept.
     *
     * @param bytes What is read.
     * @param before A position {@code 0} or larger.
     * @param lines How many lines at most.
     * @param gone What to throw when the position lies past the end.
     * @return The page.
     * @throws IOException When the bytes cannot be read.
     * @throws VarlinkException InvalidParameter naming {@code before}, or {@code gone}.
     */
    static Page before(
            final Bytes bytes,
            final long before,
            final int lines,
            final java.util.function.Supplier<VarlinkException> gone)
            throws IOException {
        final long start = bytes.start();
        if (before > bytes.end()) {
            throw gone.get();
        }
        if (before <= start) {
            return new Page(List.of(), start, true);
        }
        final byte[] one = new byte[1];
        bytes.read(before - 1, one, 1);
        if (one[0] != '\n') {
            throw invalid("before");
        }
        // The line end before the position ends the page's last line and starts none.
        final long end = before - 1;
        final long first = firstOf(bytes, start, end, lines);
        final byte[] page = new byte[(int) Math.min(end - first, MOST_BYTES)];
        bytes.read(first, page, page.length);
        final String text = new String(page, StandardCharsets.UTF_8);
        return new Page(List.of(text.split("\n", -1)), first, first == start);
    }

    /**
     * Returns where the first line of the page starts, looked for backwards from its end.
     *
     * <p>At most {@code lines} lines, and no more than {@link #MOST_BYTES} unless one line alone is
     * longer.
     */
    private static long firstOf(
            final Bytes bytes, final long start, final long end, final int lines)
            throws IOException {
        final byte[] block = new byte[BLOCK];
        long kept = end;
        int found = 0;
        long position = end;
        while (position > start) {
            final int length = (int) Math.min(BLOCK, position - start);
            final long from = position - length;
            bytes.read(from, block, length);
            for (int i = length - 1; i >= 0; i--) {
                if (block[i] == '\n') {
                    final long candidate = from + i + 1;
                    if (found > 0 && end - candidate > MOST_BYTES) {
                        return kept;
                    }
                    kept = candidate;
                    if (++found == lines) {
                        return kept;
                    }
                }
            }
            position = from;
        }
        return found > 0 && end - start > MOST_BYTES ? kept : start;
    }

    /**
     * Returns a log file as bytes from position 0.
     *
     * @param file The open file.
     * @return Its bytes.
     * @throws IOException When its size cannot be read.
     */
    static Bytes of(final java.io.RandomAccessFile file) throws IOException {
        final long size = file.length();
        return new Bytes() {
            @Override
            public long start() {
                return 0;
            }

            @Override
            public long end() {
                return size;
            }

            @Override
            public void read(final long position, final byte[] into, final int length)
                    throws IOException {
                file.seek(position);
                file.readFully(into, 0, length);
            }
        };
    }

    /**
     * Returns the lines of a task log before a position.
     *
     * @param log The log.
     * @param before A position {@code 0} or larger.
     * @param lines How many lines at most.
     * @param gone What to throw when the log is no longer what the position was given for.
     * @return The page.
     * @throws VarlinkException As {@link #before(Bytes, long, int, java.util.function.Supplier)}.
     */
    static Page ofLog(
            final Path log,
            final long before,
            final int lines,
            final java.util.function.Supplier<VarlinkException> gone) {
        try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(log.toFile(), "r")) {
            return before(of(file), before, lines, gone);
        } catch (java.io.FileNotFoundException ex) {
            throw gone.get();
        } catch (IOException ex) {
            throw new VarlinkException(
                    PoreloDaemon.INTERFACE + ".Failed",
                    Map.of("message", String.valueOf(ex.getMessage())));
        }
    }

    private static VarlinkException invalid(final String parameter) {
        return new VarlinkException(
                "org.varlink.service.InvalidParameter", Map.of("parameter", parameter));
    }
}
