package org.fuin.porelo.app;

import java.io.PrintWriter;
import java.util.concurrent.Callable;
import org.fuin.porelo.gate.review.Decision;
import org.fuin.porelo.gate.review.HoldJournal;
import org.fuin.porelo.gate.review.HoldRefused;
import org.fuin.porelo.gate.review.ReviewHolds;
import org.fuin.porelo.gate.review.Task;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/** Decides one held review item, if its content is still the one shown. */
@Command(
        name = "decide",
        mixinStandardHelpOptions = true,
        description =
                "Decides one held review item: release or refuse an outgoing one, pass or dismiss"
                        + " an incoming one, if its digest is still the one shown.")
public class GateDecideCommand implements Callable<Integer>, PoreloFactory.ContextAware {

    @Parameters(index = "0", paramLabel = "<task>", description = "The task's container name.")
    private String task;

    @Parameters(index = "1", paramLabel = "<id>", description = "The held item's id.")
    private String id;

    @Parameters(
            index = "2",
            paramLabel = "<decision>",
            description = "release, refuse, pass or dismiss.")
    private String decision;

    @Option(
            names = "--digest",
            paramLabel = "<digest>",
            required = true,
            description = "The digest 'porelo gate held' showed for the item.")
    private String digest;

    @Spec private CommandSpec spec;

    private PoreloContext context = PoreloContext.real();

    private @Nullable ReviewHolds holds;

    @Override
    public void setContext(final PoreloContext context) {
        this.context = context;
    }

    /**
     * Uses this store instead of the machine's.
     *
     * @param holds The held items.
     */
    void use(final ReviewHolds holds) {
        this.holds = holds;
    }

    @Override
    public Integer call() {
        final PrintWriter err = spec.commandLine().getErr();
        if (!task.matches(Task.NAME)) {
            err.println("porelo: the task given is no task's container name");
            err.flush();
            return 64;
        }
        final Decision decided;
        try {
            decided = HeldItemRows.decision(decision);
        } catch (IllegalArgumentException ex) {
            err.println("porelo: " + ex.getMessage());
            err.flush();
            return 64;
        }
        try {
            final ReviewHolds used =
                    holds == null ? new HoldJournal(context.paths().gate().reviewHolds()) : holds;
            used.decide(task, id, digest, decided);
            return 0;
        } catch (HoldRefused ex) {
            err.println("porelo: " + ex.reason().wireName() + ": " + TerminalText.line(ex.id()));
            err.flush();
            return 70;
        } catch (IllegalArgumentException ex) {
            // A decision for the other direction: the person named the wrong one.
            err.println("porelo: " + TerminalText.line(String.valueOf(ex.getMessage())));
            err.flush();
            return 64;
        } catch (RuntimeException ex) {
            err.println("porelo: " + TerminalText.line(String.valueOf(ex.getMessage())));
            err.flush();
            return 70;
        }
    }
}
