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
 * Discards a pending push without forwarding it.
 */
@Command(name = "reject",
        mixinStandardHelpOptions = true,
        description = "Discards a pending push without forwarding it.")
public class GateRejectCommand implements Callable<Integer>, PoreloFactory.ContextAware {

    @Option(names = { "-p", "--project" }, paramLabel = "<name>", required = true,
            description = "Project name, as 'porelo project list' prints it.")
    private String projectName;

    @Option(names = { "-r", "--repository" }, paramLabel = "<name>",
            description = "Which of the project's repositories. Default: the one the task worked on.")
    private @Nullable String repository;

    @Option(names = "--upstream", paramLabel = "<url>",
            description = "Upstream repository to forward approved pushes to.")
    private @Nullable String upstream;

    @Parameters(index = "0", paramLabel = "<name>", description = "Name of the pending push.")
    private String name;

    @Option(names = "--reason", description = "Why, in your words, read from standard input - a process list is"
            + " world-readable. The task the work came from is told them in its inbox.")
    private boolean reason;

    @Option(names = "--force", description = "Discards a push the upstream already has, which somebody pushed there"
            + " by hand; without it such a push is refused, since discarding it would record the opposite of what"
            + " happened.")
    private boolean force;

    private PoreloContext context = PoreloContext.real();

    @Override
    public void setContext(final PoreloContext context) {
        this.context = context;
    }

    @Spec
    private CommandSpec spec;

    @Override
    public Integer call() {

        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();

        try {
            final Project project = GateSupport.byName(context, projectName);
            final GitGate gate = GateSupport.gate(context, project,
                    GateSupport.repository(context, project, repository, name), upstream, null);
            gate.initialize();
            final String said = reason ? new String(System.in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                    .strip() : null;
            final org.fuin.porelo.core.project.Repository chosen = GateSupport.repository(context, project, repository,
                    name);
            final boolean upstreamHas = GateSupport.alreadyUpstream(context, project, chosen).contains(name);
            if (upstreamHas && !force) {
                err.println("porelo: '" + name + "' is already on the upstream: somebody pushed it there by hand,"
                        + " past the gate, so discarding it would record the opposite of what happened. Nothing was"
                        + " discarded; 'porelo gate reject --force " + name + "' clears the ref anyway.");
                err.flush();
                return 1;
            }
            gate.reject(name, upstreamHas, force);
            out.println("discarded " + name);
            // Told whether or not a reason was given: an agent whose work silently vanished hands it over again.
            out.println(PersonNote.rejectedWork(context, project, name, said) ? "told      the task it came from"
                    : "not told  no task named '" + name + "' has a mailbox here any more");
            out.flush();
            return 0;
        } catch (java.io.IOException ex) {
            err.println("porelo: discarded " + name + ", and the task could not be told: " + ex.getMessage());
            err.flush();
            return 70;
        } catch (GateException ex) {
            err.println("porelo: " + ex.getMessage());
            err.flush();
            return 70;
        } catch (RuntimeException ex) {
            err.println("porelo: " + ex.getMessage());
            err.flush();
            return 2;
        }
    }
}
