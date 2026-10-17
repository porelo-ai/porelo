package org.fuin.porelo.app;

import java.io.PrintWriter;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * Clears this account: every project on it, the built-in project's tasks, and what each transport
 * keeps for it.
 *
 * <p>One step, confirmed once: without {@code --yes} it lists what would go and removes nothing, so
 * the list a person confirms is the list that runs. What only a person's credentials can remove -
 * deploy keys at a forge, this machine's line in a project's {@code machine-signers} - is named,
 * never attempted.
 */
@Command(
        name = "clear",
        mixinStandardHelpOptions = true,
        description =
                "Clears this account: every project, task, mailbox, mirror and follow, and what the transports"
                        + " keep. Lists it first; --yes clears it.")
public class ClearCommand implements Callable<Integer>, PoreloFactory.ContextAware {

    @Option(
            names = "--yes",
            description = "Clears what the listing names. Without it, nothing is removed.")
    private boolean yes;

    @Option(names = "--no-input", description = "Ask nothing: list, and clear only with --yes.")
    private boolean noInput;

    /** Who is asked to clear what is listed: this process's terminal, or nobody without one. */
    Offer.Asker asker = Offer.atTerminal() ? Offer.Asker.terminal() : Offer.Asker.NOBODY;

    @Option(names = "--dry-run", description = "Lists what would go, and removes nothing.")
    private boolean dryRun;

    @Option(
            names = "--force",
            description =
                    "Clears work nobody reviewed and running tasks too. That work is destroyed.")
    private boolean force;

    @Spec private CommandSpec spec;

    private PoreloContext context = PoreloContext.real();

    @Override
    public void setContext(final PoreloContext context) {
        this.context = context;
    }

    @Override
    public Integer call() {
        // What the vault holds is listed and cleared too: at a terminal a shut vault is opened for
        // this alone.
        context.openIfShut(true, false, spec.commandLine().getErr());
        final boolean listOnly = dryRun || !yes;
        final PrintWriter out = spec.commandLine().getOut();
        final boolean asking = !dryRun && !noInput && asker != Offer.Asker.NOBODY;
        final Offer offer =
                asking ? new Offer(asker, false, false, new PrintWriter(out, true)) : Offer.NOBODY;
        boolean forced = force;
        Clearing.Result listed = new Clearing(context).account(listOnly, forced);
        int code = render(listed, listOnly, !dryRun && !yes && !asking, out);
        // What a refusal would send a person to do first is offered instead, each once: the running
        // tasks that hold a
        // token from the vault stopped, then what only --force clears, no by default.
        if (listed.refused() && asking) {
            final java.util.List<String> stopped = stopFirst(refusals(listed), offer);
            for (final String task : stopped) {
                new TaskControl(context).stop(task);
            }
            if (!stopped.isEmpty()) {
                listed = new Clearing(context).account(listOnly, forced);
                code = render(listed, listOnly, false, out);
            }
            if (listed.refused() && forceAnyway(refusals(listed), offer)) {
                forced = true;
                listed = new Clearing(context).account(listOnly, true);
                code = render(listed, listOnly, false, out);
            }
        }
        if (!asking || yes || listed.items().isEmpty() || listed.refused()) {
            return code;
        }
        // What a second command with --yes would clear is asked here instead, no by default.
        if (!new Offer(asker, false, false, new PrintWriter(out, true))
                .confirm("this account's Porelo, listed above", "Clear it now?", "", true)) {
            out.println("nothing was removed");
            out.flush();
            return code;
        }
        return render(new Clearing(context).account(false, forced), false, false, out);
    }

    private static java.util.List<Clearing.Item> refusals(final Clearing.Result result) {
        return result.items().stream()
                .filter(item -> item.status() == Clearing.Status.NOT_REMOVED)
                .toList();
    }

    /**
     * Asks whether to stop the running tasks that hold a token from the vault, which a clearing
     * will not clear past.
     *
     * @param refused What the clearing did not remove.
     * @param offer Who is asked.
     * @return The tasks to stop; empty when there are none, or nobody said yes.
     */
    static java.util.List<String> stopFirst(
            final java.util.List<Clearing.Item> refused, final Offer offer) {
        final java.util.List<String> tasks =
                refused.stream()
                        .filter(item -> item.kind().equals("task"))
                        .map(Clearing.Item::what)
                        .toList();
        if (tasks.isEmpty()
                || offer == Offer.NOBODY
                || !offer.confirm(
                        "these tasks run with a token from the vault: " + String.join(", ", tasks),
                        "Stop them now, as 'porelo task stop' does?",
                        "",
                        false)) {
            return java.util.List.of();
        }
        return tasks;
    }

    /**
     * Asks whether to clear what only {@code --force} clears, naming it first, no by default and
     * never by {@code --yes}.
     *
     * @param refused What the clearing did not remove.
     * @param offer Who is asked.
     * @return Whether to clear it all the same.
     */
    static boolean forceAnyway(final java.util.List<Clearing.Item> refused, final Offer offer) {
        return !refused.isEmpty()
                && offer != Offer.NOBODY
                && offer.confirm(
                        "clearing stops at what only --force clears",
                        "Clear it all the same?",
                        String.join(
                                "; ",
                                refused.stream()
                                        .map(
                                                item ->
                                                        item.kind()
                                                                + " "
                                                                + item.what()
                                                                + (item.why().isEmpty()
                                                                        ? ""
                                                                        : " (" + item.why() + ")"))
                                        .toList()),
                        true);
    }

    /**
     * Prints what a clearing did, one line each, the same for an account and a project.
     *
     * @param result What it did.
     * @param listOnly Whether nothing was removed.
     * @param askYes Whether to say that {@code --yes} clears it.
     * @param out Where to print.
     * @return The exit code: 0, or 1 when something could not be removed or it was refused.
     */
    static int render(
            final Clearing.Result result,
            final boolean listOnly,
            final boolean askYes,
            final PrintWriter out) {
        if (result.items().isEmpty()) {
            out.println("nothing to clear");
            out.flush();
            return 0;
        }
        boolean failed = result.refused();
        for (final Clearing.Item item : result.items()) {
            final String said =
                    switch (item.status()) {
                        case REMOVED -> "removed     ";
                        case WOULD_REMOVE -> "would go    ";
                        case NOT_REMOVED -> "not removed ";
                        case FOR_A_PERSON -> "for you     ";
                    };
            failed |= item.status() == Clearing.Status.NOT_REMOVED;
            out.println(
                    said
                            + item.kind()
                            + "  "
                            + item.what()
                            + (item.why().isEmpty() ? "" : " - " + item.why()));
        }
        if (!result.signer().isEmpty()) {
            out.println(
                    "this machine's line, to take out of each project's machine-signers where it is listed:");
            out.println("            " + result.signer());
        }
        if (askYes && !result.refused()) {
            out.println("nothing was removed; run it again with --yes to clear what is listed");
        }
        out.flush();
        return failed ? 1 : 0;
    }
}
