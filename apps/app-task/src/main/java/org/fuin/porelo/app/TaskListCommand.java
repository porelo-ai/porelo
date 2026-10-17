package org.fuin.porelo.app;

import java.io.PrintWriter;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * Lists the tasks on this machine.
 *
 * <p>The container runtime knows the containers; only Porelo knows which project and security class
 * each belongs to, which is what an operator with several tasks running actually needs to tell them
 * apart. Both come from the task's own state directory rather than from the container name, because
 * a project name may contain the separator the name is built with.
 */
@Command(
        name = "list",
        mixinStandardHelpOptions = true,
        description = "Lists the tasks on this machine.")
public class TaskListCommand implements Callable<Integer>, PoreloFactory.ContextAware {

    @Spec private CommandSpec spec;

    private PoreloContext context = PoreloContext.real();

    @Override
    public void setContext(PoreloContext context) {
        this.context = context;
    }

    @Override
    public Integer call() {
        return render(spec.commandLine().getOut());
    }

    /**
     * Renders the listing.
     *
     * <p>Separate from {@link #call()} so the bare command and the {@code list} verb produce the
     * same bytes rather than two renderings that drift.
     *
     * @param out Where to write.
     * @return Exit code.
     */
    Integer render(PrintWriter out) {

        // Asked of the inventory rather than assembled here: the daemon answers the same question
        // for the interface, and two implementations of it would drift.
        final List<TaskInventory.Task> tasks = new TaskInventory(context).tasks();

        // Named like a task and not listed: a container Porelo holds no id for. Said rather than
        // left out, because
        // "No tasks" over a stopped container that has a task's record lets its work be removed
        // unseen.
        final List<String> unlisted = new TaskInventory(context).unrecognized(tasks);

        if (tasks.isEmpty()) {
            out.println(unlisted.isEmpty() ? "No tasks." : "No tasks Porelo acts on.");
            unlistedNote(unlisted, out);
            out.flush();
            return 0;
        }

        // The age the state column had to drop. It is kept as an instant precisely so it can be
        // stated here rather than parsed back out of the runtime's own phrasing.
        final java.time.Instant now = java.time.Instant.now();
        out.printf(
                "%-38s %-14s %-9s %-18s %-5s %s%n",
                "NAME", "PROJECT", "CLASS", "STATE", "AGE", "HELPERS");
        for (final TaskInventory.Task task : tasks) {
            final String age = Age.compact(task.since(), now);
            out.printf(
                    "%-38s %-14s %-9s %-18s %-5s %s%n",
                    task.name(),
                    task.project() == null ? "-" : task.project(),
                    task.securityClass() == null ? "-" : task.securityClass(),
                    state(task.state()),
                    age.isEmpty() ? "-" : age,
                    task.helpers());
        }
        unlistedNote(unlisted, out);
        // Said once, under the table, rather than squeezed into a column: which tasks the machine
        // took down.
        final List<String> restarted =
                tasks.stream()
                        .filter(task -> TaskInventory.RESTARTED.equals(task.startDetail()))
                        .map(TaskInventory.Task::name)
                        .toList();
        if (!restarted.isEmpty()) {
            out.println();
            out.println("down because the machine restarted: " + String.join(", ", restarted));
            out.println(
                    "'porelo task start --restarted' brings them all back, each whole or not at all");
        }
        // Runs nobody watches are never brought back on their own, so they are not offered with
        // those above.
        final List<String> interrupted =
                tasks.stream()
                        .filter(task -> TaskInventory.INTERRUPTED.equals(task.startDetail()))
                        .map(TaskInventory.Task::name)
                        .toList();
        if (!interrupted.isEmpty()) {
            out.println();
            out.println(
                    "interrupted by the machine's restart, staying stopped: "
                            + String.join(", ", interrupted));
            out.println(
                    "each keeps its session, and its next 'porelo task start' with a prompt continues it");
        }
        out.flush();
        return 0;
    }

    /**
     * Returns the runtime's state, short enough to stay in its column.
     *
     * <p>The runtime writes a phrase, and its longest - {@code Exited (143) Less than a second ago}
     * - is twice the width of the column, so it ran into the helper count and the row stopped being
     * a table. The age is what gets dropped: this is a list of what exists and whether it is up,
     * and {@code task list} is how an operator finds the name to stop or resume.
     *
     * @param state What the runtime called it.
     * @return The state, at most as wide as its column.
     */
    private static String state(String state) {
        // 'Exited (143) Less than a second ago' and 'Exited (0) 2 minutes ago' both carry an age
        // this table has no room for, and the exit code is the part worth keeping: it is how a
        // task that was stopped is told apart from one that died.
        final int code = state.startsWith("Exited (") ? state.indexOf(')') : -1;
        final String shortened = code > 0 ? state.substring(0, code + 1) : state;
        return shortened.length() <= 18 ? shortened : shortened.substring(0, 17) + "…";
    }

    /**
     * Says which containers named like tasks are not listed, and why: Porelo recorded no container
     * id for them - made before it recorded one, or by something else - so no command of Porelo's
     * acts on them.
     *
     * @param unlisted Their names.
     * @param out Where to say it.
     */
    private static void unlistedNote(final List<String> unlisted, final PrintWriter out) {
        if (!unlisted.isEmpty()) {
            out.println(
                    "not listed: "
                            + String.join(", ", unlisted)
                            + " - named like tasks, but Porelo holds no"
                            + " container id for them (made before it recorded one, or by something else), so nothing of"
                            + " Porelo's acts on them; 'podman ps --all' shows them, and removing one removes what is in it");
        }
    }
}
