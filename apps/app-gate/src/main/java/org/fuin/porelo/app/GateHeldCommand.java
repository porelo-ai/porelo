package org.fuin.porelo.app;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.fuin.porelo.gate.review.HoldJournal;
import org.fuin.porelo.gate.review.ReviewHolds;
import org.fuin.porelo.gate.review.Task;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/** Lists the review items held for the owner account's decision. */
@Command(
        name = "held",
        mixinStandardHelpOptions = true,
        description =
                "Lists the review items held for your decision, both ways, oldest first: each with"
                        + " its destination, operation, pinned commit, digest and exact text.")
public class GateHeldCommand implements Callable<Integer>, PoreloFactory.ContextAware {

    @Parameters(
            index = "0",
            arity = "0..1",
            paramLabel = "<task>",
            description = "The task's container name. Default: every task of this machine.")
    private @Nullable String task;

    @Option(names = "--json", description = "Prints the items as a JSON array of HeldItem.")
    private boolean json;

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
        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();
        final String named = task;
        if (named != null && !named.matches(Task.NAME)) {
            err.println("porelo: the task given is no task's container name");
            err.flush();
            return 64;
        }
        try {
            final ReviewHolds used =
                    holds == null ? new HoldJournal(context.paths().gate().reviewHolds()) : holds;
            final ReviewHolds.Undecided found =
                    named == null
                            ? used.everyUndecided()
                            : new ReviewHolds.Undecided(used.undecided(named), List.of());
            final List<org.fuin.porelo.gate.review.Held> undecided = found.items();
            for (final String entry : found.unreadable()) {
                err.println(
                        "porelo: the held items of "
                                + TerminalText.line(entry)
                                + " cannot be read; porelo doctor names it too");
            }
            err.flush();
            // Nothing listed and something not read is no answer at all; anything listed is one.
            if (undecided.isEmpty() && !found.unreadable().isEmpty()) {
                return 70;
            }
            if (json) {
                final List<Map<String, Object>> rows = new ArrayList<>();
                undecided.forEach(held -> rows.add(HeldItemRows.row(held)));
                out.println(Json.write(rows));
            } else {
                out.print(HeldItemRows.shown(undecided));
            }
            out.flush();
            return 0;
        } catch (RuntimeException ex) {
            err.println("porelo: " + TerminalText.line(String.valueOf(ex.getMessage())));
            err.flush();
            return 70;
        }
    }
}
