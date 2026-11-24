package org.fuin.porelo.app;

import java.io.PrintWriter;
import java.util.Locale;
import java.util.concurrent.Callable;
import org.fuin.porelo.gate.review.Push;
import org.fuin.porelo.gate.review.ReviewRefused;
import org.fuin.porelo.gate.review.ReviewThread;
import org.fuin.porelo.gate.review.ThreadState;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/** Marks a comment thread of a waiting push resolved, or open again. */
@Command(
        name = "resolve",
        mixinStandardHelpOptions = true,
        description =
                "Marks a comment thread of a waiting push resolved, or open again. Approves"
                        + " nothing.")
public class GateResolveCommand implements Callable<Integer>, PoreloFactory.ContextAware {

    @Option(
            names = {"-p", "--project"},
            paramLabel = "<name>",
            required = true,
            description = "Project name, as 'porelo project list' prints it.")
    private String projectName;

    @Option(
            names = {"-r", "--repository"},
            paramLabel = "<name>",
            description =
                    "Which of the project's repositories. Default: the one the task worked on.")
    private @Nullable String repository;

    @Parameters(index = "0", paramLabel = "<name>", description = "Name of the pending push.")
    private String name;

    @Parameters(index = "1", paramLabel = "<thread>", description = "The thread's id.")
    private String thread;

    @Option(names = "--reopen", description = "Opens the thread again instead.")
    private boolean reopen;

    @Spec private CommandSpec spec;

    private PoreloContext context = PoreloContext.real();

    private CommentIntake.@Nullable Parts parts;

    @Override
    public void setContext(final PoreloContext context) {
        this.context = context;
    }

    /**
     * Uses these parts instead of the machine's.
     *
     * @param parts The pushes, check, store and writer.
     */
    void use(final CommentIntake.Parts parts) {
        this.parts = parts;
    }

    @Override
    public Integer call() {
        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();
        final CommentIntake.Parts used =
                parts == null ? CommentIntake.Parts.unwired(context) : parts;
        try {
            final Push push = used.pushes().waiting(projectName, repository, name);
            // Only the thread's state: no finding is cleared and no approval is given or taken.
            final ReviewThread now =
                    used.store()
                            .state(push, thread, reopen ? ThreadState.OPEN : ThreadState.RESOLVED);
            out.println("thread " + now.id() + "  " + now.state().name().toLowerCase(Locale.ROOT));
            out.flush();
            return 0;
        } catch (ReviewRefused ex) {
            err.println("porelo: " + GateCommentCommand.refused(ex.refusal()));
            err.flush();
            return 70;
        } catch (RuntimeException ex) {
            err.println("porelo: " + TerminalText.line(String.valueOf(ex.getMessage())));
            err.flush();
            return 70;
        }
    }
}
