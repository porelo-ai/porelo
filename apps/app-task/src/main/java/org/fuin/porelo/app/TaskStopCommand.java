package org.fuin.porelo.app;

import java.io.PrintWriter;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Stops a task and everything it started.
 *
 * <p><strong>The container is stopped, not removed.</strong> Its filesystem is the task's
 * workspace, so removing it would throw away the agent's work and leave nothing to come back to.
 * Removal is a separate decision, and a separate command: {@code porelo task remove}.
 *
 * <p>Stopping the container is not enough on its own either: the credential proxy, the git gate and
 * the clearance watcher run on the host. The poststop hook reaps what a container that ran
 * recorded; anything it misses is stopped here, and every helper is checked afterwards rather than
 * assumed to be gone.
 */
@Command(
        name = "stop",
        mixinStandardHelpOptions = true,
        description = "Stops a task, its container and its helpers.")
public class TaskStopCommand implements Callable<Integer>, PoreloFactory.ContextAware, Suggests {

    @Override
    public java.util.List<String> candidates() {
        // Both, unlike attach: stopping a stopped task is answered "nothing to stop" rather than
        // refused, and hiding it as a candidate would make that answer unreachable.
        return TaskCandidates.all(context);
    }

    @Override
    public String candidateLabel() {
        return "tasks";
    }

    @Parameters(
            index = "0",
            arity = "0..1",
            paramLabel = "TASK",
            description =
                    "Container name, as shown by 'porelo task list'. Default: the task started from the checkout you stand in.")
    private @org.jspecify.annotations.Nullable String container;

    @picocli.CommandLine.Option(
            names = "--all",
            description =
                    "Stops every running task and its helpers, as 'porelo"
                            + " panic' does, and journals it.")
    private boolean all;

    @Spec private CommandSpec spec;

    private PoreloContext context = PoreloContext.real();

    @Override
    public void setContext(PoreloContext context) {
        this.context = context;
    }

    @Override
    public Integer call() {
        if (all) {
            if (container != null) {
                spec.commandLine().getErr().println("porelo: name a task or --all, not both");
                spec.commandLine().getErr().flush();
                return 2;
            }
            // The same operation as 'porelo panic', and the same words: one stop of everything,
            // however it is asked.
            final PanicCommand panic = new PanicCommand();
            panic.setContext(context);
            return new picocli.CommandLine(panic)
                    .setOut(spec.commandLine().getOut())
                    .setErr(spec.commandLine().getErr())
                    .execute();
        }
        final TaskTarget.Found found =
                TaskTarget.resolve(
                        context,
                        container,
                        java.nio.file.Path.of("."),
                        TaskTarget.console(spec.commandLine().getOut()));
        if (found.container() == null) {
            spec.commandLine().getErr().println("porelo: " + found.refusal());
            if ((Object) this instanceof Suggests suggests) {
                Suggests.offer(spec.commandLine().getErr(), suggests);
            }
            spec.commandLine().getErr().flush();
            return found.code();
        }
        container = found.container();

        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();

        // Decided by TaskControl, which the daemon calls too: a refusal that exists in one caller
        // and not the other is a task removed with work in it. This renders, and nothing else.
        final TaskControl.Stopped result = new TaskControl(context).stop(container);

        switch (result.outcome()) {
            case NOT_A_TASK -> {
                // Refused rather than guessed at: this stops processes, and a name Porelo did not
                // create belongs to somebody else.
                err.println("porelo: '" + container + "' is not a task Porelo created");
                Suggests.offer(err, this);
                err.flush();
                return 64;
            }
            case NOTHING_TO_STOP -> {
                out.println("No task " + container + "; nothing to stop.");
                out.flush();
                return 0;
            }
            default -> {
                // Stopped, which is everything below.
            }
        }

        out.println("stopped   " + container);
        out.println(
                "helpers   "
                        + (result.helpers()
                                - result.surviving().stream()
                                        .filter(alive -> !alive.startsWith("container "))
                                        .count())
                        + " of "
                        + result.helpers()
                        + " stopped");
        for (final String alive : result.surviving()) {
            err.println("porelo: still running: " + alive);
        }
        if (result.state() != null) {
            out.println("start     porelo task start, to bring it back with the work it has");
            out.println("logs      " + result.state());
        }
        out.flush();
        err.flush();
        return result.clean() ? 0 : 70;
    }
}
