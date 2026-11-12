package org.fuin.porelo.app;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.fuin.porelo.wire.varlink.TasksContract;
import org.fuin.porelo.wire.varlink.VarlinkClient;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * Lists the tasks on this machine, once or for as long as somebody watches.
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

    /** What the command answers when the daemon a watch needs does not: EX_UNAVAILABLE. */
    static final int NO_DAEMON = 69;

    /** How often a watching view at a terminal works its ages out again. */
    static final Duration AGES = Duration.ofMinutes(1);

    @Option(
            names = "--project",
            paramLabel = "<name>",
            description = "Lists only this project's tasks.")
    private @Nullable String project;

    @Option(
            names = "--watch",
            description = "Keeps the list on screen, as the daemon reports changes, until Ctrl-C.")
    private boolean watch;

    @Spec private CommandSpec spec;

    private PoreloContext context = PoreloContext.real();

    @Override
    public void setContext(PoreloContext context) {
        this.context = context;
    }

    @Override
    public Integer call() {
        final PrintWriter out = spec.commandLine().getOut();
        if (watch) {
            return watch(
                    context,
                    project,
                    context.input().outputTerminal(),
                    out,
                    spec.commandLine().getErr(),
                    Clock.systemUTC());
        }
        return render(out);
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
        return render(context, project, out);
    }

    /**
     * Renders the listing once, from this process's own inventory.
     *
     * @param context The machine.
     * @param project The one project whose tasks are listed, or {@code null} for every task.
     * @param out Where to write.
     * @return Exit code.
     */
    static int render(
            final PoreloContext context, final @Nullable String project, final PrintWriter out) {
        // Asked of the inventory rather than assembled here: the daemon answers the same question
        // for the interface, and two implementations of it would drift.
        final TaskInventory inventory = new TaskInventory(context);
        final List<TaskInventory.Task> tasks = inventory.tasks();
        // Named like a task and not listed: a container Porelo holds no id for. Said under every
        // listing, a project's too, since such a container has no project anything could read.
        final List<String> unlisted = inventory.unrecognized(tasks);
        TaskListing.print(TaskListing.of(tasks, project), unlisted, java.time.Instant.now(), out);
        return 0;
    }

    /**
     * Shows the listing and keeps it showing, from the daemon's {@code Watch}, until Ctrl-C.
     *
     * <p>What it shows is what the interface is sent, and it asks for nothing on its own: the list
     * arrives when something other than an age changed, the clearance questions from {@code
     * Prompts}, and the ages are worked out here once a minute. Without a daemon it says so, prints
     * the listing once and answers {@link #NO_DAEMON}.
     *
     * @param context The machine.
     * @param project The one project whose tasks are shown, or {@code null} for every task.
     * @param terminal Whether the output is a terminal, drawn over in place; otherwise each change
     *     is a line.
     * @param out Where to show it.
     * @param err Where to say what could not be done.
     * @param clock What "now" is.
     * @return 0 when stopped or when the daemon ended the stream; {@link #NO_DAEMON} when it could
     *     not be watched.
     */
    static int watch(
            final PoreloContext context,
            final @Nullable String project,
            final boolean terminal,
            final PrintWriter out,
            final PrintWriter err,
            final Clock clock) {
        final Path socket = context.paths().daemonSocket();
        final VarlinkClient tasks;
        try {
            tasks = new VarlinkClient(socket);
        } catch (VarlinkException ex) {
            return once(context, project, out, err, "porelod does not answer at " + socket);
        }
        final TaskWatch view =
                new TaskWatch(
                        terminal
                                ? new TaskWatch.Screen(new TaskWatch.Ansi(out))
                                : new TaskWatch.Log(out),
                        project);
        // Ctrl-C ends the process from a signal, where no finally runs: the terminal is given back
        // there too, and stopping is not a failure.
        final Thread reset =
                new Thread(
                        () -> {
                            view.close();
                            Runtime.getRuntime().halt(0);
                        },
                        "porelo-watch-reset");
        Runtime.getRuntime().addShutdownHook(reset);
        final AtomicReference<@Nullable VarlinkClient> prompts = new AtomicReference<>();
        final AtomicBoolean ended = new AtomicBoolean();
        final Thread questions =
                Thread.ofPlatform()
                        .daemon()
                        .name("porelo-watch-prompts")
                        .start(() -> followPrompts(socket, view, prompts, ended, clock));
        final Thread ages =
                Thread.ofPlatform()
                        .daemon()
                        .name("porelo-watch-ages")
                        .start(
                                () -> {
                                    try {
                                        while (!ended.get()) {
                                            Thread.sleep(AGES);
                                            view.tick(clock.instant());
                                        }
                                    } catch (InterruptedException ex) {
                                        Thread.currentThread().interrupt();
                                    }
                                });
        final boolean[] arrived = {false};
        try (tasks) {
            tasks.callMore(
                    TasksContract.INTERFACE + ".Watch",
                    Map.of(),
                    reply -> {
                        arrived[0] = true;
                        view.listed(
                                TaskRows.read(list(reply.get("tasks"))),
                                list(reply.get("unrecognized")).stream()
                                        .map(String::valueOf)
                                        .toList(),
                                clock.instant());
                        return true;
                    });
            return 0;
        } catch (VarlinkException | java.io.IOException ex) {
            view.close();
            if (!arrived[0]) {
                return once(
                        context,
                        project,
                        out,
                        err,
                        "porelod at "
                                + socket
                                + " does not send the task list: "
                                + ex.getMessage());
            }
            err.println(
                    "porelo: porelod stopped sending the task list ("
                            + ex.getMessage()
                            + "); what was shown is no longer current");
            err.flush();
            return NO_DAEMON;
        } finally {
            ended.set(true);
            view.close();
            ages.interrupt();
            questions.interrupt();
            closeQuietly(prompts.get());
            try {
                Runtime.getRuntime().removeShutdownHook(reset);
            } catch (IllegalStateException ex) {
                // Already shutting down, where the hook gives the terminal back itself.
            }
        }
    }

    /** Follows the open clearance questions until the view ends; a failure is said on the view. */
    private static void followPrompts(
            final Path socket,
            final TaskWatch view,
            final AtomicReference<@Nullable VarlinkClient> held,
            final AtomicBoolean ended,
            final Clock clock) {
        try (VarlinkClient client = new VarlinkClient(socket)) {
            held.set(client);
            client.callMore(
                    TasksContract.INTERFACE + ".Prompts",
                    Map.of(),
                    event -> {
                        view.prompted(event, clock.instant());
                        return !ended.get();
                    });
        } catch (VarlinkException | java.io.IOException ex) {
            if (!ended.get()) {
                view.unfollowed(String.valueOf(ex.getMessage()), clock.instant());
            }
        }
    }

    /** Says what could not be watched, and lists once instead. */
    private static int once(
            final PoreloContext context,
            final @Nullable String project,
            final PrintWriter out,
            final PrintWriter err,
            final String why) {
        err.println(
                "porelo: "
                        + why
                        + ", so the list cannot be watched; this is the list once."
                        + " Start the daemon with 'systemctl --user start porelod' to watch it");
        err.flush();
        render(context, project, out);
        return NO_DAEMON;
    }

    private static List<?> list(final @Nullable Object value) {
        return value instanceof List<?> items ? items : List.of();
    }

    private static void closeQuietly(final @Nullable VarlinkClient client) {
        if (client != null) {
            try {
                client.close();
            } catch (java.io.IOException | RuntimeException ex) {
                // Only unblocks the reader; there is nothing left to tell anybody.
            }
        }
    }
}
