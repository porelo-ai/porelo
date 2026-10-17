package org.fuin.porelo.app;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stops every running task and every helper any of them started.
 *
 * <p>Split out of {@link PanicCommand} so the daemon serves the same operation rather than a second
 * one. The reason this matters more here than elsewhere: an interface that shelled out to {@code
 * porelo panic} would be running a command, and this is the one command somebody reaches for when
 * they do not know what is wrong - the worst possible moment for two implementations to differ
 * about what "stop everything" means.
 *
 * <p><strong>It stops; it never removes.</strong> The workspace, the logs and whatever never
 * reached the gate all survive, and {@code task attach} offers to bring a task back with the work
 * it had.
 */
public final class TaskPanic {

    /**
     * One task this stopped, or would have stopped.
     *
     * @param name Container name.
     * @param helpers How many helper processes it had.
     * @param surviving Helpers that refused to die, which somebody has to kill by hand.
     */
    public record Stopped(String name, long helpers, List<String> surviving) {

        /**
         * Returns this as plain values, for a caller that has to put it on a wire.
         *
         * @return The task.
         */
        public Map<String, Object> asMap() {
            final Map<String, Object> map = new LinkedHashMap<>();
            map.put("name", name);
            map.put("helpers", helpers);
            map.put("surviving", surviving);
            return map;
        }
    }

    /**
     * What a panic did, or would have done.
     *
     * @param tasks Every task that was running, in the order they were stopped.
     * @param surviving Every helper that outlived its stop, across all tasks.
     * @param previewed Whether this only listed what would happen.
     */
    public record Result(
            List<Stopped> tasks, List<String> surviving, boolean previewed, String journalled) {

        /**
         * @return How many tasks stopped with nothing left running.
         */
        public int clean() {
            return (int) tasks.stream().filter(task -> task.surviving().isEmpty()).count();
        }
    }

    private final PoreloContext context;

    /**
     * Constructor with the context to act on.
     *
     * @param context Where the runtime and the paths come from.
     */
    public TaskPanic(PoreloContext context) {
        this.context = context;
    }

    /**
     * Stops every running task, or reports what that would stop.
     *
     * @param preview Whether to list rather than stop.
     * @return What happened, or would have.
     */
    public Result panic(boolean preview) {
        return panic(preview, "cli");
    }

    /**
     * Stops every running task, or reports what that would stop, and journals a stop - even of
     * nothing.
     *
     * @param preview Whether to list rather than stop.
     * @param via What asked: {@code cli} or {@code daemon}.
     * @return What happened, or would have, and the journal's line; empty for a preview or when it
     *     could not be written.
     */
    public Result panic(boolean preview, String via) {

        final List<TaskInventory.Task> running =
                new TaskInventory(context)
                        .tasks().stream().filter(TaskInventory.Task::running).toList();

        if (preview) {
            return new Result(
                    running.stream()
                            .map(task -> new Stopped(task.name(), task.helpers(), List.of()))
                            .toList(),
                    List.of(),
                    true,
                    "");
        }

        final TaskControl control = new TaskControl(context);
        final List<Stopped> stopped = new ArrayList<>();
        final List<String> surviving = new ArrayList<>();

        for (final TaskInventory.Task task : running) {
            // Through the same operation 'task stop' uses, so a panic writes down what a task held
            // that never reached the gate, exactly as a deliberate stop does. A faster path that
            // skipped that would lose the one record of what was lost.
            final TaskControl.Stopped result = control.stop(task.name());
            stopped.add(new Stopped(task.name(), result.helpers(), result.surviving()));
            surviving.addAll(result.surviving());
        }
        return new Result(
                List.copyOf(stopped),
                List.copyOf(surviving),
                false,
                journal(via, stopped.stream().map(Stopped::name).toList(), surviving));
    }

    /**
     * Writes one line for a stop of everything. A failed write never holds a stop back: the tasks
     * are stopped already, and the line is answered empty.
     */
    private String journal(
            final String via, final List<String> stopped, final List<String> surviving) {
        String host;
        try {
            host =
                    java.nio.file.Files.readString(
                                    java.nio.file.Path.of("/proc/sys/kernel/hostname"))
                            .strip();
        } catch (final java.io.IOException | RuntimeException ex) {
            host = "unknown";
        }
        final String line =
                java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
                        + " user="
                        + System.getProperty("user.name")
                        + " host="
                        + host
                        + " via="
                        + via
                        + " stopped="
                        + (stopped.isEmpty() ? "none" : String.join(",", stopped))
                        + " surviving="
                        + (surviving.isEmpty() ? "none" : String.join(",", surviving));
        try {
            final java.nio.file.Path file = context.paths().stopJournal();
            java.nio.file.Files.createDirectories(file.getParent());
            java.nio.file.Files.writeString(
                    file,
                    line + "\n",
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
            return line;
        } catch (final java.io.IOException ex) {
            return "";
        }
    }
}
