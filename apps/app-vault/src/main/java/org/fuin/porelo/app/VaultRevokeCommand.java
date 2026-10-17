package org.fuin.porelo.app;

import java.io.PrintWriter;
import java.util.concurrent.Callable;
import org.fuin.porelo.vault.Keyslot;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Takes a device's way into the vault away.
 *
 * <p>One wrapped blob is deleted and nothing else happens: no re-keying, no other device disturbed,
 * no passphrase rotated. A lost laptop costs exactly this command.
 */
@Command(
        name = "revoke",
        mixinStandardHelpOptions = true,
        description = "Removes a device's way into the vault.")
public class VaultRevokeCommand implements Callable<Integer>, PoreloFactory.ContextAware {

    @Parameters(
            index = "0",
            paramLabel = "<id>",
            description = "The keyslot, as 'porelo vault devices' lists it.")
    private String id;

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
        context.openIfShut(true, false, err);
        final Keyslots.Revocation result = new Keyslots(context).revoke(id);
        if (result.outcome() != Keyslots.Revoked.REVOKED) {
            err.println("porelo: " + result.detail());
            if (result.outcome() == Keyslots.Revoked.VAULT_LOCKED) {
                err.println("       unlock it first: porelo vault unlock");
            }
            err.flush();
            return 1;
        }
        out.println("revoked " + id);
        out.println("still open the vault:");
        for (final Keyslot slot : result.remaining()) {
            out.println("   " + (slot.recovery() ? "the passphrase" : slot.name()));
        }
        out.flush();
        return 0;
    }
}
