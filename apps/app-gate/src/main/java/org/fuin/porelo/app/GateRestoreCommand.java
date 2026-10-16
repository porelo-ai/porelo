package org.fuin.porelo.app;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.gate.GateException;
import org.fuin.porelo.gate.GitGate;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Restores a mirror from a bundle.
 * <p>
 * Refuses to write over an existing mirror rather than merging into it, because a restore that
 * silently discarded whatever the agent pushed since the backup would be worse than no restore.
 */
@Command(name = "restore",
        mixinStandardHelpOptions = true,
        description = "Restores the mirror from a bundle, refusing to overwrite an existing one.")
public class GateRestoreCommand implements Callable<Integer>, PoreloFactory.ContextAware {

    @Parameters(index = "0", paramLabel = "<file>", description = "Bundle to restore from.")
    private Path bundle;

    @Option(names = { "-p", "--project" }, paramLabel = "<name>", required = true,
            description = "Project name, as 'porelo project list' prints it.")
    private String projectName;

    @Option(names = { "-r", "--repository" }, paramLabel = "<name>",
            description = "Which of the project's repositories. Default: the project's own.")
    private @Nullable String repository;

    @Spec
    private CommandSpec spec;

    private PoreloContext context = PoreloContext.real();

    @Override
    public void setContext(PoreloContext context) {
        this.context = context;
    }

    @Override
    public Integer call() {

        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();

        try {
            final Project project = GateSupport.byName(context, projectName);
            final GitGate gate = GateSupport.gate(context, project,
                    GateSupport.repository(project, repository), null, null);
            final java.nio.file.Path mirror = gate.mirror();
            if (java.nio.file.Files.isDirectory(mirror.resolve("objects")) && moveAside(mirror, Offer.atTerminal()
                    ? new Offer(Offer.Asker.terminal(), false, false, err) : Offer.NOBODY)) {
                final java.nio.file.Path aside = aside(mirror, java.time.LocalDate.now());
                try {
                    java.nio.file.Files.move(mirror, aside);
                } catch (final java.io.IOException ex) {
                    err.println("porelo: cannot move " + mirror + " aside: " + ex.getMessage());
                    err.flush();
                    return 70;
                }
                out.println("aside     " + mirror + " is now " + aside + "; nothing in it was removed");
            }
            gate.restore(bundle);
            out.println("restored  " + gate.mirror() + " from " + bundle);
            out.println("pending   " + gate.pending().size() + " push(es) recovered");
            out.flush();
            return 0;
        } catch (GateException ex) {
            err.println("porelo: " + ex.getMessage());
            err.flush();
            return 70;
        }
    }

    /**
     * Asks whether to move a mirror that is there aside before restoring over its place. It removes nothing, so yes by
     * default.
     *
     * @param mirror The mirror.
     * @param offer Who is asked.
     * @return Whether to move it aside.
     */
    static boolean moveAside(final java.nio.file.Path mirror, final Offer offer) {
        return offer != Offer.NOBODY && offer.confirm("a mirror is already at " + mirror,
                "Move it aside under a dated name and restore?", "", false);
    }

    /**
     * Returns where a mirror goes aside: its name with the day, and a number where that is taken.
     *
     * @param mirror The mirror.
     * @param day Today.
     * @return A path nothing is at.
     */
    static java.nio.file.Path aside(final java.nio.file.Path mirror, final java.time.LocalDate day) {
        final String base = mirror.getFileName() + ".aside-" + day;
        java.nio.file.Path target = mirror.resolveSibling(base);
        for (int n = 2; java.nio.file.Files.exists(target); n++) {
            target = mirror.resolveSibling(base + "-" + n);
        }
        return target;
    }
}
