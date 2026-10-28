package org.fuin.porelo.app;

import java.io.PrintWriter;
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

/** Forwards a reviewed push to the upstream. */
@Command(
        name = "approve",
        mixinStandardHelpOptions = true,
        description = "Forwards a reviewed push to the upstream.")
public class GateApproveCommand implements Callable<Integer>, PoreloFactory.ContextAware {

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
            names = "--branch",
            paramLabel = "<name>",
            description =
                    "Upstream branch. Default: main; in a project with a branch block, the task's own"
                            + " branch, and a named one must match its branch.pattern.")
    private @Nullable String branch;

    /** The branch this run approves onto, once settled. */
    private String onto = "main";

    @Option(
            names = "--commit",
            paramLabel = "<id>",
            description =
                    "The commit you reviewed, as 'gate pending' or 'gate review' shows it. Refused when the push"
                            + " has moved since, so what goes is what you read.")
    private @org.jspecify.annotations.Nullable String commit;

    @Option(
            names = "--signed",
            description =
                    "Merge the work onto the branch as a commit signed with your own git's signing key, and"
                            + " push that - for what the project's configuration is made of, which every machine accepts"
                            + " only signed.")
    private boolean signed;

    @Option(
            names = "--no-input",
            description = "Ask nothing: a branch that already holds work is refused, as before.")
    private boolean noInput;

    /**
     * Who is asked to take the next free branch: this process's terminal, or nobody without one.
     */
    Offer.Asker asker = Offer.atTerminal() ? Offer.Asker.terminal() : Offer.Asker.NOBODY;

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
        final Offer offer =
                noInput || asker == Offer.Asker.NOBODY
                        ? Offer.NOBODY
                        : new Offer(asker, false, false, err);
        // Each branch taken is offered the next free one once, so a run of taken names ends rather
        // than loops.
        try {
            final Project project = GateSupport.byName(context, projectName);
            final String chosen =
                    ApprovalBranch.of(
                            context,
                            project,
                            GateSupport.repository(context, project, repository, name),
                            name,
                            branch);
            onto = chosen == null ? "main" : chosen;
        } catch (RuntimeException ex) {
            err.println("porelo: " + ex.getMessage());
            err.flush();
            return 64;
        }
        for (int tries = 0; tries < 10; tries++) {
            final String next = approveOnce(out, err, offer);
            if (next == null) {
                return exit;
            }
            onto = next;
        }
        return exit;
    }

    /** The reviewed commit, as the event log names it. */
    private String reviewed() {
        return commit == null ? "" : ", reviewed at " + commit;
    }

    /** The exit code of the last try. */
    private int exit;

    /**
     * Approves onto {@link #onto} once.
     *
     * @param out Where to report.
     * @param err Where to refuse.
     * @param offer Who is asked to take the next free branch.
     * @return The branch to try next when the person took it, or {@code null} when this try is the
     *     last.
     */
    private @org.jspecify.annotations.Nullable String approveOnce(
            final PrintWriter out, final PrintWriter err, final Offer offer) {
        try {
            final Project project = GateSupport.byName(context, projectName);
            final GitGate gate =
                    GateSupport.gate(
                            context,
                            project,
                            GateSupport.repository(context, project, repository, name),
                            upstream,
                            null);
            gate.initialize();
            if (signed) {
                GateSupport.approveSigned(gate, name, onto, commit);
                out.println("merged    " + name + " onto " + onto + ", signed, and forwarded");
                EventLog.record(
                        context,
                        EventLog.APPROVED,
                        projectName,
                        name,
                        "signed and forwarded onto " + onto + reviewed());
            } else {
                gate.approve(name, onto, commit);
                out.println("forwarded " + name + " to " + onto);
                EventLog.record(
                        context,
                        EventLog.APPROVED,
                        projectName,
                        name,
                        "forwarded onto " + onto + reviewed());
            }
            out.flush();
            exit = 0;
            return null;
        } catch (GateException.BranchExists ex) {
            // The next free branch is offered at a terminal; the words the contract names, and that
            // branch, otherwise. Never a force over the one that is taken.
            final String next = suggested(ex.branch());
            // Offered only where the forge's rules cover it.
            final boolean offerable = offerable(next);
            final String taken =
                    offerable ? instead(ex.branch(), String.valueOf(ex.at()), next, offer) : null;
            if (taken != null) {
                return taken;
            }
            err.println(
                    "porelo: "
                            + ex.branch()
                            + " already holds "
                            + ex.at()
                            + ", from earlier work; approve onto"
                            + " another branch with --branch"
                            + (offerable ? " " + next : " the project's branch.pattern accepts"));
            err.flush();
            exit = 70;
            return null;
        } catch (GateException ex) {
            err.println("porelo: " + ex.getMessage());
            err.flush();
            exit = 70;
            return null;
        } catch (RuntimeException ex) {
            err.println("porelo: " + ex.getMessage());
            err.flush();
            exit = 2;
            return null;
        }
    }

    /**
     * Asks whether to approve onto the next free branch instead of one that already holds earlier
     * work.
     *
     * @param branch The branch taken.
     * @param at What it holds.
     * @param next The next free branch.
     * @param offer Who is asked.
     * @return {@code next} on a yes, {@code null} when nobody took it.
     */
    static @org.jspecify.annotations.Nullable String instead(
            final String branch, final String at, final String next, final Offer offer) {
        return offer != Offer.NOBODY
                        && offer.confirm(
                                branch + " already holds " + at + ", from earlier work",
                                "Approve onto " + next + " instead?",
                                "",
                                false)
                ? next
                : null;
    }

    /**
     * Returns the next free branch after one that holds earlier work: {@code <branch>-2}, then
     * {@code -3}, as the upstream lists them, or {@code -2} when it cannot be asked.
     *
     * @param taken The branch that holds earlier work.
     * @return A suggestion only; the person chooses.
     */
    private boolean offerable(final String next) {
        try {
            final Project project = GateSupport.byName(context, projectName);
            return ApprovalBranch.offerable(
                    project, GateSupport.repository(context, project, repository, name), next);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private String suggested(final String taken) {
        try {
            final Project project = GateSupport.byName(context, projectName);
            final GitGate gate =
                    GateSupport.gate(
                            context,
                            project,
                            GateSupport.repository(context, project, repository, name),
                            upstream,
                            null);
            return gate.nextFreeBranch(taken);
        } catch (RuntimeException ex) {
            return taken + "-2";
        }
    }
}
