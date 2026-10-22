package org.fuin.porelo.app;

import java.io.PrintWriter;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Stops taking a project's configuration from its repository, and removes the project.
 *
 * <p><strong>It deletes, and that is why it asks first.</strong> A mirror may hold pushes nobody
 * has reviewed and tasks may hold work; removing those silently would destroy exactly what the gate
 * exists to protect, by a command whose name sounds like unsubscribing from something. So the
 * refusal and the {@code --force} path are the ones {@code projects delete} already uses, through
 * the same code - a second implementation of "is there work in the way" is a second chance to get
 * it wrong.
 */
@Command(
        name = "unfollow",
        mixinStandardHelpOptions = true,
        description = "Stops following a project's repository, and removes the project.")
public class ProjectUnfollowCommand
        implements Callable<Integer>, PoreloFactory.ContextAware, Suggests {

    @Override
    public java.util.List<String> candidates() {
        // Every project the machine LISTS, not only the followed ones - the same list the refusal
        // prints, which is the rule completion follows everywhere here. A project that predates
        // following is exactly the one somebody cannot remember the spelling of.
        return new ProjectInventory(context)
                .projects().stream().map(ProjectInventory.Summary::name).toList();
    }

    @Override
    public String candidateLabel() {
        return "projects";
    }

    @Parameters(index = "0", paramLabel = "<name>", description = "The project.")
    private String name;

    @Option(names = "--dry-run", description = "Says what would go, and removes nothing.")
    private boolean dryRun;

    @Option(
            names = "--no-input",
            description = "Ask nothing: running tasks and unreviewed work refuse, as before.")
    private boolean noInput;

    /**
     * Who is asked to stop running tasks or destroy unreviewed work: this process's terminal, or
     * nobody without one.
     */
    Offer.Asker asker = Offer.atTerminal() ? Offer.Asker.terminal() : Offer.Asker.NOBODY;

    @Option(
            names = "--force",
            description = "Removes it although work is waiting for review. That work is destroyed.")
    private boolean force;

    @Spec private CommandSpec spec;

    private PoreloContext context = PoreloContext.real();

    @Override
    public void setContext(final PoreloContext context) {
        this.context = context;
    }

    @Override
    public Integer call() throws Exception {
        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();
        final FollowedProjects projects =
                new FollowedProjects(context.paths().projects().followed());
        final FollowedProjects.Followed followed = projects.find(name);
        // Read before anything goes: which of this machine's keys the forge holds for it is said at
        // the end.
        org.fuin.porelo.core.project.Project project = null;
        try {
            project = GateSupport.byName(context, name);
        } catch (RuntimeException ex) {
            // A project whose file cannot be read has no keys this machine can name.
        }

        // Asked for every project this machine LISTS, not only for the ones it follows. A project
        // that came to be the old way - a task was started with a file - is in no follow record,
        // and this used to refuse it outright: the project stayed in the listing, kept its mirror
        // and its image, and no command ended it. Two of them were cleared by hand on a test
        // machine, which is not a thing an interface can do. What is at risk is the same either
        // way, so the checks are the same either way.
        //
        // Refusals are checked before following stops; the record goes before the clone.
        ProjectDeletion.Result deleted = new ProjectDeletion(context).unfollow(name, dryRun, force);
        // What a refusal would tell a person to do first is offered instead, and the removal tried
        // again after it: at most once per kind of refusal, so a remedy that did not help ends in
        // that refusal rather than a loop.
        final Offer offer =
                dryRun || noInput || asker == Offer.Asker.NOBODY
                        ? Offer.NOBODY
                        : new Offer(asker, false, false, err);
        final java.util.Set<ProjectDeletion.Outcome> offered =
                java.util.EnumSet.noneOf(ProjectDeletion.Outcome.class);
        String next;
        while (offered.add(deleted.outcome())
                && (next =
                                nextTry(
                                        deleted.outcome(),
                                        deleted.outcome() == ProjectDeletion.Outcome.HOLDS_WORK
                                                ? deleted.unreviewed()
                                                : deleted.running(),
                                        offer))
                        != null) {
            if (next.equals("stop")) {
                for (final String task : deleted.running()) {
                    final TaskControl.Stopped result = new TaskControl(context).stop(task);
                    for (final String alive : result.surviving()) {
                        err.println("porelo: still running: " + alive);
                    }
                    err.flush();
                    if (!result.clean()) return 70;
                }
                deleted = new ProjectDeletion(context).unfollow(name, false, force);
            } else {
                deleted = new ProjectDeletion(context).unfollow(name, false, true);
            }
        }

        if (followed == null && deleted.outcome() == ProjectDeletion.Outcome.NO_SUCH_PROJECT) {
            if (force) {
                // With --force this is the one command a cleanup trap can run blind: a trap fires
                // after a failure, which is exactly when nobody knows how far the setup got.
                // Removing nothing is a success, not an error - anything else makes a script guard
                // a command whose whole purpose is to be the guard.
                out.println("nothing of " + name + " is here");
                out.flush();
                return 0;
            }
            // A name this machine does not list at all. Kept distinct from "it is here but you do
            // not follow it", because that distinction is the whole value of the answer.
            final java.util.List<String> known = ProjectSource.names(context);
            err.println(
                    "porelo: no project called '"
                            + name
                            + "' here."
                            + (known.isEmpty()
                                    ? ""
                                    : " This machine has: " + String.join(", ", known)));
            err.flush();
            return 70;
        }
        switch (deleted.outcome()) {
            case HOLDS_WORK -> {
                err.println("porelo: " + name + " holds work nobody has reviewed:");
                deleted.unreviewed().forEach(ref -> err.println("    " + ref));
                err.println(
                        "       review it, or 'porelo projects unfollow "
                                + name
                                + " --force' to destroy it");
                err.flush();
                return 70;
            }
            case TASKS_RUNNING -> {
                err.println("porelo: " + name + " still has tasks up:");
                deleted.running().forEach(task -> err.println("    " + task));
                err.println("       stop them first");
                err.flush();
                return 70;
            }
            case NO_SUCH_PROJECT -> {
                // Followed but never applied: there is no project to delete, and forgetting the
                // record is the whole of it.
                out.println("nothing of " + name + " was ever applied here");
            }
            case PREVIEWED -> {
                out.println(
                        followed == null
                                ? "would remove " + name + ", which this account does not follow"
                                : "would remove " + name + " and stop following " + followed.url());
                // The kind and the thing, not the record's toString: a preview that reads
                // "Removal[kind=FOLLOWED_CLONE, what=/home/...]" is for a debugger, and this is the
                // screen somebody decides on.
                deleted.removes()
                        .forEach(
                                removal ->
                                        out.printf(
                                                "    %-16s %s%n",
                                                removal.kind()
                                                        .toLowerCase(java.util.Locale.ROOT)
                                                        .replace('_', ' '),
                                                removal.what()));
                // What survives, said as plainly as what goes. Somebody deciding whether to confirm
                // is asking both questions at once, and the one nobody answers is the one they
                // assume the worst about.
                deleted.keeps().forEach(kept -> out.println("    kept: " + kept));
                out.flush();
                return 0;
            }
            case DELETED ->
                    deleted.removes()
                            .forEach(
                                    removal ->
                                            out.printf(
                                                    "removed  %-16s %s%n",
                                                    removal.kind()
                                                            .toLowerCase(java.util.Locale.ROOT)
                                                            .replace('_', ' '),
                                                    removal.what()));
            default -> {
                err.println("porelo: " + deleted.detail());
                err.flush();
                return 70;
            }
        }

        if (followed == null) {
            out.println(name + " was not followed here; what Porelo built for it is gone");
            out.flush();
            return 0;
        }
        out.println("no longer following " + followed.url());
        if (project != null) {
            // Its deploy keys go from the vault; the forge still grants access by them until they
            // go there too.
            for (final LeftDeployKeys.Key key : LeftDeployKeys.forget(context, project)) {
                out.println(
                        "remove at the forge: '"
                                + key.title()
                                + "' "
                                + key.fingerprint()
                                + " on "
                                + key.upstream());
            }
        }
        out.flush();
        return 0;
    }

    /**
     * Says what to do before unfollowing again, after asking, or nothing when the refusal stands.
     *
     * @param outcome Why the removal was refused.
     * @param what The running tasks, or the unreviewed work.
     * @param offer Who is asked.
     * @return {@code "stop"} to stop the running tasks, {@code "force"} to destroy the unreviewed
     *     work, or {@code null}.
     */
    static @org.jspecify.annotations.Nullable String nextTry(
            final ProjectDeletion.Outcome outcome,
            final java.util.List<String> what,
            final Offer offer) {
        if (offer == Offer.NOBODY) {
            return null;
        }
        return switch (outcome) {
            case TASKS_RUNNING ->
                    offer.confirm(
                                    "its tasks are still up: " + String.join(", ", what),
                                    "Stop them now, as 'porelo task stop' does?",
                                    "",
                                    false)
                            ? "stop"
                            : null;
            case HOLDS_WORK ->
                    offer.confirm(
                                    "it holds work nobody has reviewed",
                                    "Destroy it and unfollow?",
                                    String.join(", ", what),
                                    true)
                            ? "force"
                            : null;
            default -> null;
        };
    }
}
