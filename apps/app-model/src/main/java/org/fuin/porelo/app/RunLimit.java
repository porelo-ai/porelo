package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;

/**
 * An unattended run's time limit, kept in its task's state: what the run was given, and whether it
 * was ended by it. A fleet view reads both; the planner sets the limit per phase.
 */
public final class RunLimit {

    /** What the current run was given. */
    static final String GIVEN = "run-limit.json";

    /** Written when porelo ended the run because its limit passed. */
    static final String ENDED = "ended-by-limit.json";

    /**
     * A run ended by its limit.
     *
     * @param minutes The limit that passed.
     * @param at When porelo ended it, ISO 8601.
     */
    public record Ended(int minutes, String at) {}

    /** The longest limit a run may be given: the largest number the command line takes. */
    public static final int MOST = Integer.MAX_VALUE;

    private RunLimit() {}

    /**
     * Reads a run's limit as the command line or a varlink call gives it, where every number of a
     * call may be a double.
     *
     * @param given What was given.
     * @return The minutes, or {@code null} when it is no whole number from 1 to {@link #MOST}: zero
     *     or less, a fraction, a number past the bound, a string or a boolean is no limit.
     */
    public static @Nullable Integer wholeMinutes(@Nullable Object given) {
        if (!(given instanceof Number number)) {
            return null;
        }
        final double minutes = number.doubleValue();
        return minutes >= 1 && minutes <= MOST && minutes == Math.rint(minutes)
                ? Integer.valueOf((int) minutes)
                : null;
    }

    /**
     * Says why a run's limit is refused, in the same words on the command line and in Tasks1 Start:
     * one of zero ended the run before it began, and one read as another number bounded the agent
     * to something nobody asked for.
     *
     * @param given What was given.
     * @return The refusal, or {@code null} when it is a whole number from 1 up.
     */
    public static @Nullable String refusal(@Nullable Object given) {
        if (wholeMinutes(given) != null) {
            return null;
        }
        return "takes a whole number of minutes from 1 to "
                + MOST
                + ", not "
                + (given instanceof String text ? "'" + text + "'" : given);
    }

    /**
     * Records a run's limit as it starts, and forgets an earlier run's end by its limit.
     *
     * @param state The task's state directory.
     * @param minutes The limit.
     * @throws IOException If it cannot be written.
     */
    public static void started(Path state, int minutes) throws IOException {
        started(state, minutes, Instant.now(), Waiter.current());
    }

    /**
     * Records a run's limit as it starts: its minutes, the moment they pass, and the process that
     * waits on the run, so that something else can end it when that process is gone.
     *
     * @param state The task's state directory.
     * @param minutes The limit.
     * @param now When the run starts.
     * @param waiter The process waiting on the run.
     * @throws IOException If it cannot be written.
     */
    static void started(Path state, int minutes, Instant now, Waiter waiter) throws IOException {
        Files.deleteIfExists(state.resolve(ENDED));
        final Map<String, Object> record = new java.util.LinkedHashMap<>();
        record.put("minutes", minutes);
        record.put("until", now.plus(java.time.Duration.ofMinutes(minutes)).toString());
        record.put("waiterPid", waiter.pid());
        if (waiter.started() != null) {
            record.put("waiterStarted", waiter.started());
        }
        write(state.resolve(GIVEN), record);
    }

    /**
     * Records that the run ended, whatever ended it, so that its deadline ends nothing more.
     *
     * @param state The task's state directory.
     * @throws IOException If it cannot be written.
     */
    public static void closed(Path state) throws IOException {
        if (!(read(state.resolve(GIVEN)) instanceof Map<?, ?> map)) {
            return;
        }
        final Map<String, Object> record = new java.util.LinkedHashMap<>();
        map.forEach((key, value) -> record.put(String.valueOf(key), value));
        record.put("closed", true);
        write(state.resolve(GIVEN), record);
    }

    /**
     * Returns the limit of a run whose deadline passed while nothing waits on it: not closed, not
     * already ended by its limit, and its waiter gone.
     *
     * @param state The task's state directory.
     * @param now The moment to read the deadline against.
     * @param alive Whether a waiter still runs.
     * @return The minutes the run was given, or {@code null} when nothing is due: a record without
     *     a deadline (written before one was kept) is never due.
     */
    public static @Nullable Integer due(
            Path state, Instant now, java.util.function.Predicate<Waiter> alive) {
        if (endedBy(state) != null
                || !(read(state.resolve(GIVEN)) instanceof Map<?, ?> map)
                || Boolean.TRUE.equals(map.get("closed"))
                || !(map.get("minutes") instanceof Number minutes)
                || !(map.get("until") instanceof String until)
                || !(map.get("waiterPid") instanceof Number pid)) {
            return null;
        }
        final Instant deadline;
        try {
            deadline = Instant.parse(until);
        } catch (java.time.format.DateTimeParseException ex) {
            return null;
        }
        if (now.isBefore(deadline)) {
            return null;
        }
        final Waiter waiter =
                new Waiter(
                        pid.longValue(),
                        map.get("waiterStarted") instanceof String started ? started : null);
        return alive.test(waiter) ? null : Integer.valueOf(minutes.intValue());
    }

    /**
     * The process waiting on a run: its pid, and when it started, so a pid used again is not taken
     * for it.
     *
     * @param pid The process id.
     * @param started When it started, ISO 8601, or {@code null} when the system does not say.
     */
    public record Waiter(long pid, @Nullable String started) {

        /**
         * Returns this process.
         *
         * @return It.
         */
        public static Waiter current() {
            return of(ProcessHandle.current());
        }

        private static Waiter of(ProcessHandle process) {
            return new Waiter(
                    process.pid(),
                    process.info().startInstant().map(Instant::toString).orElse(null));
        }

        /**
         * Returns whether this process still runs.
         *
         * @return {@code true} when a process of this pid runs and, where both say when it started,
         *     started then.
         */
        public boolean alive() {
            return ProcessHandle.of(pid)
                    .filter(ProcessHandle::isAlive)
                    .map(Waiter::of)
                    .filter(
                            found ->
                                    started == null
                                            || found.started() == null
                                            || started.equals(found.started()))
                    .isPresent();
        }
    }

    /**
     * Records that porelo ended a run because its limit passed.
     *
     * @param state The task's state directory.
     * @param minutes The limit that passed.
     * @param at When.
     * @throws IOException If it cannot be written.
     */
    public static void ended(Path state, int minutes, Instant at) throws IOException {
        write(state.resolve(ENDED), Map.of("minutes", minutes, "at", at.toString()));
    }

    /**
     * Returns the limit the task's last run was given.
     *
     * @param state The task's state directory.
     * @return Minutes, or 0 when no run recorded one: an attended session, or a task started before
     *     it was recorded.
     */
    public static int minutes(Path state) {
        return read(state.resolve(GIVEN)) instanceof Map<?, ?> map
                        && map.get("minutes") instanceof Number number
                ? number.intValue()
                : 0;
    }

    /**
     * Returns whether the task's last run was ended by its limit.
     *
     * @param state The task's state directory.
     * @return How, or {@code null} when it was not.
     */
    public static @Nullable Ended endedBy(Path state) {
        return read(state.resolve(ENDED)) instanceof Map<?, ?> map
                        && map.get("minutes") instanceof Number number
                        && map.get("at") instanceof String at
                ? new Ended(number.intValue(), at)
                : null;
    }

    private static void write(Path file, Map<String, Object> record) throws IOException {
        Files.createDirectories(file.getParent());
        final Path written = file.resolveSibling(file.getFileName() + ".new");
        Files.writeString(written, Json.write(record), StandardCharsets.UTF_8);
        Files.move(
                written,
                file,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    private static @Nullable Object read(Path file) {
        try {
            return Files.isRegularFile(file)
                    ? Json.parse(Files.readString(file, StandardCharsets.UTF_8))
                    : null;
        } catch (IOException | RuntimeException ex) {
            // A record that cannot be read says nothing, as one that is missing.
            return null;
        }
    }
}
