package org.fuin.porelo.daemon;

import static org.fuin.porelo.daemon.PoreloDaemon.INTERFACE;

import java.time.Duration;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.app.HostPullRequestReader;
import org.fuin.porelo.wire.varlink.VarlinkConnection;
import org.fuin.porelo.wire.varlink.VarlinkException;

/**
 * Answers a project's open reviews in replies of one message each: the first rows with the
 * reading's reply, the rest page by page from the reading this daemon keeps for them.
 *
 * <p>A reading is the one forge read a first reply answered, a {@code PullRequests} reply or a
 * watch's snapshot, so every page is of the same rows: a second forge read for a page could have
 * moved them. Positions run through the open rows and then the ended ones. A reading is kept {@link
 * #KEPT} after its first reply, and at most the {@link #MOST_READINGS} newest, so a client asking
 * again and again holds no more; one no longer kept is answered by name, never as an empty page.
 */
final class PullRequestPages {

    /** How long a reading is kept for its pages. */
    static final Duration KEPT = Duration.ofMinutes(10);

    /** How many readings are kept at most; a new one takes the oldest one's place. */
    static final int MOST_READINGS = 16;

    private final KeptReadings<String, Reading> readings;

    /** The rows of one forge read, open first, and how many of them are open. */
    private record Reading(String project, List<Map<String, Object>> rows, int open) {}

    /**
     * Creates the readings of one daemon.
     *
     * @param clock When a reading is made and when it is asked.
     */
    PullRequestPages(final InstantSource clock) {
        // No bound in bytes: a forge answer is bounded where it is read.
        this.readings = new KeptReadings<>(clock, KEPT, MOST_READINGS, Long.MAX_VALUE);
    }

    /**
     * What a reading answers, as the wire has it: a watch compares two of them to send only a
     * changed snapshot, never by its reply, whose reading is named anew each time.
     *
     * @param open The open rows.
     * @param ended The ended rows.
     * @param refused The refused repositories, empty when every repository answered.
     */
    record Snapshot(
            List<Map<String, Object>> open,
            List<Map<String, Object>> ended,
            List<Map<String, Object>> refused) {

        /**
         * Returns what a reading answers.
         *
         * @param round The reading.
         * @return Its rows as the wire has them.
         */
        static Snapshot of(final HostPullRequestReader.Round round) {
            return new Snapshot(
                    round.open().stream().map(HostPullRequestReader.Pull::asMap).toList(),
                    round.ended().stream().map(HostPullRequestReader.Pull::asMap).toList(),
                    round.refused().stream().map(HostPullRequestReader.Refusal::asMap).toList());
        }
    }

    /**
     * Returns the reply to a reading: whole when it fits one message, else its first rows and what
     * pages the rest, the reading kept for them.
     *
     * @param project The project read.
     * @param round The reading.
     * @return The reply.
     * @throws VarlinkException {@code HostPullRequestsRefused} with reason {@code bounds} when the
     *     refused repositories and one row do not fit one message.
     */
    Map<String, Object> first(final String project, final HostPullRequestReader.Round round) {
        return first(project, Snapshot.of(round));
    }

    /**
     * Returns the reply to a reading: whole when it fits one message, else its first rows and what
     * pages the rest, the reading kept for them.
     *
     * @param project The project read.
     * @param snapshot What the reading answers.
     * @return The reply.
     * @throws VarlinkException {@code HostPullRequestsRefused} with reason {@code bounds} when the
     *     refused repositories and one row do not fit one message.
     */
    Map<String, Object> first(final String project, final Snapshot snapshot) {
        final int limit = VarlinkConnection.MAX_MESSAGE;
        final List<Map<String, Object>> open = snapshot.open();
        final List<Map<String, Object>> ended = snapshot.ended();
        final Map<String, Object> whole = new LinkedHashMap<>();
        whole.put("pullRequests", open);
        whole.put("ended", ended);
        // Absent when every repository answered.
        if (!snapshot.refused().isEmpty()) {
            whole.put("refused", snapshot.refused());
        }
        if (ReviewCut.bytes(whole) <= limit) {
            return whole;
        }
        final List<Map<String, Object>> rows = new ArrayList<>(open);
        rows.addAll(ended);
        final String name = KeptReadings.name();
        final Map<String, Object> paged = new LinkedHashMap<>();
        paged.put("reading", name);
        paged.put("pullRequests", open.size());
        paged.put("ended", ended.size());
        paged.put("next", Integer.MAX_VALUE);
        final Map<String, Object> reply = new LinkedHashMap<>(whole);
        reply.put("pullRequests", List.of());
        reply.put("ended", List.of());
        reply.put("paged", paged);
        final long room = limit - MessagePages.ENVELOPE - ReviewCut.bytes(reply);
        final int count = MessagePages.fitting(rows, 0, room);
        if (count == 0) {
            throw new VarlinkException(
                    INTERFACE + ".HostPullRequestsRefused",
                    Map.of(
                            "reason",
                            "bounds",
                            "message",
                            "the refused repositories and one review do not fit one message"));
        }
        paged.put("next", count);
        split(reply, rows, open.size(), 0, count);
        readings.keep(name, new Reading(project, List.copyOf(rows), open.size()), 0);
        return MessagePages.checked(reply, limit);
    }

    /**
     * Returns the rows of a kept reading from a position this daemon gave.
     *
     * @param project The project the reading is of.
     * @param reading The reading's name.
     * @param from Where the page starts.
     * @return {@code pullRequests}, {@code ended} and {@code next}, absent at the reading's end.
     * @throws VarlinkException {@code PullRequestsReadingGone} for a reading not kept or of another
     *     project; InvalidParameter naming {@code from} for a position outside the reading.
     */
    Map<String, Object> page(final String project, final String reading, final long from) {
        final int limit = VarlinkConnection.MAX_MESSAGE;
        final Reading kept = readings.get(reading);
        if (kept == null || !kept.project().equals(project)) {
            throw new VarlinkException(
                    INTERFACE + ".PullRequestsReadingGone",
                    Map.of("project", project, "reading", reading));
        }
        if (from < 0 || from >= kept.rows().size()) {
            throw MessagePages.invalid("from");
        }
        final int start = (int) from;
        final Map<String, Object> reply = new LinkedHashMap<>();
        reply.put("pullRequests", List.of());
        reply.put("ended", List.of());
        reply.put("next", Integer.MAX_VALUE);
        final long room = limit - MessagePages.ENVELOPE - ReviewCut.bytes(reply);
        final int count = MessagePages.fitting(kept.rows(), start, room);
        if (count == 0) {
            throw LogPages.failed("A review does not fit one message");
        }
        split(reply, kept.rows(), kept.open(), start, count);
        if (start + count < kept.rows().size()) {
            reply.put("next", start + count);
        } else {
            reply.remove("next");
        }
        return MessagePages.checked(reply, limit);
    }

    /**
     * Returns how many readings are kept now.
     *
     * @return The count.
     */
    int kept() {
        return readings.kept();
    }

    /** Puts the rows of a page into the two lists, the open ones first. */
    private static void split(
            final Map<String, Object> reply,
            final List<Map<String, Object>> rows,
            final int open,
            final int from,
            final int count) {
        final int end = from + count;
        reply.put(
                "pullRequests",
                new ArrayList<>(rows.subList(Math.min(from, open), Math.min(end, open))));
        reply.put(
                "ended", new ArrayList<>(rows.subList(Math.max(from, open), Math.max(end, open))));
    }
}
