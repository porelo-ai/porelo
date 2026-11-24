package org.fuin.porelo.daemon;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * Answers a project's task records page by page, reading its record files once for a run of pages.
 *
 * <p>Each page read and parsed every record file of the project again. A first page that does not
 * end the records keeps what it read, named by the project, the {@code since} asked and the
 * reading's start, so a later page of the same run takes it: {@link #KEPT} after it was read, at
 * most the {@link #MOST_READINGS} newest and at most {@link #MOST_BYTES} together. A page whose
 * reading is no longer kept reads the records again, as every page did, so its answer is the same.
 * A kept reading asks only whether the record its page starts at is still stored, so a record
 * removed since is refused as it was when every page read the files.
 */
final class RecordReadings {

    /** How long a reading is kept for its pages. */
    static final Duration KEPT = Duration.ofMinutes(5);

    /** How many readings are kept at most; a new one takes the oldest one's place. */
    static final int MOST_READINGS = 4;

    /** How many bytes of records, as sent, all kept readings hold together at most. */
    static final long MOST_BYTES = 64L * 1024 * 1024;

    /** Reads a project's records, as {@code TaskWorkRecords.since} does. */
    @FunctionalInterface
    interface Reader {

        /**
         * Returns a project's records.
         *
         * @param project The project.
         * @param since The earliest end of a record read.
         * @return The records, in their order.
         * @throws IOException When they cannot be read.
         */
        List<Map<String, Object>> since(String project, Instant since) throws IOException;
    }

    /**
     * What names one reading.
     *
     * @param project The project.
     * @param since The {@code since} asked.
     * @param asOf When the reading began.
     */
    private record Key(String project, Instant since, Instant asOf) {}

    private final InstantSource clock;
    private final Reader reader;
    private final Predicate<String> stored;
    private final KeptReadings<Key, List<Map<String, Object>>> readings;

    /**
     * Creates the readings of one daemon.
     *
     * @param clock When a reading begins and when it is asked.
     * @param reader Reads a project's records.
     * @param stored Whether a task's record is still stored.
     */
    RecordReadings(final InstantSource clock, final Reader reader, final Predicate<String> stored) {
        this.clock = clock;
        this.reader = reader;
        this.stored = stored;
        this.readings = new KeptReadings<>(clock, KEPT, MOST_READINGS, MOST_BYTES);
    }

    /**
     * Returns one page of a project's records.
     *
     * @param project The project.
     * @param since The earliest end of a record answered.
     * @param from Where the page starts, or {@code null} for the first page.
     * @param limit The most bytes a message may have.
     * @return The page, as {@link RecordPages#records} answers it.
     * @throws IOException When the records cannot be read.
     */
    Map<String, Object> page(
            final String project,
            final Instant since,
            final RecordPages.@Nullable From from,
            final int limit)
            throws IOException {
        // A later page pins the reading's start the first one gave.
        final Instant asOf = from == null ? clock.instant() : from.asOf();
        final Key key = new Key(project, since, asOf);
        final List<Map<String, Object>> kept = from == null ? null : readings.get(key);
        if (kept != null && from != null && removedSince(kept, from.task())) {
            throw RecordPages.changed(from.task());
        }
        final List<Map<String, Object>> records =
                kept != null ? kept : List.copyOf(reader.since(project, since));
        final Map<String, Object> page = RecordPages.records(records, asOf, from, limit);
        if (kept == null && page.containsKey("next")) {
            readings.keep(key, records, MessagePages.sent(records));
        }
        return page;
    }

    /**
     * Tells whether a task the reading holds is no longer stored. Only such a task is looked up, so
     * a position naming anything else never reaches the file system; the page refuses it.
     */
    private boolean removedSince(final List<Map<String, Object>> kept, final String task) {
        return kept.stream().anyMatch(record -> task.equals(record.get("task")))
                && !stored.test(task);
    }

    /** Releases the readings whose keep time has passed; the daemon's sweep calls this. */
    void release() {
        readings.release();
    }
}
