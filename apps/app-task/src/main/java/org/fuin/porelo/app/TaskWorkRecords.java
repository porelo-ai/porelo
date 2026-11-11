package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;

/**
 * Keeps one work record per task: what it worked on, from when to when, with which agent and model,
 * the tokens its usage record counts, and one entry per tool call of its unattended runs.
 *
 * <p>Written when the task is made, completed at the end of each run, at a stop and at its removal,
 * and kept past the removal under a fresh name, as its usage record is. Planning reads them through
 * {@link #since}, never the files.
 *
 * @param context Where the records are.
 */
public record TaskWorkRecords(PoreloContext context) {

    /** The run ended with exit code 0. */
    public static final String DONE = "done";

    /** The run ended otherwise. */
    public static final String FAILED = "failed";

    /** The task was stopped before anything else ended it. */
    public static final String STOPPED = "stopped";

    /** The task was removed before anything else ended it. */
    public static final String REMOVED = "removed";

    private static final String RETAINED = ".removed-";

    /**
     * One writer at a time, across processes: porelod and a command line both end a task's record.
     * A record is replaced whole.
     */
    private static final String LOCK = ".lock";

    /**
     * Writes a new task's record.
     *
     * @param container Its container name.
     * @param project Its project.
     * @param repository The repository it works on, or {@code null}.
     * @param work The issue and phase it was started for, or {@code null}.
     * @param agent Its agent, or {@code null}.
     * @param model The model it was started with, or {@code null} for the agent's default.
     * @param modelFrom Where the model was settled from.
     * @throws IOException If it cannot be written.
     */
    public void started(
            final String container,
            final String project,
            final @Nullable String repository,
            final @Nullable TaskWork work,
            final @Nullable String agent,
            final @Nullable String model,
            final String modelFrom)
            throws IOException {
        if (!TaskUsage.validTask(container)) {
            return;
        }
        final Map<String, Object> record = new LinkedHashMap<>();
        record.put("task", container);
        record.put("project", project);
        record.put("repository", repository);
        record.put("work", work == null ? null : work.asMap());
        record.put("agent", agent);
        record.put("model", model);
        record.put("modelFrom", modelFrom);
        // Start has no effort setting yet.
        record.put("effort", null);
        record.put("startedAt", Instant.now().toString());
        record.put("endedAt", null);
        record.put("end", null);
        record.put("totals", totals(container));
        record.put("toolCallsAsked", ToolCalls.DID_NOT_ASK);
        record.put("toolCalls", List.of());
        locked(
                () -> {
                    write(file(container), record);
                    return Boolean.TRUE;
                });
    }

    /**
     * Marks a task working again: a run starts, so its earlier end no longer holds.
     *
     * @param container Its container name.
     * @throws IOException If it cannot be written.
     */
    public void runStarted(final String container) throws IOException {
        update(
                container,
                record -> {
                    record.put("endedAt", null);
                    record.put("end", null);
                });
    }

    /**
     * Completes a task's record at the end of a run, with the run's tool calls.
     *
     * @param container Its container name.
     * @param calls The run's tool calls.
     * @param exitCode The run's exit code.
     * @throws IOException If it cannot be written.
     */
    public void runEnded(final String container, final ToolCalls calls, final int exitCode)
            throws IOException {
        update(
                container,
                record -> {
                    final List<Object> entries = new ArrayList<>();
                    if (record.get("toolCalls") instanceof List<?> earlier) {
                        entries.addAll(earlier);
                    }
                    entries.addAll(calls.entries());
                    record.put("toolCalls", entries);
                    record.put(
                            "toolCallsAsked", asked(record.get("toolCallsAsked"), calls.asked()));
                    record.put("endedAt", Instant.now().toString());
                    record.put("end", exitCode == 0 ? DONE : FAILED);
                    record.put("totals", totals(container));
                });
    }

    private static String asked(final @Nullable Object earlier, final String now) {
        // A run that could not say is never hidden by one that could.
        if (ToolCalls.CANNOT_SAY.equals(earlier) || ToolCalls.CANNOT_SAY.equals(now)) {
            return ToolCalls.CANNOT_SAY;
        }
        return ToolCalls.ASKED.equals(earlier) || ToolCalls.ASKED.equals(now)
                ? ToolCalls.ASKED
                : ToolCalls.DID_NOT_ASK;
    }

    /**
     * Completes a task's record at a stop, unless a run's end completed it already.
     *
     * @param container Its container name.
     * @throws IOException If it cannot be written.
     */
    public void stopped(final String container) throws IOException {
        update(container, record -> ending(record, STOPPED, container));
    }

    /**
     * Completes a task's record at its removal and keeps it under a fresh name, so a later task of
     * the same name starts a record of its own. Called before its usage record is kept, whose
     * totals it takes.
     *
     * @param container Its container name.
     * @throws IOException If it cannot be written or kept.
     */
    public void removed(final String container) throws IOException {
        if (!TaskUsage.validTask(container)) {
            return;
        }
        locked(
                () -> {
                    final Path file = file(container);
                    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                        return Boolean.FALSE;
                    }
                    final Map<String, Object> record = read(file);
                    ending(record, REMOVED, container);
                    write(file, record);
                    long at = System.currentTimeMillis();
                    Path retained = file.resolveSibling(container + RETAINED + at + ".json");
                    while (Files.exists(retained, LinkOption.NOFOLLOW_LINKS)) {
                        at++;
                        retained = file.resolveSibling(container + RETAINED + at + ".json");
                    }
                    Files.move(file, retained);
                    return Boolean.TRUE;
                });
    }

    private void ending(final Map<String, Object> record, final String end, final String task) {
        if (record.get("end") == null) {
            record.put("endedAt", Instant.now().toString());
            record.put("end", end);
        }
        record.put("totals", totals(task));
    }

    /**
     * Answers a project's records since a time, removed tasks' among them, oldest first.
     *
     * @param project The project.
     * @param since The earliest end of a record answered; a record not yet ended is answered.
     * @return The records.
     * @throws IOException If the directory cannot be read.
     */
    public List<Map<String, Object>> since(final String project, final Instant since)
            throws IOException {
        final Path directory = context.paths().tasks().workRecords();
        final List<Map<String, Object>> found = new ArrayList<>();
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            return found;
        }
        // Under the writers' lock: a removal between the listing and the reading would move a
        // record away, and its totals with it.
        return locked(() -> since(directory, project, since));
    }

    private List<Map<String, Object>> since(
            final Path directory, final String project, final Instant since) throws IOException {
        final List<Map<String, Object>> found = new ArrayList<>();
        final List<Path> files;
        try (var entries = Files.list(directory)) {
            files =
                    entries.filter(file -> file.getFileName().toString().endsWith(".json"))
                            .filter(file -> Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                            .toList();
        }
        for (final Path file : files) {
            final Map<String, Object> record;
            try {
                record = read(file);
            } catch (java.nio.file.NoSuchFileException gone) {
                // Removed by hand meanwhile: it says nothing now.
                continue;
            }
            if (!project.equals(record.get("project"))) {
                continue;
            }
            final Instant ended = instant(record.get("endedAt"));
            if (ended != null && ended.isBefore(since)) {
                continue;
            }
            if (!file.getFileName().toString().contains(RETAINED)
                    && record.get("task") instanceof String task) {
                // A task that is still there counts on.
                record.put("totals", totals(task));
            }
            found.add(record);
        }
        found.sort(
                Comparator.comparing(
                        record -> String.valueOf(record.get("startedAt")),
                        Comparator.naturalOrder()));
        return found;
    }

    /**
     * Reads the time a caller gives for {@link #since}: an ISO-8601 instant, a date and time with
     * its offset, or a date, which starts in this machine's zone.
     *
     * @param given The text.
     * @return The time, or {@code null} for text that is none.
     */
    public static @Nullable Instant parseSince(final String given) {
        try {
            return OffsetDateTime.parse(given).toInstant();
        } catch (DateTimeParseException notWithOffset) {
            try {
                return LocalDate.parse(given).atStartOfDay(ZoneId.systemDefault()).toInstant();
            } catch (DateTimeParseException notADate) {
                return null;
            }
        }
    }

    private static @Nullable Instant instant(final @Nullable Object value) {
        return value instanceof String text ? parseSince(text) : null;
    }

    private Map<String, Object> totals(final String task) {
        return new TaskUsage(context).read(task).totals().asMap();
    }

    private Path file(final String container) {
        return context.paths().tasks().workRecord(container);
    }

    private interface Change {
        void apply(Map<String, Object> record);
    }

    private void update(final String container, final Change change) throws IOException {
        if (!TaskUsage.validTask(container)) {
            return;
        }
        locked(
                () -> {
                    final Path file = file(container);
                    // A task made before records were kept has none to complete; a removed one's
                    // is kept under another name and never written again.
                    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                        return Boolean.FALSE;
                    }
                    final Map<String, Object> record = read(file);
                    change.apply(record);
                    write(file, record);
                    return Boolean.TRUE;
                });
    }

    private <T> T locked(final FileLocks.Locked<T> locked) throws IOException {
        final Path directory = context.paths().tasks().workRecords();
        org.fuin.porelo.wire.Directories.create(directory);
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        return FileLocks.holding(directory.resolve(LOCK), locked);
    }

    private static Map<String, Object> read(final Path file) throws IOException {
        final Object document;
        try {
            document = Json.parseExact(Files.readString(file, StandardCharsets.UTF_8));
        } catch (RuntimeException ex) {
            throw new IOException(file + " is no task record: " + ex.getMessage(), ex);
        }
        if (!(document instanceof Map<?, ?> parsed)) {
            throw new IOException(file + " is no task record");
        }
        final Map<String, Object> record = new LinkedHashMap<>();
        parsed.forEach((key, value) -> record.put(String.valueOf(key), value));
        return record;
    }

    private static void write(final Path file, final Map<String, Object> record)
            throws IOException {
        final Path directory =
                Objects.requireNonNull(file.getParent(), "work records have a directory");
        org.fuin.porelo.wire.Directories.create(directory);
        Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        final Path staged = directory.resolve(file.getFileName() + ".new");
        Files.writeString(
                staged,
                Json.write(record),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
        Files.setPosixFilePermissions(staged, PosixFilePermissions.fromString("rw-------"));
        Files.move(
                staged, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}
