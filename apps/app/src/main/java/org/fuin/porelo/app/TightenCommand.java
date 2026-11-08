package org.fuin.porelo.app;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * Takes from what Porelo left behind everything anyone but this account had of it, and says what it
 * changed.
 *
 * <p>Its own command because {@code porelo doctor} diagnoses and never repairs. A person runs this
 * knowing what it does; nothing runs it for them.
 */
@Command(
        name = "tighten",
        mixinStandardHelpOptions = true,
        description =
                "Makes what Porelo left behind readable by this account alone, and says what it"
                        + " changed. What a task reads through a mount stays as it is.")
public class TightenCommand implements Callable<Integer>, PoreloFactory.ContextAware {

    @Spec private CommandSpec spec;

    private PoreloContext context = PoreloContext.real();

    @Override
    public void setContext(final PoreloContext context) {
        this.context = context;
    }

    @Override
    public Integer call() {
        return tighten(
                new LooseFiles(context.paths().xdg()),
                spec.commandLine().getOut(),
                spec.commandLine().getErr());
    }

    /**
     * Does it.
     *
     * @param looseFiles Where to look.
     * @param out Where what changed is said.
     * @param err Where what could not be is said.
     * @return 0 when nothing is left readable by anyone else, else 1.
     */
    static int tighten(final LooseFiles looseFiles, final PrintWriter out, final PrintWriter err) {
        final Tightening.Outcome outcome;
        try {
            outcome = Tightening.of(looseFiles, false);
        } catch (final IOException ex) {
            err.println("porelo tighten: cannot look through it: " + ex.getMessage());
            return 1;
        }
        if (outcome.found() == 0) {
            out.println("nothing to tighten: all of it is this account's alone");
            return 0;
        }
        // One gone since it was found leaves nothing to read, so it counts as tightened.
        final List<Path> left =
                outcome.left().stream()
                        .filter(path -> path.reason() != Tightening.Reason.GONE)
                        .map(Tightening.Left::path)
                        .toList();
        out.println(
                "tightened "
                        + (outcome.found() - left.size())
                        + " of "
                        + outcome.found()
                        + ", at or below:");
        outcome.topmost().forEach(path -> out.println("  " + path));
        left.forEach(path -> err.println("could not tighten " + path));
        return left.isEmpty() ? 0 : 1;
    }
}
