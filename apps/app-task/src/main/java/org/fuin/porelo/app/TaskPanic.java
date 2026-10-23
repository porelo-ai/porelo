package org.fuin.porelo.app;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stops every task not confirmed inactive and every helper any of them started.
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
     * @param surviving Containers and helpers that survived their stop.
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
     * @param tasks Every task whose identity was not confirmed inactive, in stop order.
     * @param surviving Every container and helper that outlived its stop, across all tasks.
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
     * Stops every task not confirmed inactive, or reports what that would stop.
     *
     * @param preview Whether to list rather than stop.
     * @return What happened, or would have.
     */
    public Result panic(boolean preview) {
        return panic(preview, "cli");
    }

    /**
     * Stops every task not confirmed inactive, or reports what that would stop, and journals a stop
     * - even of nothing.
     *
     * @param preview Whether to list rather than stop.
     * @param via What asked: {@code cli} or {@code daemon}.
     * @return What happened, or would have, and the journal's line; empty for a preview or when it
     *     could not be written.
     */
    public Result panic(boolean preview, String via) {

        final List<String> running = candidates();

        if (preview) {
            return new Result(
                    running.stream()
                            .map(
                                    task ->
                                            new Stopped(
                                                    task,
                                                    TaskLifecycle.running(
                                                                    context.paths()
                                                                            .tasks()
                                                                            .containerState(task))
                                                            .size(),
                                                    List.of()))
                            .toList(),
                    List.of(),
                    true,
                    "");
        }

        final TaskControl control = new TaskControl(context);
        final List<Stopped> stopped = new ArrayList<>();
        final List<String> surviving = new ArrayList<>();

        for (final String task : running) {
            // Through the same operation 'task stop' uses, so a panic writes down what a task held
            // that never reached the gate, exactly as a deliberate stop does. A faster path that
            // skipped that would lose the one record of what was lost.
            final TaskControl.Stopped result =
                    control.stop(task, org.fuin.porelo.runtime.Podman.ContainerState.UNCONFIRMED);
            stopped.add(new Stopped(task, result.helpers(), result.surviving()));
            surviving.addAll(result.surviving());
        }
        return new Result(
                List.copyOf(stopped),
                List.copyOf(surviving),
                false,
                journal(via, stopped.stream().map(Stopped::name).toList(), surviving));
    }

    List<String> candidates() {
        // Inventory rows follow current names and display state, so they can hide a task still
        // stopping.
        final java.util.Set<String> names = new java.util.TreeSet<>();
        for (final java.nio.file.Path root :
                List.of(
                        context.paths().xdg().runtime(),
                        context.paths().xdg().state().resolve("tasks"))) {
            if (java.nio.file.Files.notExists(root)) continue;
            try (java.util.stream.Stream<java.nio.file.Path> entries =
                    java.nio.file.Files.list(root)) {
                entries.filter(java.nio.file.Files::isDirectory)
                        .map(path -> path.getFileName().toString())
                        .filter(org.fuin.porelo.runtime.ContainerName::isTask)
                        .forEach(names::add);
            } catch (final java.io.IOException ex) {
                throw new java.io.UncheckedIOException("cannot list recorded tasks in " + root, ex);
            }
        }
        final Map<String, String> identities = new LinkedHashMap<>();
        for (final String name : names) {
            final String id = context.recordedId(name);
            if (id != null) identities.put(id, name);
        }
        return context.podman().containerStates().stream()
                .filter(
                        row ->
                                row.state()
                                        == org.fuin.porelo.runtime.Podman.ContainerState
                                                .UNCONFIRMED)
                .filter(row -> identities.containsKey(row.id()))
                .map(
                        row ->
                                java.util.Objects.requireNonNull(
                                        identities.get(row.id()), "matched recorded identity"))
                .toList();
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
