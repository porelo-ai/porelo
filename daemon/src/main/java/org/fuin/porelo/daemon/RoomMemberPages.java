package org.fuin.porelo.daemon;

import static org.fuin.porelo.daemon.PoreloDaemon.INTERFACE;

import java.time.Duration;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.wire.varlink.VarlinkConnection;
import org.fuin.porelo.wire.varlink.VarlinkException;

/**
 * Answers a project's room members in replies of one message each: the first members with the
 * reading's reply, the rest page by page from the reading this daemon keeps for them.
 *
 * <p>A transport's answer may hold 16 MiB, a message 4 MiB, so a large room is paged as a project's
 * open reviews are: every page is of the one membership the first reply read, kept {@link #KEPT}
 * after it, at most the {@link #MOST_READINGS} newest and at most {@link #MOST_BYTES} together. One
 * no longer kept is answered by name, never as an empty page.
 */
final class RoomMemberPages {

    /** How long a reading is kept for its pages. */
    static final Duration KEPT = Duration.ofMinutes(10);

    /** How many readings are kept at most; a new one takes the oldest one's place. */
    static final int MOST_READINGS = 16;

    /** How many bytes of members all kept readings hold together at most. */
    static final long MOST_BYTES = 64L * 1024 * 1024;

    private final KeptReadings<String, Reading> readings;

    /** The members of one transport read. */
    private record Reading(String project, List<Map<String, Object>> members) {}

    /**
     * Creates the readings of one daemon.
     *
     * @param clock When a reading is made and when it is asked.
     */
    RoomMemberPages(final InstantSource clock) {
        this.readings = new KeptReadings<>(clock, KEPT, MOST_READINGS, MOST_BYTES);
    }

    /**
     * Returns the reply to a reading: whole when it fits one message, else its first members and
     * what pages the rest, the reading kept for them.
     *
     * @param project The project read.
     * @param room The room's id.
     * @param members The members, as the wire has them.
     * @return The reply.
     */
    Map<String, Object> first(
            final String project, final String room, final List<Map<String, Object>> members) {
        final int limit = VarlinkConnection.MAX_MESSAGE;
        final Map<String, Object> whole = new LinkedHashMap<>();
        whole.put("room", room);
        whole.put("members", members);
        final long size = ReviewCut.bytes(whole);
        if (size <= limit) {
            return whole;
        }
        final String name = KeptReadings.name();
        final Map<String, Object> paged = new LinkedHashMap<>();
        paged.put("reading", name);
        paged.put("members", members.size());
        paged.put("next", Integer.MAX_VALUE);
        final Map<String, Object> reply = new LinkedHashMap<>();
        reply.put("room", room);
        reply.put("members", List.of());
        reply.put("paged", paged);
        final long space = limit - MessagePages.ENVELOPE - ReviewCut.bytes(reply);
        final int count = MessagePages.fitting(members, 0, space);
        if (count == 0) {
            throw LogPages.failed("A room member does not fit one message");
        }
        paged.put("next", count);
        reply.put("members", new ArrayList<>(members.subList(0, count)));
        readings.keep(name, new Reading(project, List.copyOf(members)), size);
        return MessagePages.checked(reply, limit);
    }

    /**
     * Returns the members of a kept reading from a position this daemon gave.
     *
     * @param project The project the reading is of.
     * @param reading The reading's name.
     * @param from Where the page starts.
     * @return {@code members}, and {@code next}, absent at the reading's end.
     * @throws VarlinkException {@code RoomMembersReadingGone} for a reading not kept or of another
     *     project; InvalidParameter naming {@code from} for a position outside the reading.
     */
    Map<String, Object> page(final String project, final String reading, final long from) {
        final int limit = VarlinkConnection.MAX_MESSAGE;
        final Reading kept = readings.get(reading);
        if (kept == null || !kept.project().equals(project)) {
            throw new VarlinkException(
                    INTERFACE + ".RoomMembersReadingGone",
                    Map.of("project", project, "reading", reading));
        }
        if (from < 0 || from >= kept.members().size()) {
            throw MessagePages.invalid("from");
        }
        final int start = (int) from;
        final Map<String, Object> reply = new LinkedHashMap<>();
        reply.put("members", List.of());
        reply.put("next", Integer.MAX_VALUE);
        final long room = limit - MessagePages.ENVELOPE - ReviewCut.bytes(reply);
        final int count = MessagePages.fitting(kept.members(), start, room);
        if (count == 0) {
            throw LogPages.failed("A room member does not fit one message");
        }
        reply.put("members", new ArrayList<>(kept.members().subList(start, start + count)));
        if (start + count < kept.members().size()) {
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
}
