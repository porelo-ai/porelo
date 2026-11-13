package org.fuin.porelo.app;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Reads back what a task's gate decided when a push arrived, so a review shows it before an
 * approval: {@code accepted} for a push judged by the task's certificate, {@code accepted,
 * unsigned} for one nothing could judge.
 */
public final class ArrivalVerdicts {

    private static final String ARRIVED = " arrived ";

    private ArrivalVerdicts() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Returns the verdict the account's event log holds for a waiting push.
     *
     * @param context The machine.
     * @param project The project.
     * @param task The task, as its incoming ref names it.
     * @param commit The commit waiting at the gate.
     * @return The verdict, or {@code null} when the log records no arrival of that commit or cannot
     *     be read.
     */
    public static @Nullable String of(
            final PoreloContext context,
            final String project,
            final String task,
            final String commit) {
        try {
            return of(EventLog.of(context).entries(null), project, task, commit);
        } catch (final IOException | RuntimeException ex) {
            // Absent, never guessed: a review without the line is a review without its verdict.
            return null;
        }
    }

    /**
     * Returns the verdict of the newest arrival of a commit among event log lines.
     *
     * @param lines The event log's lines, oldest first.
     * @param project The project.
     * @param task The task, as its incoming ref names it.
     * @param commit The commit waiting at the gate.
     * @return The verdict, or {@code null} when no line records that commit's arrival.
     */
    static @Nullable String of(
            final List<Map<String, Object>> lines,
            final String project,
            final String task,
            final String commit) {
        String verdict = null;
        for (final Map<String, Object> line : lines) {
            if (!EventLog.ARRIVED.equals(line.get("kind"))
                    || !project.equals(line.get("project"))
                    || !task.equals(line.get("task"))
                    || !(line.get("detail") instanceof String detail)) {
                continue;
            }
            // "<old>..<new> arrived <instant>: <verdict>", as ArrivalRecords writes it.
            final int arrived = detail.indexOf(ARRIVED);
            final int range = detail.indexOf("..");
            final int colon = arrived < 0 ? -1 : detail.indexOf(": ", arrived);
            if (range < 0 || arrived < range || colon < 0) {
                continue;
            }
            if (detail.substring(range + 2, arrived).equals(commit)) {
                verdict = detail.substring(colon + 2);
            }
        }
        return verdict;
    }
}
