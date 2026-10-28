package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;

/**
 * The account's event log: every security-relevant event of this node - an approval, a clearance at
 * run time, a hand-in, a credential given at a start, a project file applied - one line each,
 * hash-chained, and each line's hash anchored in the user's systemd journal.
 *
 * <p><strong>Why the journal.</strong> An account's own file cannot be made tamper-proof against
 * the account: it can rewrite the file and recompute every hash. The journal it can append to but
 * not rewrite. So a line changed or removed afterwards leaves a hash in the journal that the file
 * no longer holds, whatever the file's chain says.
 */
public final class EventLog {

    /** An approval of a task's work: onto which branch, at which commit. */
    public static final String APPROVED = "approved";

    /** A task's work rejected at its gate. */
    public static final String REJECTED = "rejected";

    /** A connection a person allowed or denied while the task ran. */
    public static final String CLEARANCE = "clearance";

    /** A file handed into a task, or taken back. */
    public static final String HAND_IN = "hand-in";

    /** A credential a start gave a task beyond its agent's own. */
    public static final String CREDENTIAL = "credential";

    /** A project file applied to this machine. */
    public static final String PROJECT_APPLIED = "project-applied";

    /** Every task of the account stopped at once. */
    public static final String STOPPED_ALL = "stopped-all";

    /** What {@link #entries} shows for a line that cannot be read, rather than failing on it. */
    public static final String UNREADABLE = "unreadable";

    /**
     * A hash the anchor holds, and when it was anchored.
     *
     * @param hash The hash.
     * @param at When the anchor took it.
     */
    public record Anchored(String hash, Instant at) {}

    /** What anchors a line's hash where the account cannot rewrite it. */
    public interface Anchor {

        /**
         * Anchors a hash. Never fails the event: an anchor that cannot be written leaves a line the
         * verification names as not anchored.
         *
         * @param hash The line's hash.
         * @param kind The line's kind, for a person reading the journal.
         * @return Whether it was anchored.
         */
        boolean send(String hash, String kind);

        /**
         * Returns every hash anchored so far, oldest first, with when each was anchored.
         *
         * @return The hashes, or {@code null} when the anchor cannot be read.
         */
        @Nullable List<Anchored> anchored();
    }

    /**
     * What a verification found.
     *
     * @param lines How many lines the log holds.
     * @param firstBroken The number of the first line whose chain does not hold, or zero.
     * @param notAnchored The numbers of lines whose hash the anchor does not hold, other than those
     *     counted in {@code beforeAnchor}.
     * @param missing Hashes the anchor holds that the log does not: lines changed or removed.
     * @param anchorRead Whether the anchor could be read at all.
     * @param beforeAnchor How many lines lie ahead of the first anchored one and are older than the
     *     oldest hash the anchor still holds of this log, and not held: rotated out of the journal;
     *     the chain alone vouches for them. A log the anchor holds nothing of has none: every line
     *     of it counts as not anchored.
     */
    public record Verification(
            int lines,
            int firstBroken,
            List<Integer> notAnchored,
            List<String> missing,
            boolean anchorRead,
            int beforeAnchor) {

        /**
         * Says whether nothing was changed: the chain holds and the anchor agrees line for line.
         *
         * @return {@code true} when the log verifies.
         */
        public boolean holds() {
            return firstBroken == 0 && missing.isEmpty() && notAnchored.isEmpty() && anchorRead;
        }
    }

    private final Path file;

    private final Anchor anchor;

    /**
     * Constructor.
     *
     * @param file The log.
     * @param anchor Where each hash is anchored.
     */
    public EventLog(final Path file, final Anchor anchor) {
        this.file = file;
        this.anchor = anchor;
    }

    /**
     * Returns the account's log, anchored in the user's journal.
     *
     * @param context The machine.
     * @return The log.
     */
    public static EventLog of(final PoreloContext context) {
        return new EventLog(
                context.paths().eventLog(),
                new Journal(context.runner(), context.paths().eventLog()));
    }

    /**
     * Appends an event, and never fails the act it records: an event that cannot be written is said
     * on standard error and the act goes on.
     *
     * @param context The machine.
     * @param kind One of the constants here.
     * @param project The project, or "".
     * @param task The task's container, or "".
     * @param detail What happened, in words and values; never a secret.
     */
    public static void record(
            final PoreloContext context,
            final String kind,
            final String project,
            final String task,
            final String detail) {
        try {
            of(context).append(kind, project, task, detail);
        } catch (IOException | RuntimeException ex) {
            System.err.println(
                    "porelo: the event log could not record '" + kind + "': " + ex.getMessage());
        }
    }

    /**
     * Appends an event.
     *
     * @param kind One of the constants here.
     * @param project The project, or "".
     * @param task The task's container, or "".
     * @param detail What happened; never a secret.
     * @throws IOException If the log cannot be written.
     */
    public void append(
            final String kind, final String project, final String task, final String detail)
            throws IOException {
        // Read and written under one lock: two commands at once read the same last hash, and the
        // log then said it had been changed when nobody had.
        // Anchored under the lock too, so a verification never sees a line whose hash is still on
        // its way to the journal.
        FileLocks.holding(
                lock(),
                () -> {
                    anchor.send(appendHeld(kind, project, task, detail), kind);
                    return Boolean.TRUE;
                });
    }

    private Path lock() {
        return file.resolveSibling(".events.lock");
    }

    private String appendHeld(
            final String kind, final String project, final String task, final String detail)
            throws IOException {
        final String previous = lastHash();
        final String at = Instant.now().toString();
        final String hash = HashChain.link(previous, List.of(at, kind, project, task, detail));
        final Map<String, Object> line = new LinkedHashMap<>();
        line.put("at", at);
        line.put("kind", kind);
        line.put("project", project);
        line.put("task", task);
        line.put("detail", detail);
        line.put("previous", previous);
        line.put("hash", hash);
        Files.createDirectories(file.getParent());
        Files.writeString(
                file,
                Json.write(line) + "\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
        return hash;
    }

    /**
     * Returns the events, oldest first.
     *
     * @param since Only those at or after it, or {@code null} for all.
     * @return Each line as written.
     * @throws IOException If the log cannot be read.
     */
    public List<Map<String, Object>> entries(final @Nullable Instant since) throws IOException {
        final List<Map<String, Object>> found = new ArrayList<>();
        for (final Map<String, Object> read : lines()) {
            final Map<String, Object> line = shown(read);
            final Instant at = instant(text(line, "at"));
            if (since == null || at == null || !at.isBefore(since)) {
                found.add(line);
            }
        }
        return found;
    }

    /**
     * Checks the chain, and every line against the anchor.
     *
     * @return What was found.
     * @throws IOException If the log cannot be read.
     */
    public Verification verify() throws IOException {
        // Under the lock an append takes, so a line and its anchor are seen together or not at all.
        return FileLocks.holding(lock(), this::verifyHeld);
    }

    private Verification verifyHeld() throws IOException {
        final List<Map<String, Object>> lines = lines();
        String previous = "";
        int broken = 0;
        final Set<String> held = new LinkedHashSet<>();
        for (int number = 1; number <= lines.size(); number++) {
            final Map<String, Object> line = lines.get(number - 1);
            final String expected =
                    HashChain.link(
                            previous,
                            List.of(
                                    text(line, "at"),
                                    text(line, "kind"),
                                    text(line, "project"),
                                    text(line, "task"),
                                    text(line, "detail")));
            if (broken == 0
                    && (!expected.equals(text(line, "hash"))
                            || !previous.equals(text(line, "previous")))) {
                broken = number;
            }
            held.add(text(line, "hash"));
            previous = text(line, "hash");
        }
        final List<Anchored> anchored = anchor.anchored();
        if (anchored == null) {
            return new Verification(lines.size(), broken, List.of(), List.of(), false, 0);
        }
        final Set<String> inAnchor = new LinkedHashSet<>();
        Instant oldest = null;
        for (final Anchored each : anchored) {
            inAnchor.add(each.hash());
            if (oldest == null || each.at().isBefore(oldest)) {
                oldest = each.at();
            }
        }
        // Only a line ahead of the first the journal holds, and older than the oldest hash it still
        // holds of this log, may lack its own: the journal drops its oldest entries first, and the
        // chain ties such a line to the anchored ones after it. A line after the first anchored
        // one must be there whatever its date says, so a line appended later with an old date is
        // named; a log rewritten with fresh hashes is named line by line; and a log the journal
        // holds nothing of holds nothing.
        int firstAnchored = 0;
        for (int number = 1; number <= lines.size() && firstAnchored == 0; number++) {
            if (inAnchor.contains(text(lines.get(number - 1), "hash"))) {
                firstAnchored = number;
            }
        }
        final List<Integer> notAnchored = new ArrayList<>();
        int beforeAnchor = 0;
        for (int number = 1; number <= lines.size(); number++) {
            final Map<String, Object> line = lines.get(number - 1);
            if (inAnchor.contains(text(line, "hash"))) {
                continue;
            }
            final Instant at = instant(text(line, "at"));
            if (number < firstAnchored && oldest != null && at != null && at.isBefore(oldest)) {
                beforeAnchor++;
            } else {
                notAnchored.add(number);
            }
        }
        final List<String> missing = new ArrayList<>();
        for (final String hash : inAnchor) {
            if (!held.contains(hash)) {
                missing.add(hash);
            }
        }
        return new Verification(lines.size(), broken, notAnchored, missing, true, beforeAnchor);
    }

    private static @Nullable Instant instant(final String text) {
        try {
            return Instant.parse(text);
        } catch (java.time.format.DateTimeParseException ex) {
            return null;
        }
    }

    /**
     * A line as it is shown: every field there, and one without a hash and a kind said to be
     * unreadable, its other fields "" so that no date it claims keeps it from a listing.
     */
    private static Map<String, Object> shown(final Map<String, Object> line) {
        final Map<String, Object> shown = new LinkedHashMap<>();
        final boolean readable = word(line.get("hash")) && word(line.get("kind"));
        for (final String key :
                List.of("at", "kind", "project", "task", "detail", "hash", "previous")) {
            shown.put(key, readable ? text(line, key) : "kind".equals(key) ? UNREADABLE : "");
        }
        return shown;
    }

    private List<Map<String, Object>> lines() throws IOException {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        final List<Map<String, Object>> parsed = new ArrayList<>();
        for (final String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            final Map<String, Object> entry = new LinkedHashMap<>();
            try {
                if (Json.parse(line) instanceof Map<?, ?> map) {
                    map.forEach((key, value) -> entry.put(String.valueOf(key), value));
                }
            } catch (RuntimeException ex) {
                // An unreadable line breaks the chain by being unreadable; saying so is the point.
            }
            parsed.add(entry);
        }
        return parsed;
    }

    private String lastHash() throws IOException {
        final List<Map<String, Object>> lines = lines();
        return lines.isEmpty() ? "" : text(lines.getLast(), "hash");
    }

    private static boolean word(final @Nullable Object value) {
        return value instanceof String text && !text.isBlank();
    }

    private static String text(final Map<String, Object> line, final String key) {
        final Object value = line.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * The user's systemd journal: written through {@code logger --journald}, a line of fields on
     * standard input, read back with {@code journalctl --user}. The account can append to it and
     * read it, never rewrite it.
     */
    static final class Journal implements Anchor {

        /** The journal field that holds a line's hash. */
        static final String FIELD = "PORELO_EVENT_HASH";

        /**
         * The journal field that names the log a hash belongs to: a log elsewhere - a test's, a
         * second state directory - is another log, and its hashes are not this one's lost lines.
         */
        static final String LOG_FIELD = "PORELO_EVENT_LOG";

        /** How Porelo's event lines are named in the journal. */
        static final String IDENTIFIER = "porelo-events";

        private final CommandRunner runner;

        private final String log;

        Journal(final CommandRunner runner, final Path log) {
            this.runner = runner;
            this.log = log.toAbsolutePath().normalize().toString();
        }

        @Override
        public boolean send(final String hash, final String kind) {
            try {
                return runner.run(
                                Command.of("logger", "--journald")
                                        .withInput(
                                                "MESSAGE=porelo event "
                                                        + kind
                                                        + " "
                                                        + hash
                                                        + "\nSYSLOG_IDENTIFIER="
                                                        + IDENTIFIER
                                                        + "\n"
                                                        + FIELD
                                                        + "="
                                                        + hash
                                                        + "\n"
                                                        + LOG_FIELD
                                                        + "="
                                                        + log
                                                        + "\n"))
                        .successful();
            } catch (RuntimeException ex) {
                return false;
            }
        }

        @Override
        public @Nullable List<Anchored> anchored() {
            final CommandResult result;
            try {
                result =
                        runner.run(
                                Command.of(
                                        "journalctl",
                                        "--user",
                                        "--no-pager",
                                        "--output",
                                        "json",
                                        "--output-fields",
                                        FIELD,
                                        "SYSLOG_IDENTIFIER=" + IDENTIFIER,
                                        LOG_FIELD + "=" + log));
            } catch (RuntimeException ex) {
                return null;
            }
            if (!result.successful()) {
                return null;
            }
            final List<Anchored> hashes = new ArrayList<>();
            for (final String line : result.standardOutput().lines().toList()) {
                try {
                    if (Json.parse(line) instanceof Map<?, ?> entry
                            && entry.get(FIELD) instanceof String hash
                            && entry.get("__REALTIME_TIMESTAMP") instanceof String micros) {
                        final long value = Long.parseLong(micros);
                        hashes.add(
                                new Anchored(
                                        hash,
                                        Instant.ofEpochSecond(
                                                value / 1_000_000L,
                                                (value % 1_000_000L) * 1_000L)));
                    }
                } catch (RuntimeException ex) {
                    // A line journalctl printed that is not one entry carries no hash.
                }
            }
            return hashes;
        }
    }
}
