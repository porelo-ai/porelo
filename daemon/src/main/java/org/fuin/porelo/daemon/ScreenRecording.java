package org.fuin.porelo.daemon;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import org.fuin.porelo.app.ScreenFile;
import org.jspecify.annotations.Nullable;

/**
 * The recording a task's root keeps of its session's output, read as one run of bytes.
 *
 * <p>Its files are named for where their bytes lie, so a position stays where it was when the
 * oldest file goes: the recording's start moves on, and nothing a client was given changes its
 * meaning. A file older than {@code records.kept_days} is no longer kept: it is read as gone.
 *
 * <p>Every file is opened before anything is read, so the writer removing the oldest one meanwhile
 * changes nothing in what one call reads.
 */
final class ScreenRecording implements LogPages.Bytes, AutoCloseable {

    /** One file of the recording, open, and where its bytes lie. */
    private record Part(long start, long size, FileChannel channel) {}

    private final List<Part> parts;

    private ScreenRecording(final List<Part> parts) {
        this.parts = parts;
    }

    /**
     * Opens a task's recording.
     *
     * @param state The task's state directory.
     * @param keptDays How many days a file of it is kept.
     * @param now What "now" is.
     * @return The recording, or {@code null} when none is kept.
     * @throws IOException When it cannot be read.
     */
    static @Nullable ScreenRecording open(final Path state, final int keptDays, final Instant now)
            throws IOException {
        final Path directory = ScreenFile.directory(state).resolve(ScreenFile.RECORDING);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        final Instant oldest = now.minus(Duration.ofDays(keptDays));
        final TreeMap<Long, Path> named = new TreeMap<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory)) {
            for (final Path file : files) {
                final long index = index(file.getFileName().toString());
                if (index < 0 || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                if (Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS)
                        .toInstant()
                        .isBefore(oldest)) {
                    // Not kept, and left for the task's removal: reading changes nothing.
                    continue;
                }
                named.put(index, file);
            }
        }
        // Only the run that ends with the newest: what lies before a gap is not where its name
        // says.
        final List<java.util.Map.Entry<Long, Path>> newestFirst =
                new ArrayList<>(named.descendingMap().entrySet());
        int run = newestFirst.isEmpty() ? 0 : 1;
        while (run < newestFirst.size()
                && newestFirst.get(run).getKey() == newestFirst.get(run - 1).getKey() - 1) {
            run++;
        }
        final List<Part> parts = new ArrayList<>();
        try {
            for (final java.util.Map.Entry<Long, Path> file :
                    newestFirst.subList(0, run).reversed()) {
                final FileChannel channel =
                        FileChannel.open(
                                file.getValue(),
                                StandardOpenOption.READ,
                                LinkOption.NOFOLLOW_LINKS);
                parts.add(new Part(file.getKey() * ScreenFile.SEGMENT, channel.size(), channel));
            }
        } catch (IOException | RuntimeException ex) {
            // The writer removed the oldest meanwhile, or began anew: what was opened is closed.
            close(parts);
            throw ex;
        }
        return parts.isEmpty() ? null : new ScreenRecording(List.copyOf(parts));
    }

    /**
     * Returns where a file's bytes lie, from its name, or {@code -1} for a name the writer never
     * gives.
     */
    static long index(final String name) {
        if (name.length() != ScreenFile.SUFFIX) {
            return -1;
        }
        long index = 0;
        for (int i = 0; i < name.length(); i++) {
            final char letter = name.charAt(i);
            if (letter < 'a' || letter > 'z') {
                return -1;
            }
            index = index * 26 + (letter - 'a');
        }
        return index;
    }

    @Override
    public long start() {
        return parts.getFirst().start();
    }

    @Override
    public long end() {
        final Part last = parts.getLast();
        return last.start() + last.size();
    }

    @Override
    public void read(final long position, final byte[] into, final int length) throws IOException {
        int done = 0;
        for (final Part part : parts) {
            final long at = position + done;
            if (done == length) {
                return;
            }
            if (at < part.start() || at >= part.start() + part.size()) {
                continue;
            }
            final int count = (int) Math.min(length - done, part.start() + part.size() - at);
            final ByteBuffer buffer = ByteBuffer.wrap(into, done, count);
            long from = at - part.start();
            while (buffer.hasRemaining()) {
                final int read = part.channel().read(buffer, from);
                if (read < 0) {
                    throw new java.io.EOFException(
                            "The recording ended at " + (from + part.start()));
                }
                from += read;
            }
            done += count;
        }
        if (done != length) {
            throw new java.io.EOFException("The recording holds no byte at " + (position + done));
        }
    }

    /**
     * Returns where the recording's last whole line ends: the first position Screen gives for
     * reading back. A line still being written belongs to the screen, not yet to the recording.
     *
     * @return The position after its last line end, or its start when it holds none.
     * @throws IOException When it cannot be read.
     */
    long lineEnd() throws IOException {
        final byte[] block = new byte[LogPages.BLOCK];
        long position = end();
        while (position > start()) {
            final int length = (int) Math.min(block.length, position - start());
            final long from = position - length;
            read(from, block, length);
            for (int i = length - 1; i >= 0; i--) {
                if (block[i] == '\n') {
                    return from + i + 1;
                }
            }
            position = from;
        }
        return start();
    }

    /**
     * Returns a recorded line as a client that draws no escapes shows it: what a carriage return
     * wrote over left out, and every escape sequence and control character but a tab removed.
     *
     * @param line A line as the session wrote it.
     * @return Its text.
     */
    static String plain(final String line) {
        final StringBuilder text = new StringBuilder(line.length());
        int last = line.length() - 1;
        while (last >= 0 && line.charAt(last) == '\r') {
            last--;
        }
        int i = 0;
        while (i < line.length()) {
            final char c = line.charAt(i);
            if (c == '\u001b') {
                i = afterEscape(line, i + 1);
                continue;
            }
            if (c == '\r') {
                // A line ends with one, from the terminal's line end; one within it starts the
                // line over, and what follows is what stays shown.
                if (i < last) {
                    text.setLength(0);
                }
            } else if (c == '\t' || c >= ' ' && c != '\u007f' && (c < '\u0080' || c > '\u009f')) {
                text.append(c);
            }
            i++;
        }
        return text.toString();
    }

    /** Returns where the text after an escape sequence starts; {@code at} follows its ESC. */
    private static int afterEscape(final String line, final int at) {
        if (at >= line.length()) {
            return at;
        }
        final char kind = line.charAt(at);
        if (kind == '[') {
            // Parameters and intermediates, then one final byte.
            int i = at + 1;
            while (i < line.length() && (line.charAt(i) < '@' || line.charAt(i) > '~')) {
                i++;
            }
            return Math.min(i + 1, line.length());
        }
        if (kind == ']' || kind == 'P' || kind == '_' || kind == '^') {
            // A string, ended by BEL or ESC \.
            int i = at + 1;
            while (i < line.length()) {
                if (line.charAt(i) == '\u0007') {
                    return i + 1;
                }
                if (line.charAt(i) == '\u001b'
                        && i + 1 < line.length()
                        && line.charAt(i + 1) == '\\') {
                    return i + 2;
                }
                i++;
            }
            return i;
        }
        if (kind >= ' ' && kind <= '/') {
            // A character set or similar: intermediates, then one final byte.
            return Math.min(at + 2, line.length());
        }
        return at + 1;
    }

    @Override
    public void close() {
        close(parts);
    }

    private static void close(final List<Part> parts) {
        for (final Part part : parts) {
            try {
                part.channel().close();
            } catch (IOException ex) {
                // A file opened for reading only; nothing was written that could be lost.
            }
        }
    }
}
