package org.fuin.porelo.app;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.fuin.porelo.agent.api.Waiting;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;

/**
 * What an unattended run answered, read from its own log at its end.
 *
 * <p>Which record is the answer is the agent's to say, by the last message its definition declares,
 * so Porelo names no agent. Where it declares none, or the run wrote no such record (a timeout, a
 * crash), the run's last lines stand in, as the agent shows them, and say so.
 *
 * @param text The answer, or the last lines joined by line ends; at most {@link #MOST_BYTES} bytes
 *     of UTF-8, cut at a code point.
 * @param kind Which of the two it is.
 * @param cut Whether it was cut to fit.
 */
public record RunResult(String text, Kind kind, boolean cut) {

    /** The most bytes of UTF-8 a result carries. */
    public static final int MOST_BYTES = 16 * 1024;

    /** How many of the run's last lines stand in for an answer it did not give. */
    public static final int LAST_LINES = 40;

    /** The most of a log's end that is read, so a huge log costs no more than this. */
    public static final int MOST_READ = 2 * 1024 * 1024;

    /** What a result is. */
    public enum Kind {
        /** The agent's own answer, the record its definition names. */
        ANSWER,
        /** The run's last lines, where it gave no answer the agent declares. */
        LAST_LINES
    }

    /**
     * Reads a run's result from its log.
     *
     * @param log The run's log; one that is a link is not read.
     * @param lastMessage Which record is the answer, as the agent declares it, or {@code null}.
     * @param formatting How the agent shows its lines, {@code null} for a line it hides.
     * @return The result, or {@code null} when there is no log to read.
     */
    public static @Nullable RunResult of(
            final Path log,
            final Waiting.@Nullable LastMessage lastMessage,
            final Function<List<String>, List<@Nullable String>> formatting) {
        final List<String> lines = tail(log);
        if (lines == null) {
            return null;
        }
        if (lastMessage != null) {
            final List<Object> records = new ArrayList<>();
            for (final String line : lines) {
                try {
                    final Object record = Json.parse(line);
                    if (record != null) {
                        records.add(record);
                    }
                } catch (RuntimeException notARecord) {
                    // A line printed around the stream says nothing of the answer.
                }
            }
            final String said = lastMessage.of(records);
            if (said != null) {
                return bounded(said, Kind.ANSWER);
            }
        }
        List<@Nullable String> shown;
        try {
            shown = formatting.apply(lines);
        } catch (RuntimeException ex) {
            // A formatter that fails leaves the lines worth reading as they are.
            shown = new ArrayList<>(lines);
        }
        final List<String> kept = new ArrayList<>();
        for (final String line : shown) {
            if (line != null) {
                kept.add(line);
            }
        }
        final List<String> last = kept.subList(Math.max(0, kept.size() - LAST_LINES), kept.size());
        return bounded(String.join("\n", last), Kind.LAST_LINES);
    }

    /** The whole lines of the log's last {@link #MOST_READ} bytes, or {@code null} for no log. */
    private static @Nullable List<String> tail(final Path log) {
        if (!Files.isRegularFile(log, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        try (RandomAccessFile file = new RandomAccessFile(log.toFile(), "r")) {
            final long length = file.length();
            final long start = Math.max(0, length - MOST_READ);
            final byte[] bytes = new byte[(int) (length - start)];
            file.seek(start);
            file.readFully(bytes);
            final String text = new String(bytes, StandardCharsets.UTF_8);
            final List<String> lines = new ArrayList<>(text.lines().toList());
            if (start > 0 && !lines.isEmpty()) {
                // Read from the middle of a line: its start is not here.
                lines.removeFirst();
            }
            return lines;
        } catch (IOException ex) {
            return null;
        }
    }

    private static RunResult bounded(final String text, final Kind kind) {
        final byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= MOST_BYTES) {
            return new RunResult(text, kind, false);
        }
        int end = MOST_BYTES;
        // Back to the start of a code point: a continuation byte is 10xxxxxx.
        while (end > 0 && (bytes[end] & 0b1100_0000) == 0b1000_0000) {
            end--;
        }
        try {
            final String cut =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .decode(java.nio.ByteBuffer.wrap(bytes, 0, end))
                            .toString();
            return new RunResult(cut, kind, true);
        } catch (CharacterCodingException ex) {
            return new RunResult(text.substring(0, Math.min(text.length(), end / 4)), kind, true);
        }
    }
}
