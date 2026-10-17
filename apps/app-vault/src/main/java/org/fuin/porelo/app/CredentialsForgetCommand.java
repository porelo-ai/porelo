package org.fuin.porelo.app;

import java.io.PrintWriter;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Forgets a record, and says what still holds a value.
 *
 * <p>It does not remove a secret. A key in somebody's own directory is theirs, and a vault entry is
 * removed by a person who says so - {@code vault remove}. Naming what is left is how somebody can
 * take the second step instead of believing it was already taken.
 */
@Command(
        name = "forget",
        mixinStandardHelpOptions = true,
        description = "Forgets a credential record. The value itself is left alone.")
public class CredentialsForgetCommand implements Callable<Integer>, PoreloFactory.ContextAware {

    @Parameters(
            index = "0",
            paramLabel = "<match>",
            description = "The destinations it covers, as 'porelo credentials list' shows them.")
    private String match;

    @Spec private CommandSpec spec;

    private PoreloContext context = PoreloContext.real();

    @Override
    public void setContext(final PoreloContext context) {
        this.context = context;
    }

    @Override
    public Integer call() {
        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();
        final String left;
        final CredentialDeclarations declarations = new CredentialDeclarations(context);
        try {
            left = declarations.forget(match);
        } catch (final java.io.IOException ex) {
            err.println("porelo: cannot write the record: " + ex.getMessage());
            err.flush();
            return 70;
        }
        if (left == null) {
            err.println("porelo: nothing is declared for '" + match + "'");
            err.flush();
            return 70;
        }
        out.println("forgot     " + match);
        final String inVault = declarations.leftInVault();
        if (inVault != null
                && removeToo(
                        inVault,
                        Offer.atTerminal()
                                ? new Offer(Offer.Asker.terminal(), false, false, err)
                                : Offer.NOBODY)) {
            out.flush();
            return context.exec()
                    .applyAsInt(java.util.List.of(PoreloBinary.path(), "vault", "remove", inVault));
        }
        if (!left.isEmpty()) {
            out.println("           " + left);
        }
        out.flush();
        return 0;
    }

    /**
     * Asks whether to remove the secret a forgotten declaration leaves in the vault, as 'porelo
     * vault remove' does - which then asks what it asks about revoking it. No by default.
     *
     * @param id The vault entry.
     * @param offer Who is asked.
     * @return Whether to remove it.
     */
    static boolean removeToo(final String id, final Offer offer) {
        return offer != Offer.NOBODY
                && offer.confirm(
                        "'" + id + "' is still in the vault",
                        "Remove it too, as 'porelo vault remove' does?",
                        "the vault entry '" + id + "'",
                        true);
    }
}
