package org.fuin.porelo.app;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Reads a task back from the row the daemon sends for it, the reverse of {@link
 * TaskInventory.Task#asMap()}, so a view fed by {@code Watch} renders the same record the listing
 * does.
 *
 * <p>What a row carries as a nested report of its own - how the agent's run ended, the files handed
 * in, the builds of its pushes, the file it handed back - is not read back: no listing shows it,
 * and the task carries the value of one nothing is known about instead. An empty string the row
 * sends for an absent value is read back as absent, as {@code asMap} writes it.
 */
final class TaskRows {

    /** No instances. */
    private TaskRows() {}

    /**
     * Reads the tasks of one {@code Watch} or {@code List} answer.
     *
     * @param rows The rows, as sent.
     * @return The tasks, in the same order.
     */
    static List<TaskInventory.Task> read(final List<?> rows) {
        return rows.stream()
                .filter(Map.class::isInstance)
                .map(row -> read((Map<?, ?>) row))
                .toList();
    }

    /**
     * Reads one task.
     *
     * @param row The row, as sent.
     * @return The task.
     */
    static TaskInventory.Task read(final Map<?, ?> row) {
        final String within = text(row, "task");
        return new TaskInventory.Task(
                text(row, "name"),
                absent(row, "project"),
                absent(row, "securityClass"),
                text(row, "state"),
                flag(row, "running"),
                number(row, "helpers"),
                absent(row, "agent"),
                absent(row, "mode"),
                absent(row, "prompt"),
                absent(row, "branch"),
                text(row, "since"),
                named(
                        TaskInventory.Activity.class,
                        text(row, "activity"),
                        TaskInventory.Activity.UNKNOWN),
                absent(row, "waitingFor"),
                absent(row, "clearance"),
                absent(row, "label"),
                (int) number(row, "waiting"),
                text(row, "startAction"),
                text(row, "startDetail"),
                absent(row, "repository"),
                absent(row, "commit"),
                new AgentWaiting.Derived(
                        named(
                                AgentWaiting.Screen.class,
                                text(row, "screen"),
                                AgentWaiting.Screen.UNSEEN),
                        text(row, "screenWaitingFor"),
                        flag(row, "screenUnproven"),
                        text(row, "lastMessage"),
                        named(
                                AgentWaiting.Asked.class,
                                text(row, "asked"),
                                AgentWaiting.Asked.CANNOT_SAY),
                        text(row, "askedFrom"),
                        text(row, "session")),
                texts(row.get("credentials")),
                nested(row.get("grants")),
                absent(row, "provider"),
                null,
                HandIns.Summary.none(),
                TaskBuilds.View.none(),
                nested(row.get("parameters")),
                text(row, "phase"),
                text(row, "lastWrote"),
                (int) number(row, "inboxWaiting"),
                absent(row, "forgeBranch"),
                flag(row, "certified"),
                text(row, "certifiedUntil"),
                text(row, "model"),
                text(row, "modelLabel"),
                text(row, "modelFrom"),
                absent(row, "named"),
                // The name within its project, as the sender worked it out: a client that cut it
                // from the container name would follow a rule that has changed before.
                within,
                ended(row.get("ended")),
                null);
    }

    private static TaskInventory.@Nullable Ended ended(final @Nullable Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return null;
        }
        final TaskInventory.EndOutcome outcome =
                known(TaskInventory.EndOutcome.class, text(map, "outcome"));
        return outcome == null
                ? null
                : new TaskInventory.Ended(outcome, text(map, "why"), text(map, "at"));
    }

    private static String text(final Map<?, ?> row, final String key) {
        final Object value = row.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static @Nullable String absent(final Map<?, ?> row, final String key) {
        final String value = text(row, key);
        return value.isEmpty() ? null : value;
    }

    private static boolean flag(final Map<?, ?> row, final String key) {
        return Boolean.TRUE.equals(row.get(key));
    }

    private static long number(final Map<?, ?> row, final String key) {
        return row.get(key) instanceof Number number ? number.longValue() : 0;
    }

    private static Map<String, String> texts(final @Nullable Object value) {
        final Map<String, String> read = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, entry) -> read.put(String.valueOf(key), String.valueOf(entry)));
        }
        return read;
    }

    private static Map<String, Map<String, String>> nested(final @Nullable Object value) {
        final Map<String, Map<String, String>> read = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, entry) -> read.put(String.valueOf(key), texts(entry)));
        }
        return read;
    }

    private static <E extends Enum<E>> E named(
            final Class<E> type, final String name, final E fallback) {
        final E known = known(type, name);
        // A later daemon may send a value this client does not know; it is shown as the one that
        // says nothing rather than refused.
        return known == null ? fallback : known;
    }

    private static <E extends Enum<E>> @Nullable E known(final Class<E> type, final String name) {
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
