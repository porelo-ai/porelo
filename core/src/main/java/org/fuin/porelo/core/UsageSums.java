package org.fuin.porelo.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;

/** Sums reported usage without treating an unreported token count as zero. */
public final class UsageSums {
    private UsageSums() {}

    private static final List<String> FIELDS =
            List.of("input", "cache_read", "cache_write", "output", "reasoning");
    private static final List<String> WIRE_FIELDS =
            List.of("input", "cacheRead", "cacheWrite", "output", "reasoning");

    /** One field's reported total and the number of requests that did not report it. */
    public record Count(@Nullable Long tokens, long missing) {
        /** Returns the contract's two fields. */
        public Map<String, Object> asMap() {
            final Map<String, Object> result = new LinkedHashMap<>();
            result.put("tokens", tokens);
            result.put("missing", missing);
            return result;
        }
    }

    /** Counts and missing counts for one set of requests. */
    public record Totals(long requests, List<Count> fields) {
        /** Returns the common totals used by every grouping. */
        public Map<String, Object> asMap() {
            final Map<String, Object> result = new LinkedHashMap<>();
            result.put("requests", requests);
            for (int i = 0; i < WIRE_FIELDS.size(); i++)
                result.put(WIRE_FIELDS.get(i), fields.get(i).asMap());
            return result;
        }
    }

    /** Totals for a model, agent, local day or local hour. */
    public record Group(String name, Totals totals) {
        /** Returns the contract's group name and totals. */
        public Map<String, Object> asMap() {
            return Map.of("name", name, "totals", totals.asMap());
        }
    }

    /**
     * The reading state and all four views of the same record.
     *
     * @param state Whether it could be read.
     * @param totals Over every request.
     * @param models By the model that answered.
     * @param agents By agent.
     * @param days By local day.
     * @param hours By local hour.
     * @param lastBegan When the latest finished request in it began, or {@code null} when none is
     *     known: a line is written when its answer ends, with the time near its start.
     */
    public record Summary(
            String state,
            Totals totals,
            List<Group> models,
            List<Group> agents,
            List<Group> days,
            List<Group> hours,
            @Nullable Instant lastBegan) {
        /**
         * Returns the additive task usage contract; the start of the latest request is not in it.
         */
        public Map<String, Object> asMap() {
            return Map.of(
                    "state",
                    state,
                    "totals",
                    totals.asMap(),
                    "models",
                    models.stream().map(Group::asMap).toList(),
                    "agents",
                    agents.stream().map(Group::asMap).toList(),
                    "days",
                    days.stream().map(Group::asMap).toList(),
                    "hours",
                    hours.stream().map(Group::asMap).toList());
        }
    }

    /** Reads one JSONL record, grouping times in the host's local zone. */
    public static Summary read(Path record, ZoneId zone) {
        final Accumulator totals = new Accumulator();
        final List<Map<String, Accumulator>> groups =
                List.of(new TreeMap<>(), new TreeMap<>(), new TreeMap<>(), new TreeMap<>());
        Instant lastBegan = null;
        try (BufferedReader reader = Files.newBufferedReader(record)) {
            String line;
            while ((line = line(reader)) != null) {
                Map<?, ?> entry;
                List<String> keys;
                try {
                    entry = (Map<?, ?>) java.util.Objects.requireNonNull(Json.parseDecimals(line));
                    Accumulator.check(entry);
                    final Instant began = Instant.parse(String.valueOf(entry.get("time")));
                    final var local = began.atZone(zone);
                    keys =
                            List.of(
                                    name(entry.get("model_answered")),
                                    name(entry.get("agent")),
                                    local.toLocalDate().toString(),
                                    local.truncatedTo(ChronoUnit.HOURS)
                                            .toOffsetDateTime()
                                            .toString());
                    lastBegan = lastBegan == null || began.isAfter(lastBegan) ? began : lastBegan;
                } catch (RuntimeException | StackOverflowError ex) {
                    // A killed broker's half line. That request was billed: it stays a request of
                    // which nothing is known, and takes nothing else with it.
                    entry = Map.of();
                    keys = List.of("unknown", "unknown", "unknown", "unknown");
                }
                totals.add(entry);
                for (int i = 0; i < groups.size(); i++)
                    groups.get(i)
                            .computeIfAbsent(keys.get(i), ignored -> new Accumulator())
                            .add(entry);
            }
        } catch (java.nio.file.NoSuchFileException ex) {
            return empty("absent");
        } catch (IOException | RuntimeException | StackOverflowError ex) {
            return empty("unreadable");
        }
        return new Summary(
                "available",
                totals.value(),
                groups(groups.get(0)),
                groups(groups.get(1)),
                groups(groups.get(2)),
                groups(groups.get(3)),
                lastBegan);
    }

    private static String name(@Nullable Object value) {
        if (value == null) return "unknown";
        if (!(value instanceof String text) || !text.matches("[A-Za-z0-9_./:@+~\\-]{1,200}"))
            throw new IllegalArgumentException("Invalid usage name");
        return text;
    }

    private static @Nullable String line(BufferedReader reader) throws IOException {
        final StringBuilder line = new StringBuilder();
        int next;
        while ((next = reader.read()) != -1) {
            if (next == '\n') return line.toString();
            if (line.length() >= 1024 * 1024) throw new IOException("Usage record line too large");
            line.append((char) next);
        }
        // A last line without its end is being written, or was never finished: no request yet.
        return null;
    }

    /**
     * Returns the sums of a record that does not exist.
     *
     * @return Empty sums, in the state {@code absent}.
     */
    public static Summary absent() {
        return empty("absent");
    }

    /**
     * Returns the sums of a record that could not be reached.
     *
     * @return Empty sums, in the state {@code unreadable}.
     */
    public static Summary unreadable() {
        return empty("unreadable");
    }

    private static Summary empty(String state) {
        return new Summary(
                state, new Accumulator().value(), List.of(), List.of(), List.of(), List.of(), null);
    }

    private static List<Group> groups(Map<String, Accumulator> entries) {
        return entries.entrySet().stream()
                .map(entry -> new Group(entry.getKey(), entry.getValue().value()))
                .toList();
    }

    private static final class Accumulator {
        private long requests;
        private final long[] sums = new long[FIELDS.size()];
        private final long[] missing = new long[FIELDS.size()];

        /** Refuses what {@link #add} could not count, before anything was added. */
        static void check(Map<?, ?> entry) {
            for (final String field : FIELDS) {
                final Object value = entry.get(field);
                if (value != null
                        && !(value instanceof java.math.BigDecimal number
                                && number.signum() >= 0
                                && number.longValueExact() >= 0))
                    throw new IllegalArgumentException("Invalid usage count");
            }
        }

        void add(Map<?, ?> entry) {
            requests = Math.incrementExact(requests);
            for (int i = 0; i < FIELDS.size(); i++) {
                final Object value = entry.get(FIELDS.get(i));
                if (value == null) missing[i] = Math.incrementExact(missing[i]);
                else
                    sums[i] =
                            Math.addExact(sums[i], ((java.math.BigDecimal) value).longValueExact());
            }
        }

        Totals value() {
            final List<Count> fields = new ArrayList<>();
            for (int i = 0; i < FIELDS.size(); i++)
                fields.add(new Count(missing[i] == requests ? null : sums[i], missing[i]));
            return new Totals(requests, List.copyOf(fields));
        }
    }
}
