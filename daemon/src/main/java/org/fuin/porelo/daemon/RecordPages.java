package org.fuin.porelo.daemon;

import static org.fuin.porelo.daemon.PoreloDaemon.INTERFACE;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.jspecify.annotations.Nullable;

/**
 * Fits a project's task records into replies of one message each, and a record's tool calls into
 * pages of their own where the record alone does not fit one.
 *
 * <p>Nothing is kept between pages: a position names the next record by its start and task, and the
 * reading's start, so each page is read from what is stored. A record answered in one page is never
 * answered in another, and a record started after the reading began is left to the next reading. A
 * position whose record is gone, or whose record was written since its calls were paged, is
 * answered by name rather than with calls skipped or repeated.
 */
final class RecordPages {

    private RecordPages() {}

    /**
     * Where the next page of records starts, as a reply gave it.
     *
     * @param asOf When the reading began.
     * @param startedAt The next record's start.
     * @param task The next record's task.
     */
    record From(Instant asOf, String startedAt, String task) {}

    /**
     * Reads the position a call carries.
     *
     * @param parameters The call's parameters.
     * @return It, or {@code null} for a first page.
     * @throws VarlinkException InvalidParameter naming {@code from} for one that is no position.
     */
    static @Nullable From from(final Map<String, Object> parameters) {
        final Object given = parameters.get("from");
        if (given == null) {
            return null;
        }
        if (!(given instanceof Map<?, ?> fields)
                || !(fields.get("asOf") instanceof String asOf)
                || !(fields.get("startedAt") instanceof String startedAt)
                || !(fields.get("task") instanceof String task)) {
            throw MessagePages.invalid("from");
        }
        try {
            return new From(Instant.parse(asOf), startedAt, task);
        } catch (DateTimeParseException ex) {
            throw MessagePages.invalid("from");
        }
    }

    /**
     * Returns one page of records.
     *
     * @param all The project's records, in their order.
     * @param asOf When the reading began: a record started later is left out.
     * @param from Where the page starts, or {@code null} for the first page.
     * @param limit The most bytes a message may have.
     * @return {@code records}, and {@code next} when more follow.
     * @throws VarlinkException {@code RecordChanged} when the record {@code from} names is no
     *     longer stored.
     */
    static Map<String, Object> records(
            final List<Map<String, Object>> all,
            final Instant asOf,
            final @Nullable From from,
            final int limit) {
        final List<Map<String, Object>> rest = new ArrayList<>();
        boolean found = from == null;
        for (final Map<String, Object> record : all) {
            if (startedAfter(record, asOf)) {
                continue;
            }
            if (from != null && !found) {
                // The records before it were answered by earlier pages.
                if (!from.startedAt().equals(record.get("startedAt"))
                        || !from.task().equals(record.get("task"))) {
                    continue;
                }
                found = true;
            }
            rest.add(record);
        }
        if (from != null && !found) {
            throw changed(from.task());
        }
        long room = limit - MessagePages.ENVELOPE - ReviewCut.bytes(Map.of("records", List.of()));
        final List<Map<String, Object>> page = new ArrayList<>();
        int next = 0;
        while (next < rest.size()) {
            final Map<String, Object> record = rest.get(next);
            final long size = MessagePages.sent(record) + 1;
            if (size <= room) {
                page.add(record);
                room -= size;
                next++;
                continue;
            }
            // Too large for what is left: on the next page, unless it is too large alone.
            if (page.isEmpty()) {
                page.add(firstCalls(record, room));
                next++;
            }
            break;
        }
        final Map<String, Object> reply = new LinkedHashMap<>();
        reply.put("records", page);
        if (next < rest.size()) {
            final Map<String, Object> position = new LinkedHashMap<>();
            position.put("asOf", asOf.toString());
            position.put("startedAt", String.valueOf(rest.get(next).get("startedAt")));
            position.put("task", String.valueOf(rest.get(next).get("task")));
            reply.put("next", position);
        }
        return MessagePages.checked(reply, limit);
    }

    /**
     * Returns one page of a record's tool calls.
     *
     * @param record The record as stored now.
     * @param task Its task, for the refusal.
     * @param updatedAt Its {@code updatedAt} as the client was answered it, or {@code null}.
     * @param from The first call's position.
     * @param limit The most bytes a message may have.
     * @return {@code toolCalls}, and {@code next} when more follow.
     * @throws VarlinkException {@code RecordChanged} when the record was written since;
     *     InvalidParameter naming {@code from} for a position outside its calls.
     */
    static Map<String, Object> calls(
            final Map<String, Object> record,
            final String task,
            final @Nullable String updatedAt,
            final long from,
            final int limit) {
        if (!Objects.equals(record.get("updatedAt"), updatedAt)) {
            throw changed(task);
        }
        final List<?> calls = record.get("toolCalls") instanceof List<?> list ? list : List.of();
        if (from < 0 || from >= calls.size()) {
            throw MessagePages.invalid("from");
        }
        final int start = (int) from;
        final Map<String, Object> reply = new LinkedHashMap<>();
        reply.put("toolCalls", List.of());
        reply.put("next", Integer.MAX_VALUE);
        final long room = limit - MessagePages.ENVELOPE - ReviewCut.bytes(reply);
        final int count = MessagePages.fitting(calls, start, room);
        if (count == 0) {
            throw LogPages.failed("A tool call does not fit one message");
        }
        reply.put("toolCalls", new ArrayList<>(calls.subList(start, start + count)));
        if (start + count < calls.size()) {
            reply.put("next", start + count);
        } else {
            reply.remove("next");
        }
        return MessagePages.checked(reply, limit);
    }

    /**
     * Returns the refusal of a position whose record is no longer stored as it was answered.
     *
     * @param task The record's task.
     * @return The error.
     */
    static VarlinkException changed(final String task) {
        return new VarlinkException(INTERFACE + ".RecordChanged", Map.of("task", task));
    }

    /** A record too large alone: its first calls that fit, and where the rest start. */
    private static Map<String, Object> firstCalls(
            final Map<String, Object> record, final long room) {
        final List<?> calls = record.get("toolCalls") instanceof List<?> list ? list : List.of();
        final Map<String, Object> first = new LinkedHashMap<>(record);
        first.put("toolCalls", List.of());
        first.put("toolCallsNext", Integer.MAX_VALUE);
        final long left = room - MessagePages.sent(first) - 1;
        if (left < 0) {
            throw LogPages.failed("A task record does not fit one message even without its calls");
        }
        final int count = MessagePages.fitting(calls, 0, left);
        first.put("toolCalls", new ArrayList<>(calls.subList(0, count)));
        if (count < calls.size()) {
            first.put("toolCallsNext", count);
        } else {
            first.remove("toolCallsNext");
        }
        return first;
    }

    private static boolean startedAfter(final Map<String, Object> record, final Instant asOf) {
        if (!(record.get("startedAt") instanceof String started)) {
            return false;
        }
        try {
            return Instant.parse(started).isAfter(asOf);
        } catch (DateTimeParseException ex) {
            // A start that is no time is no later one: it is answered.
            return false;
        }
    }
}
