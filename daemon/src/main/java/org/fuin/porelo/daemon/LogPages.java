package org.fuin.porelo.daemon;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.wire.Json;
import org.fuin.porelo.wire.varlink.VarlinkConnection;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.jspecify.annotations.Nullable;

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

    /** Room in one message for what surrounds a page's lines: the names, offset and atStart. */
    static final int ENVELOPE = 1024;

    /**
     * How many bytes a page's lines take at most as they go on the wire, escaped as JSON: a reply
     * is one message, and a client refuses one larger than {@link VarlinkConnection#MAX_MESSAGE}.
     */
    static final int MOST_SENT = VarlinkConnection.MAX_MESSAGE - ENVELOPE;

    /**
     * How many bytes of the log a page reads at most. Its lines stay whole; only one line longer
     * than this alone is read cut to its first part, so a line without end in a log cannot fill the
     * daemon's memory. A sixth of {@link #MOST_SENT}, since a control character, the worst case,
     * goes on the wire as six bytes: read lines escaped as they are still fit one message, and so
     * does the request that hands them to the agent's formatter.
     */
    static final int MOST_BYTES = MOST_SENT / 6;

    /** What a client is told when a log cannot be read: the path is none of its business. */
    static final String LOG_FAILED = "The task log could not be read";

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
     * @param starts Where each of them starts, as read; empty for a page {@link #fit} made.
     * @param offset The position where the first of them starts.
     * @param atStart Whether nothing is kept before it.
     */
    record Page(List<String> lines, List<Long> starts, long offset, boolean atStart) {}

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
     * Reads what a position is a position in, as Tail or Screen named it.
     *
     * @param parameters The call's parameters.
     * @return The name.
     * @throws VarlinkException InvalidParameter naming {@code of} when there is none.
     */
    static String of(final Map<String, Object> parameters) {
        if (!(parameters.get("of") instanceof String of)) {
            throw invalid("of");
        }
        return of;
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
            return new Page(List.of(), List.of(), start, true);
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
        final List<Long> starts = new ArrayList<>();
        starts.add(first);
        for (int i = 0; i < page.length; i++) {
            if (page[i] == '\n') {
                starts.add(first + i + 1);
            }
        }
        final String text = new String(page, StandardCharsets.UTF_8);
        return new Page(List.of(text.split("\n", -1)), starts, first, first == start);
    }

    /**
     * Returns the newest of a page's lines, as they are shown, that fit one reply together.
     *
     * <p>Shown lines may be longer than read - an agent's formatter lengthens them, and JSON
     * escapes a control character as six bytes - so the bound is taken on what is sent. The page
     * then starts at the first line sent, and the next page goes on from there; a line too long
     * alone is sent cut to its first part.
     *
     * @param page A page as read.
     * @param shown Each of its lines as shown, or {@code null} for one left out.
     * @return The page as sent.
     */
    static Page fit(final Page page, final List<@Nullable String> shown) {
        if (shown.size() != page.lines().size()) {
            throw new IllegalArgumentException(
                    "Shown " + shown.size() + " lines for " + page.lines().size());
        }
        long room = MOST_SENT;
        int first = shown.size();
        int sent = 0;
        while (first > 0) {
            final @Nullable String line = shown.get(first - 1);
            // Its quotes and escapes, and the comma before it.
            final long size = line == null ? 0 : sent(line) + 1L;
            if (size > room) {
                break;
            }
            room -= size;
            first--;
            if (line != null) {
                sent++;
            }
        }
        final List<String> lines = new ArrayList<>();
        if (sent == 0 && first > 0) {
            first--;
            lines.add(
                    cut(
                            java.util.Objects.requireNonNull(
                                    shown.get(first), "the line that did not fit is shown")));
        } else {
            for (int i = first; i < shown.size(); i++) {
                final @Nullable String line = shown.get(i);
                if (line != null) {
                    lines.add(line);
                }
            }
        }
        final long offset = first < page.starts().size() ? page.starts().get(first) : page.offset();
        return new Page(List.copyOf(lines), List.of(), offset, page.atStart() && first == 0);
    }

    /**
     * Returns how many bytes a text takes on the wire, its quotes included: escaped as JSON, so a
     * control character counts six.
     *
     * @param line The text.
     * @return Its size as sent.
     */
    static int sent(final String line) {
        return Json.write(line).getBytes(StandardCharsets.UTF_8).length;
    }

    /** Returns the first part of a line that fits {@link #MOST_SENT} on the wire. */
    static String cut(final String line) {
        long room = MOST_SENT - 2L;
        int end = 0;
        while (end < line.length()) {
            final int point = line.codePointAt(end);
            final int size = sent(Character.toString(point)) - 2;
            if (size > room) {
                break;
            }
            room -= size;
            end += Character.charCount(point);
        }
        return line.substring(0, end);
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
    static Bytes of(final FileChannel file) throws IOException {
        final long size = file.size();
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
                final ByteBuffer buffer = ByteBuffer.wrap(into, 0, length);
                long from = position;
                while (buffer.hasRemaining()) {
                    final int read = file.read(buffer, from);
                    if (read < 0) {
                        // Shortened since its size was read.
                        throw new java.io.EOFException();
                    }
                    from += read;
                }
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
        final FileChannel file;
        try {
            // Never through a link: one put in its place would read a file elsewhere as the log.
            file = FileChannel.open(log, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException ex) {
            if (!Files.isRegularFile(log, LinkOption.NOFOLLOW_LINKS)) {
                // Gone since it was found, or a link now: no log there.
                throw gone.get();
            }
            throw failed(LOG_FAILED);
        }
        try (file) {
            return read(of(file), before, lines, gone, LOG_FAILED);
        } catch (IOException ex) {
            throw failed(LOG_FAILED);
        }
    }

    /**
     * Returns the lines before a position, answering what goes wrong as a client is told it.
     *
     * <p>Bytes that end before their end said were cut while read: the position belongs to what was
     * there, as for one gone. Any other failure is answered with a fixed text, never the
     * exception's, which for a file names its path.
     *
     * @param bytes What is read.
     * @param before A position {@code 0} or larger.
     * @param lines How many lines at most.
     * @param gone What to throw when the position lies past the end or the bytes were cut.
     * @param failed The text Failed carries.
     * @return The page.
     * @throws VarlinkException As {@link #before(Bytes, long, int, java.util.function.Supplier)},
     *     or Failed.
     */
    static Page read(
            final Bytes bytes,
            final long before,
            final int lines,
            final java.util.function.Supplier<VarlinkException> gone,
            final String failed) {
        try {
            return before(bytes, before, lines, gone);
        } catch (java.io.EOFException ex) {
            throw gone.get();
        } catch (IOException ex) {
            throw failed(failed);
        }
    }

    /**
     * Returns Failed carrying a fixed text.
     *
     * @param message What failed, naming no path.
     * @return The error.
     */
    static VarlinkException failed(final String message) {
        return new VarlinkException(PoreloDaemon.INTERFACE + ".Failed", Map.of("message", message));
    }

    private static VarlinkException invalid(final String parameter) {
        return new VarlinkException(
                "org.varlink.service.InvalidParameter", Map.of("parameter", parameter));
    }
}
