package org.fuin.porelo.acceptance;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Tells an agent that repeats a failure from one that is still working, from what has scrolled into
 * its tasks' histories and the agent's own tool-call records.
 *
 * <p><strong>What scrolled by, not what is on the screen.</strong> A spinner, a timer and tmux's
 * status bar redraw in place, so the same line is drawn again many times a minute by an agent that
 * works; they never scroll into the history. A compact view may redraw failed tool calls too, so a
 * scenario may also declare where the agent records them.
 *
 * <p><strong>Two rules.</strong> The marker a scenario declares for a failed tool call, seen
 * {@value #MARKER_REPEATS} times in either source since the step began, is a loop: three tries of
 * one failing call is no longer working on it. Without a marker, or for a loop the marker does not
 * catch, one line seen more often than {@link #REPEATS} allows is one. Lines without a letter or a
 * digit do not count: the bottom edge of every box an agent draws is the same line.
 *
 * <p>History counts from the step's first look; each record counts from its first sight in the
 * step. The container counts literal markers without assuming a record format and sends only the
 * count, which is kept separate from history.
 */
final class LoopWatch {

    /** How often the declared marker may scroll by in one step before it is a loop. */
    static final int MARKER_REPEATS = 3;

    /** The system property that sets how often one line may scroll by in one step. */
    static final String REPEATS = "porelo.acceptance.repeats";

    /** How often one line may scroll by in one step when {@link #REPEATS} is not set. */
    static final int DEFAULT_REPEATS = 25;

    /** What separates one container's history from the next in what {@link #COMMAND} prints. */
    static final char SEPARATOR = '\u001e';

    /** Separates a tool-call record from its container's history. */
    static final char SESSION_SEPARATOR = '\u001f';

    private static final String LIST =
            "containers=$(podman ps --format '{{.Names}}' --filter name=^porelo-) || exit $?;"
                    + " for c in $containers; do";

    private static final String HISTORY =
            " printf '\\036%s\\n' \"$c\";"
                    + " podman exec \"$c\" tmux capture-pane -p -J -S - -E -1 2>/dev/null || :;";

    /** Prints each task's history, tolerating a task that stopped after the listing. */
    static final String COMMAND = LIST + HISTORY + " done; exit 0";

    private final @Nullable String marker;

    private final int repeats;

    private @Nullable Map<String, Map<String, Integer>> baseline;

    private final Map<SessionKey, Long> sessionBaseline = new HashMap<>();

    /**
     * Creates one for a step.
     *
     * @param marker What a failed tool call shows, as a scenario declared it, or {@code null}.
     * @param repeats How often one line may scroll by.
     */
    LoopWatch(@Nullable String marker, int repeats) {
        this.marker = marker;
        this.repeats = repeats;
    }

    /** One task's newest tool-call record and the marker count read inside the container. */
    record Session(String path, long count) {

        Session {
            if (count < 0) {
                throw new IllegalArgumentException(
                        "a tool record's marker count cannot be negative");
            }
        }
    }

    private record SessionKey(String container, String path) {}

    /** The two sources collected in one look at the account's tasks. */
    record Sources(Map<String, String> histories, Map<String, Session> sessions) {}

    /** Collects histories and the declared marker's count in each newest record. */
    static String command(@Nullable ToolRecords records, @Nullable String marker) {
        if (records == null || marker == null) {
            return COMMAND;
        }
        return LIST
                + HISTORY
                + " podman exec \"$c\" sh -c "
                + Shell.quote(records.command(marker))
                + " || :; done; exit 0";
    }

    /** Looks at both the histories and the agents' tool-call records. */
    Optional<String> check(Map<String, String> histories, Map<String, Session> sessions) {
        final Optional<String> history = historyLoop(histories);
        if (history.isPresent() || marker == null) {
            return history;
        }
        for (final Map.Entry<String, Session> task : sessions.entrySet()) {
            final Session session = task.getValue();
            final SessionKey key = new SessionKey(task.getKey(), session.path());
            final long before = sessionBaseline.computeIfAbsent(key, ignored -> session.count());
            final long added = session.count() - before;
            if (added >= MARKER_REPEATS) {
                return Optional.of(
                        "In "
                                + task.getKey()
                                + " the tool failure \""
                                + marker
                                + "\" was recorded "
                                + added
                                + " times since this step first saw "
                                + session.path()
                                + ": the agent repeats a failing call.");
            }
        }
        return Optional.empty();
    }

    /**
     * Reads how often one line may scroll by from {@link #REPEATS}.
     *
     * @return The limit.
     */
    static int repeats() {
        final String set = System.getProperty(REPEATS);
        if (set == null || set.isBlank()) {
            return DEFAULT_REPEATS;
        }
        try {
            return Integer.parseInt(set.strip());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(
                    REPEATS + " is a number of lines, not '" + set + "'", ex);
        }
    }

    /** Forgets what was seen, so the next look is the beginning of a step. */
    void reset() {
        baseline = null;
        sessionBaseline.clear();
    }

    /**
     * Looks at the histories once.
     *
     * <p>The first look after {@link #reset()} is what the step began with, and finds nothing.
     *
     * @param histories Each task's history, by its container.
     * @return What repeated, where and how often, or empty while nothing does.
     */
    Optional<String> check(Map<String, String> histories) {
        return check(histories, Map.of());
    }

    private Optional<String> historyLoop(Map<String, String> histories) {
        final Map<String, Map<String, Integer>> now = new LinkedHashMap<>();
        histories.forEach((container, history) -> now.put(container, counts(history)));
        final Map<String, Map<String, Integer>> before = baseline;
        if (before == null) {
            baseline = now;
            return Optional.empty();
        }
        for (final Map.Entry<String, Map<String, Integer>> task : now.entrySet()) {
            // A container that appeared during the step began with nothing.
            final Map<String, Integer> was = before.getOrDefault(task.getKey(), Map.of());
            int failures = 0;
            String failure = "";
            for (final Map.Entry<String, Integer> line : task.getValue().entrySet()) {
                final int added = line.getValue() - was.getOrDefault(line.getKey(), 0);
                if (marker != null && line.getKey().contains(marker) && added > 0) {
                    failures += added;
                    failure = line.getKey();
                }
                if (added > repeats) {
                    return Optional.of(
                            "In "
                                    + task.getKey()
                                    + " one line scrolled by "
                                    + added
                                    + " times since this step began, more than the "
                                    + repeats
                                    + " that "
                                    + REPEATS
                                    + " allows: \""
                                    + line.getKey()
                                    + "\"");
                }
            }
            if (failures >= MARKER_REPEATS) {
                return Optional.of(
                        "In "
                                + task.getKey()
                                + " the tool failure \""
                                + marker
                                + "\" scrolled by "
                                + failures
                                + " times since this step began: the agent repeats a failing call. The last: \""
                                + failure
                                + "\"");
            }
        }
        return Optional.empty();
    }

    /**
     * Splits what {@link #COMMAND} printed into each container's history.
     *
     * @param printed Its output.
     * @return Each history, by its container.
     */
    static Map<String, String> histories(String printed) {
        return sources(printed).histories();
    }

    /** Splits the command's output into histories and tool-call records by container. */
    static Sources sources(String printed) {
        final Map<String, String> histories = new LinkedHashMap<>();
        final Map<String, Session> sessions = new LinkedHashMap<>();
        String container = null;
        boolean recordSeen = false;
        StringBuilder history = new StringBuilder();
        for (final String line : printed.split("\n")) {
            if (!line.isEmpty() && line.charAt(0) == SEPARATOR) {
                if (container != null) {
                    histories.put(container, history.toString());
                }
                container = line.substring(1).strip();
                recordSeen = false;
                history = new StringBuilder();
            } else if (container != null && !recordSeen && summary(line) != null) {
                // The record's summary comes last, after the history: what follows it is not
                // history.
                recordSeen = true;
                sessions.put(container, summary(line));
            } else if (container != null && !recordSeen) {
                history.append(line).append('\n');
            }
        }
        if (container != null) {
            histories.put(container, history.toString());
        }
        return new Sources(histories, sessions);
    }

    /**
     * Reads a record's summary, the separator, a path, a tab and a count, or {@code null} for any
     * other line: an agent can draw a line that starts like one, and it stays history.
     */
    private static @Nullable Session summary(String line) {
        if (line.isEmpty() || line.charAt(0) != SESSION_SEPARATOR) {
            return null;
        }
        final int tab = line.lastIndexOf('\t');
        if (tab <= 1) {
            return null;
        }
        try {
            final long count = Long.parseLong(line.substring(tab + 1).strip());
            return count < 0 ? null : new Session(line.substring(1, tab), count);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Map<String, Integer> counts(String history) {
        final Map<String, Integer> counts = new HashMap<>();
        for (final String raw : history.split("\n")) {
            final String line = raw.strip();
            if (line.codePoints().anyMatch(Character::isLetterOrDigit)) {
                counts.merge(line, 1, Integer::sum);
            }
        }
        return counts;
    }
}
