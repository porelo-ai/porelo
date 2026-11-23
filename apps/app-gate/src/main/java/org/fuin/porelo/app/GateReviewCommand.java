package org.fuin.porelo.app;

import java.io.PrintWriter;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.gate.GateException;
import org.fuin.porelo.gate.GitGate;
import org.fuin.porelo.gate.ReviewRanking;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/** Shows what a pending push would change. */
@Command(
        name = "review",
        mixinStandardHelpOptions = true,
        description = "Shows what a pending push would change.")
public class GateReviewCommand implements Callable<Integer>, PoreloFactory.ContextAware {

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

    @Option(
            names = "--upstream",
            paramLabel = "<url>",
            description = "Upstream repository to forward approved pushes to.")
    private @Nullable String upstream;

    @Parameters(index = "0", paramLabel = "<name>", description = "Name of the pending push.")
    private String name;

    @Option(
            names = "--against",
            paramLabel = "<ref>",
            description = "Ref to compare against. Default: the branch the task started from.")
    private @Nullable String against;

    @Option(
            names = "--commit",
            paramLabel = "<id>",
            description = "Show this full commit id; refuse approval if it no longer waits.")
    private @Nullable String commit;

    @Option(
            names = "--ignore-line-endings",
            description =
                    "Ignore whitespace at line endings in the patch, as git's --ignore-space-at-eol."
                            + " The files, their counts and the log are unchanged.")
    private boolean ignoreLineEndings;

    @Spec private CommandSpec spec;

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
            final var selected = GateSupport.repository(context, project, repository, name);
            final GitGate gate = GateSupport.gate(context, project, selected, upstream, null);
            GateSupport.held(gate);
            // A mistyped name is said with what does wait, as every gate command says it.
            final GitGate.Waiting pending = gate.pendingWork(name);
            final GitGate.Waiting waiting =
                    commit == null ? pending : new GitGate.Waiting(commit.strip(), "");
            // What matters before what is merely large: the instruction, the files dangerous by
            // kind, and the patch in that same order, reformatting and generated files last. A
            // base named here that names nothing is refused, never taken as no base; an empty one
            // is the whole history, as a daemon's cut review hands it out.
            final String base =
                    against == null
                            ? gate.reviewBase(TaskBase.reviewAgainst(context, project, name, gate))
                            : against.isBlank() ? null : gate.namedBase(against);
            final ReviewRanking.Review review = gate.rankedReview(waiting, base, ignoreLineEndings);
            out.print(
                    ReviewText.render(
                            ReviewText.instruction(context.paths(), project.name(), name),
                            review.files()));
            out.println(shown(gate.log(waiting, base)));
            out.println(shown(review.patch()));
            out.println("reviewed  " + waiting.commit());
            out.println(
                    "approve   "
                            + GateApprovalHint.line(
                                    gate,
                                    pending.commit(),
                                    new GateApprovalHint(
                                            project.name(),
                                            selected.name(),
                                            name,
                                            waiting.commit(),
                                            upstream,
                                            null,
                                            false)));
            out.flush();
            return pending.commit().equals(waiting.commit()) ? 0 : 70;
        } catch (GateException ex) {
            err.println("porelo: " + TalkReadCommand.shown(String.valueOf(ex.getMessage())));
            err.flush();
            return 70;
        } catch (RuntimeException ex) {
            err.println("porelo: " + TalkReadCommand.shown(String.valueOf(ex.getMessage())));
            err.flush();
            return 2;
        }
    }

    /**
     * Returns the agent's text with every control character written out but the line breaks and
     * tabs a patch needs.
     *
     * <p>Printed raw, a subject or a patch line carrying {@code ESC[1A ESC[2K} erased the lines
     * before it - the warning to read first among them - and the person approved what they had not
     * seen.
     *
     * @param text What git printed of the agent's work.
     * @return The same, safe to print.
     */
    static String shown(final String text) {
        return TerminalText.lines(text);
    }

    /** The whole captured review, including an explicit empty base for the whole history. */
    static String fullView(
            final String project,
            final String repository,
            final String name,
            final String commit,
            final @Nullable String against) {
        return List.of(
                        "porelo",
                        "gate",
                        "review",
                        "--project",
                        project,
                        "--repository",
                        repository,
                        "--commit",
                        commit,
                        "--against",
                        against == null ? "" : against,
                        "--",
                        name)
                .stream()
                .map(GateApprovalHint::quoted)
                .collect(Collectors.joining(" "));
    }
}
