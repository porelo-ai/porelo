package org.fuin.porelo.app;

import java.io.PrintWriter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The task listing's text: what {@code porelo task list} prints once, and what its watching form
 * draws, line for line, so the two cannot come to show a task differently.
 *
 * <p>It takes the tasks it is given and asks nothing of the machine; where they came from - the
 * inventory in this process, or the daemon's {@code Watch} - is the caller's business.
 */
final class TaskListing {

    /** No instances. */
    private TaskListing() {}

    /**
     * One task's row, in the parts a watching view redraws apart.
     *
     * @param display The name a person gave it, or its short one.
     * @param project Its project, or {@code -}.
     * @param securityClass Its class, or {@code -}.
     * @param state What the runtime calls its state, short enough for its column.
     * @param age How long it has been in that state, or {@code -}.
     * @param helpers How many of its helpers are alive.
     */
    record Row(
            String display,
            String project,
            String securityClass,
            String state,
            String age,
            long helpers) {

        /**
         * Returns the row as the listing prints it.
         *
         * @return The row.
         */
        String text() {
            return before() + ageCell() + after();
        }

        /**
         * Returns everything of the row but its age, which is what a change is.
         *
         * @return The row without its age.
         */
        String settled() {
            return before() + after();
        }

        /**
         * Returns the column the age starts at, counted from zero.
         *
         * @return The column.
         */
        int ageColumn() {
            return before().length();
        }

        /**
         * Returns the age as its cell holds it, padded to the column's width.
         *
         * @return The cell.
         */
        String ageCell() {
            return String.format("%-5s", age);
        }

        private String before() {
            // The name a person gave it, or its short one; the project column tells two of one
            // name apart, and 'task status' shows the container.
            return String.format("%-38s %-14s %-9s %-18s ", display, project, securityClass, state);
        }

        private String after() {
            return " " + helpers;
        }
    }

    /**
     * Returns the table's header line.
     *
     * @return The header.
     */
    static String header() {
        return String.format(
                "%-38s %-14s %-9s %-18s %-5s %s",
                "NAME", "PROJECT", "CLASS", "STATE", "AGE", "HELPERS");
    }

    /**
     * Returns one task's row.
     *
     * @param task The task.
     * @param now The moment its age is measured against.
     * @return Its row.
     */
    static Row row(final TaskInventory.Task task, final Instant now) {
        // The age the state column had to drop. It is kept as an instant precisely so it can be
        // stated here rather than parsed back out of the runtime's own phrasing.
        final String age = Age.compact(task.since(), now);
        return new Row(
                task.display(),
                task.project() == null ? "-" : task.project(),
                task.securityClass() == null ? "-" : task.securityClass(),
                state(task.state()),
                age.isEmpty() ? "-" : age,
                task.helpers());
    }

    /**
     * Returns the line said instead of a table when there is no task.
     *
     * @param unrecognized The containers named like tasks that are not listed.
     * @return The line.
     */
    static String none(final List<String> unrecognized) {
        return unrecognized.isEmpty() ? "No tasks." : "No tasks Porelo acts on.";
    }

    /**
     * Returns what is said under the table: which containers are not listed, and which tasks the
     * machine's restart took down.
     *
     * @param tasks The listed tasks.
     * @param unrecognized The containers named like tasks that are not listed.
     * @return The lines, empty when there is nothing to say.
     */
    static List<String> notes(
            final List<TaskInventory.Task> tasks, final List<String> unrecognized) {
        final List<String> lines = new ArrayList<>();
        if (!unrecognized.isEmpty()) {
            // Named like a task and not listed: a container Porelo holds no id for. Said rather
            // than left out, because "No tasks" over a stopped container that has a task's record
            // lets its work be removed unseen.
            lines.add(
                    "not listed: "
                            + String.join(", ", unrecognized)
                            + " - named like tasks, but Porelo holds no"
                            + " container id for them (made before it recorded one, or by something else), so nothing of"
                            + " Porelo's acts on them; 'podman ps --all' shows them, and removing one removes what is in it");
        }
        // Said once, under the table, rather than squeezed into a column: which tasks the machine
        // took down.
        final List<String> restarted = named(tasks, TaskInventory.RESTARTED);
        if (!restarted.isEmpty()) {
            lines.add("");
            lines.add("down because the machine restarted: " + String.join(", ", restarted));
            lines.add(
                    "'porelo task start --restarted' brings them all back, each whole or not at all");
        }
        // Runs nobody watches are never brought back on their own, so they are not offered with
        // those above.
        final List<String> interrupted = named(tasks, TaskInventory.INTERRUPTED);
        if (!interrupted.isEmpty()) {
            lines.add("");
            lines.add(
                    "interrupted by the machine's restart, staying stopped: "
                            + String.join(", ", interrupted));
            lines.add(
                    "each keeps its session, and its next 'porelo task start' with a prompt continues it");
        }
        return lines;
    }

    /**
     * Prints the listing once.
     *
     * @param tasks The tasks.
     * @param unrecognized The containers named like tasks that are not listed.
     * @param now The moment ages are measured against.
     * @param out Where to print.
     */
    static void print(
            final List<TaskInventory.Task> tasks,
            final List<String> unrecognized,
            final Instant now,
            final PrintWriter out) {
        for (final String line : lines(tasks, unrecognized, now)) {
            out.println(line);
        }
        out.flush();
    }

    /**
     * Returns the listing's lines.
     *
     * @param tasks The tasks.
     * @param unrecognized The containers named like tasks that are not listed.
     * @param now The moment ages are measured against.
     * @return The lines, as {@link #print} prints them.
     */
    static List<String> lines(
            final List<TaskInventory.Task> tasks,
            final List<String> unrecognized,
            final Instant now) {
        final List<String> lines = new ArrayList<>();
        if (tasks.isEmpty()) {
            lines.add(none(unrecognized));
        } else {
            lines.add(header());
            for (final TaskInventory.Task task : tasks) {
                lines.add(row(task, now).text());
            }
        }
        lines.addAll(notes(tasks, unrecognized));
        return lines;
    }

    /**
     * Returns the tasks of one project, or all of them.
     *
     * @param tasks The tasks.
     * @param project The project, or {@code null} for every task.
     * @return The tasks the listing shows, in their order.
     */
    static List<TaskInventory.Task> of(
            final List<TaskInventory.Task> tasks, final @Nullable String project) {
        return project == null
                ? tasks
                : tasks.stream().filter(task -> project.equals(task.project())).toList();
    }

    private static List<String> named(final List<TaskInventory.Task> tasks, final String detail) {
        return tasks.stream()
                .filter(task -> detail.equals(task.startDetail()))
                .map(TaskInventory.Task::name)
                .toList();
    }

    /**
     * Returns the runtime's state, short enough to stay in its column.
     *
     * <p>The runtime writes a phrase, and its longest - {@code Exited (143) Less than a second ago}
     * - is twice the width of the column, so it ran into the helper count and the row stopped being
     * a table. The age is what gets dropped: this is a list of what exists and whether it is up,
     * and {@code task list} is how an operator finds the name to stop or resume.
     *
     * <p>{@code Up 4 minutes} keeps only its first word for the same reason: the age column says
     * how long, and a watching view, which is sent a list only when something other than an age
     * changed, would otherwise show {@code Up 3 seconds} for an hour.
     *
     * @param state What the runtime called it.
     * @return The state, at most as wide as its column.
     */
    static String state(final String state) {
        // 'Exited (143) Less than a second ago' and 'Exited (0) 2 minutes ago' both carry an age
        // this table has no room for, and the exit code is the part worth keeping: it is how a task
        // that was stopped is told apart from one that died. The daemon's Watch settles a state
        // the same way before it compares.
        final int code = state.startsWith("Exited (") ? state.indexOf(')') : -1;
        final String shortened =
                code > 0 ? state.substring(0, code + 1) : state.strip().split(" ", 2)[0];
        return shortened.length() <= 18 ? shortened : shortened.substring(0, 17) + "…";
    }
}
