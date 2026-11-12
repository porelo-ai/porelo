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

    private RunLimit() {}

    /**
     * Records a run's limit as it starts, and forgets an earlier run's end by its limit.
     *
     * @param state The task's state directory.
     * @param minutes The limit.
     * @throws IOException If it cannot be written.
     */
    public static void started(Path state, int minutes) throws IOException {
        Files.deleteIfExists(state.resolve(ENDED));
        write(state.resolve(GIVEN), Map.of("minutes", minutes));
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
