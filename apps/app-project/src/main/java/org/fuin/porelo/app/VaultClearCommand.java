package org.fuin.porelo.app;

import java.io.PrintWriter;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * Clears the vault in one step: its file, backup and lock, what the keyring caches of it, and what
 * the transports keep with its secrets. Lists it first; {@code --yes} clears it.
 *
 * <p>Here, with the other clearings, and put into the {@code vault} group where the command line is
 * assembled: it reaches the transports and the tasks, which the vault's own area does not depend
 * on.
 */
@Command(
        name = "clear",
        mixinStandardHelpOptions = true,
        description =
                "Clears the vault: its file, backup and lock, the cached passphrase and shares, and the accounts"
                        + " a transport keeps with it. Lists it first; --yes clears it.")
public class VaultClearCommand implements Callable<Integer>, PoreloFactory.ContextAware {

    @Option(
            names = "--yes",
            description = "Clears what the listing names. Without it, nothing is removed.")
    private boolean yes;

    @Option(names = "--dry-run", description = "Lists what would go, and removes nothing.")
    private boolean dryRun;

    @Option(
            names = "--force",
            description =
                    "Clears a locked vault unread, or one whose transport could not clear a project's account."
                            + " A running task that holds a token from it still has to be stopped first.")
    private boolean force;

    @Option(
            names = "--json",
            description =
                    "Answers one object on standard output: 'items' (each with kind, what, status, why,"
                            + " transport and project), 'refused', and 'kept' - why the vault was kept:"
                            + " tasks, locked or transports, \"\" when it was not. The exit is 1 when anything"
                            + " was not removed, as without it.")
    private boolean json;

    @Spec private CommandSpec spec;

    private PoreloContext context = PoreloContext.real();

    @Override
    public void setContext(final PoreloContext context) {
        this.context = context;
    }

    @Override
    public Integer call() {
        try {
            context.openIfShut(true, false, spec.commandLine().getErr());
        } catch (org.fuin.porelo.vault.VaultException ex) {
            // Clearing reports unreadable content and must still erase cached keys.
        }
        final boolean listOnly = dryRun || !yes;
        final PrintWriter out = spec.commandLine().getOut();
        final Clearing.Result result = new VaultClearing(context).clear(listOnly, force);
        if (json) {
            // A program decides on --force from these values, never from a sentence.
            out.println(org.fuin.porelo.wire.Json.write(asJson(result)));
            out.flush();
            // As the listing exits: anything not removed is a failure, refused or not.
            return result.refused()
                            || result.items().stream()
                                    .anyMatch(item -> item.status() == Clearing.Status.NOT_REMOVED)
                    ? 1
                    : 0;
        }
        final int code = ClearCommand.render(result, listOnly, !dryRun && !yes, out);
        if (result.items().stream()
                .anyMatch(
                        item ->
                                "vault".equals(item.kind())
                                        && item.status() == Clearing.Status.REMOVED)) {
            out.println("the vault is gone; 'porelo vault init' starts a new one");
            out.flush();
        }
        return code;
    }

    /**
     * Returns what a clearing did as the object {@code --json} prints.
     *
     * @param result What it did.
     * @return Its items, whether it refused, and why the vault was kept.
     */
    static java.util.Map<String, Object> asJson(final Clearing.Result result) {
        return java.util.Map.of(
                "items",
                result.items().stream().map(Clearing.Item::asMap).toList(),
                "refused",
                result.refused(),
                "kept",
                result.kept().wire());
    }
}
