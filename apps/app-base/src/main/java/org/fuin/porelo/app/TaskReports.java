package org.fuin.porelo.app;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.fuin.porelo.wire.Directories;
import org.fuin.porelo.wire.Json;
import org.fuin.porelo.wire.JsonException;
import org.jspecify.annotations.Nullable;

/**
 * What a task reported back to whoever started it: plain text and a one-line kind, numbered in the
 * task's own sequence and kept with the task's work records, past its removal.
 *
 * <p>One file per task name beside the work records, outside every directory mounted into the task,
 * appended to one line per report and never rewritten, so a report costs one line however many came
 * before. The sequence belongs to the name: a later task of the same name goes on with it, so a
 * caller that keeps the last number it read misses nothing and never reads one twice. The bound
 * counts the reports of one task, told apart by the start of its work record. Porelo reads neither
 * kind nor text, and keeps nothing about who read what.
 *
 * @param paths Where the records are.
 */
public record TaskReports(PoreloPaths paths) {

    /** The most code points of a report's kind, which is one line. */
    public static final int MOST_KIND = 64;

    /** The most code points of a report's text. */
    public static final int MOST_TEXT = 65_536;

    /**
     * The most bytes of reports one answer carries, the reports array written as sent: a quarter of
     * the 4 MiB a varlink message may hold. One report at its longest and widest fits.
     */
    public static final int MOST_PAGE = 1024 * 1024;

    /** The ending of a task's file in the work records' directory. */
    static final String SUFFIX = ".reports";

    /** One writer at a time, across processes: porelod and a command line both read and write. */
    private static final String LOCK = ".reports.lock";

    /** One report, as the task gave it and the host numbered it. */
    public record Report(long seq, String kind, String text, String at) {

        /** Refuses a report the host would not have kept. */
        public Report {
            if (seq < 1 || !validKind(kind) || !validText(text)) {
                throw new IllegalArgumentException("not a report the host keeps: " + seq);
            }
            Instant.parse(at);
        }

        /**
         * Returns it as a caller is sent it.
         *
         * @return Its four fields.
         */
        public Map<String, Object> asMap() {
            final Map<String, Object> map = new LinkedHashMap<>();
            map.put("seq", seq);
            map.put("kind", kind);
            map.put("text", text);
            map.put("at", at);
            return map;
        }
    }

    /**
     * One answer of reports.
     *
     * @param reports The reports, oldest first.
     * @param last The task's last sequence number, 0 before its first.
     * @param more Whether some after the ones asked for were left out under {@link #MOST_PAGE}.
     */
    public record Page(List<Report> reports, long last, boolean more) {

        /** Constructor. */
        public Page {
            reports = List.copyOf(reports);
        }

        /**
         * Returns it as a caller is sent it.
         *
         * @return Its three fields.
         */
        public Map<String, Object> asMap() {
            final Map<String, Object> map = new LinkedHashMap<>();
            map.put("reports", reports.stream().map(Report::asMap).toList());
            map.put("last", last);
            map.put("more", more);
            return map;
        }
    }

    /** A report past the bound the project file names. */
    public static final class BoundReached extends Exception {
        private static final long serialVersionUID = 1L;

        private final int most;

        /**
         * Constructor.
         *
         * @param most The bound.
         */
        public BoundReached(final int most) {
            super("the task has reported the most its project allows, " + most);
            this.most = most;
        }

        /**
         * Returns the bound.
         *
         * @return The most reports one task may hand back.
         */
        public int most() {
            return most;
        }
    }

    /**
     * One line of the file: a report, the recorded id of the container that gave it, which the
     * bound counts by, and the start of its task's work record, which {@link #ofRecord} answers by.
     */
    private record Line(Report report, String container, @Nullable String record) {}

    /** A container id as a task's start records it. */
    private static final java.util.regex.Pattern CONTAINER =
            java.util.regex.Pattern.compile("[0-9a-f]{12,64}");

    /**
     * Returns whether a kind can be kept: one line of 1 to {@link #MOST_KIND} code points, with no
     * control character, no format character (bidi controls, zero-width ones) and no line or
     * paragraph separator.
     *
     * @param kind What the task gave.
     * @return Whether it is kept as it is.
     */
    public static boolean validKind(final String kind) {
        return !kind.isEmpty()
                && encodable(kind, MOST_KIND)
                && kind.codePoints()
                        .noneMatch(
                                point ->
                                        Character.isISOControl(point)
                                                || Character.getType(point) == Character.FORMAT
                                                || point == 0x2028
                                                || point == 0x2029);
    }

    /**
     * Returns whether a text can be kept: 1 to {@link #MOST_TEXT} code points, with no control
     * character but line breaks, no line or paragraph separator, and no bidi override, embedding or
     * isolate, which would show the text in another order than it was written.
     *
     * @param text What the task gave.
     * @return Whether it is kept as it is.
     */
    public static boolean validText(final String text) {
        return !text.isEmpty()
                && encodable(text, MOST_TEXT)
                && text.codePoints()
                        .noneMatch(
                                point ->
                                        Character.isISOControl(point)
                                                        && point != '\n'
                                                        && point != '\r'
                                                || point == 0x2028
                                                || point == 0x2029
                                                || point >= 0x202a && point <= 0x202e
                                                || point >= 0x2066 && point <= 0x2069);
    }

    private static boolean encodable(final String value, final int most) {
        return value.codePointCount(0, value.length()) <= most
                && StandardCharsets.UTF_8.newEncoder().canEncode(value);
    }

    /**
     * Keeps one more report of the task's, numbered next in the name's sequence.
     *
     * @param container The task.
     * @param id The recorded id of the task's container, which the bound counts by: a task of the
     *     name in another container has a bound of its own.
     * @param record The start of the task's work record, which {@link #ofRecord} answers by, or
     *     {@code null} for a task without one; the bound does not depend on it.
     * @param kind Its kind.
     * @param text Its text.
     * @param most The most reports this task may hand back.
     * @return The report as kept.
     * @throws IOException If the file cannot be read or written; one that cannot be read is never
     *     taken for an empty one, which would number a report again.
     * @throws BoundReached If the task reported {@code most} already.
     * @throws IllegalArgumentException If {@code id} is no container id: a report nobody's
     *     container gave is never counted.
     */
    public Report report(
            final String container,
            final String id,
            final @Nullable String record,
            final String kind,
            final String text,
            final int most)
            throws IOException, BoundReached {
        if (!CONTAINER.matcher(id).matches()) {
            throw new IllegalArgumentException("not a container id: " + id);
        }
        final Path file = file(container);
        final Object kept =
                locked(
                        () -> {
                            final List<Line> lines = read(file, true);
                            final long own =
                                    lines.stream()
                                            .filter(line -> line.container().equals(id))
                                            .count();
                            if (own >= most) {
                                return new BoundReached(most);
                            }
                            final Report report =
                                    new Report(
                                            lines.isEmpty()
                                                    ? 1
                                                    : lines.getLast().report().seq() + 1,
                                            kind,
                                            text,
                                            Instant.now().toString());
                            append(file, new Line(report, id, record));
                            return report;
                        });
        if (kept instanceof BoundReached bound) {
            throw bound;
        }
        return (Report) kept;
    }

    /**
     * Answers the task's reports after a number, oldest first, as many as {@link #MOST_PAGE} holds.
     *
     * @param container The task, a removed one's too.
     * @param after The last number the caller read, 0 for all.
     * @return The page; empty, at 0, for a name that never reported.
     * @throws IOException If the file cannot be read.
     */
    public Page page(final String container, final long after) throws IOException {
        // Unlocked: a report being written is a last line without its line break, never read.
        final List<Line> lines = read(file(container), false);
        final List<Report> carried = new ArrayList<>();
        // The array's brackets.
        long bytes = 2;
        boolean more = false;
        for (final Line line : lines) {
            if (line.report().seq() <= after) {
                continue;
            }
            // With the comma before it, where the page carries one already.
            final long size =
                    Json.write(line.report().asMap()).getBytes(StandardCharsets.UTF_8).length
                            + (carried.isEmpty() ? 0L : 1L);
            if (bytes + size > MOST_PAGE) {
                more = true;
                break;
            }
            bytes += size;
            carried.add(line.report());
        }
        return new Page(carried, lines.isEmpty() ? 0 : lines.getLast().report().seq(), more);
    }

    /**
     * Answers the reports one task of the name gave, the one its work record started at names.
     *
     * @param container The task's name.
     * @param record The start of its work record.
     * @return Its reports, oldest first.
     * @throws IOException If the file cannot be read.
     */
    public List<Report> ofRecord(final String container, final @Nullable String record)
            throws IOException {
        return read(file(container), false).stream()
                .filter(line -> Objects.equals(line.record(), record))
                .map(Line::report)
                .toList();
    }

    /**
     * Returns the task's last sequence number, which its row carries.
     *
     * @param container The task.
     * @return It, 0 before the first report, and where the file cannot be read: a row is still
     *     listed, and {@link #page} says what is wrong.
     */
    public long last(final String container) {
        try {
            final Line line = lastLine(file(container));
            return line == null ? 0 : line.report().seq();
        } catch (IOException | IllegalArgumentException ex) {
            return 0;
        }
    }

    private Path file(final String container) {
        if (!TaskResult.named(container)) {
            throw new IllegalArgumentException("not a task's name: " + container);
        }
        return paths.tasks().workRecords().resolve(container + SUFFIX);
    }

    private <T> T locked(final FileLocks.Locked<T> locked) throws IOException {
        final Path directory = paths.tasks().workRecords();
        Directories.create(directory);
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        return FileLocks.holding(directory.resolve(LOCK), locked);
    }

    /**
     * Reads every whole line; a last line without its line break is a write that broke off, never
     * answered, and is cut away when {@code mend} is set, so the next report is written on a line
     * of its own.
     */
    private static List<Line> read(final Path file, final boolean mend) throws IOException {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(file + " is not a file the host wrote");
        }
        final List<Line> lines = new ArrayList<>();
        long whole = 0;
        try (InputStream input =
                new java.io.BufferedInputStream(
                        Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS))) {
            final java.io.ByteArrayOutputStream line = new java.io.ByteArrayOutputStream();
            long position = 0;
            for (int next = input.read(); next >= 0; next = input.read()) {
                position++;
                if (next != '\n') {
                    line.write(next);
                    continue;
                }
                final Line read = parse(file, line.toByteArray());
                if (!lines.isEmpty() && read.report().seq() <= lines.getLast().report().seq()) {
                    throw new IOException(file + " holds its reports out of order");
                }
                lines.add(read);
                line.reset();
                whole = position;
            }
        }
        if (mend && Files.size(file) > whole) {
            try (FileChannel channel =
                    FileChannel.open(file, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                channel.truncate(whole);
                channel.force(true);
            }
        }
        return List.copyOf(lines);
    }

    /** Reads the last whole line alone, from the file's end: every row of every listing asks. */
    private static @Nullable Line lastLine(final Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        try (FileChannel channel =
                FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            // The last line break: what follows it is a write that broke off.
            final long end = previousBreak(channel, channel.size());
            if (end < 0) {
                return null;
            }
            final long start = previousBreak(channel, end) + 1;
            final ByteBuffer bytes = ByteBuffer.allocate(Math.toIntExact(end - start));
            while (bytes.hasRemaining()) {
                if (channel.read(bytes, start + bytes.position()) < 0) {
                    throw new IOException(file + " ended while it was read");
                }
            }
            return parse(file, bytes.array());
        }
    }

    /** Bytes read at once from the file's end, looking for a line break. */
    private static final int BLOCK = 8 * 1024;

    /** Returns where the last line break before {@code before} lies, or -1 where there is none. */
    private static long previousBreak(final FileChannel channel, final long before)
            throws IOException {
        final ByteBuffer block = ByteBuffer.allocate(BLOCK);
        long to = before;
        while (to > 0) {
            final long from = Math.max(0, to - BLOCK);
            final int length = Math.toIntExact(to - from);
            block.clear().limit(length);
            while (block.hasRemaining()) {
                if (channel.read(block, from + block.position()) < 0) {
                    throw new IOException("ended while it was read");
                }
            }
            for (int index = length - 1; index >= 0; index--) {
                if (block.get(index) == '\n') {
                    return from + index;
                }
            }
            to = from;
        }
        return -1;
    }

    private static Line parse(final Path file, final byte[] bytes) throws IOException {
        try {
            final String text =
                    StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            if (!(Json.parseExact(text) instanceof Map<?, ?> fields
                    && fields.get("seq") instanceof Long seq
                    && fields.get("kind") instanceof String kind
                    && fields.get("text") instanceof String said
                    && fields.get("at") instanceof String at
                    && fields.get("container") instanceof String container
                    && CONTAINER.matcher(container).matches()
                    && (fields.get("record") == null || fields.get("record") instanceof String))) {
                throw new IOException(file + " holds something that is no report");
            }
            return new Line(
                    new Report(seq, kind, said, at), container, (String) fields.get("record"));
        } catch (CharacterCodingException
                | JsonException
                | IllegalArgumentException
                | DateTimeException ex) {
            throw new IOException(file + " cannot be read: " + ex.getMessage(), ex);
        }
    }

    private static void append(final Path file, final Line line) throws IOException {
        final Map<String, Object> fields = line.report().asMap();
        fields.put("container", line.container());
        if (line.record() != null) {
            fields.put("record", line.record());
        }
        final byte[] bytes = (Json.write(fields) + "\n").getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel =
                FileChannel.open(
                        file,
                        java.util.Set.of(
                                StandardOpenOption.CREATE,
                                StandardOpenOption.APPEND,
                                StandardOpenOption.WRITE,
                                LinkOption.NOFOLLOW_LINKS),
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rw-------")))) {
            final ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            // Answered only once it is on disk: a number told is never given again.
            channel.force(true);
        }
    }
}
