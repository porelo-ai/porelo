package org.fuin.porelo.app;

import java.io.PrintWriter;
import java.util.Arrays;
import java.util.concurrent.Callable;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.fuin.porelo.runtime.ContainerSummary;
import org.fuin.porelo.vault.CommandPassphrase;
import org.fuin.porelo.vault.ConsolePassphrase;
import org.fuin.porelo.vault.KernelKeyring;
import org.fuin.porelo.vault.PassphraseTiers;
import org.fuin.porelo.vault.VaultException;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * Re-encrypts the vault under a different passphrase.
 *
 * <p><strong>At the machine, never over the socket.</strong> The same rule as unlocking, and for
 * the same reason: this takes a passphrase, and the daemon has no terminal to take one at. A method
 * would mean a secret travelling INTO the contract, over a socket that can be forwarded across ssh,
 * where everything else only ever travels out as a name.
 *
 * <p>Until this existed the product could not change a passphrase at all - not over the wire and
 * not here - so the only way was to build a new vault and store every credential again, which needs
 * the values, which the vault deliberately will not give back.
 */
@Command(
        name = "passphrase",
        mixinStandardHelpOptions = true,
        description = "Re-encrypts the vault under a different passphrase.")
public class VaultPassphraseCommand implements Callable<Integer>, PoreloFactory.ContextAware {

    @Option(
            names = "--passphrase-command",
            paramLabel = "<command>",
            description = "Command whose first output line is the CURRENT passphrase.")
    private @Nullable String passphraseCommand;

    @Option(
            names = "--new-passphrase-command",
            paramLabel = "<command>",
            description =
                    "Command whose first output line is the NEW passphrase. Without it, it"
                            + " is asked for twice.")
    private @Nullable String newPassphraseCommand;

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
        final var vault = context.vault();

        if (!vault.exists()) {
            err.println("porelo: there is no vault at " + vault.path() + " to re-encrypt");
            err.flush();
            return 69;
        }

        final var runner = new ProcessCommandRunner();
        char[] fresh = null;
        char[] again = null;
        try {
            context.requirePassphraseSlot();
            final org.fuin.porelo.vault.VaultFile.Opener current =
                    context.requireOpener(new CommandPassphrase(runner, passphraseCommand));

            // Checked before anything else is asked for. Discovering the current one was wrong
            // after somebody has typed a new one twice wastes the part of this that is tedious.
            vault.read(current);

            if (newPassphraseCommand != null) {
                fresh =
                        new CommandPassphrase(runner, newPassphraseCommand)
                                .passphrase()
                                .orElseThrow(
                                        () ->
                                                new VaultException(
                                                        "the new-passphrase command produced nothing"));
            } else {
                fresh =
                        new PassphraseTiers(new ConsolePassphrase("New vault passphrase: "))
                                .require();
                again =
                        new PassphraseTiers(new ConsolePassphrase("New vault passphrase again: "))
                                .require();
                if (!Arrays.equals(fresh, again)) {
                    // Typed twice because there is nothing to compare it against afterwards: a
                    // mistyped new passphrase encrypts the vault under something nobody knows.
                    err.println("porelo: the two did not match - nothing was changed");
                    err.flush();
                    return 70;
                }
            }
            if (fresh.length == 0) {
                err.println(
                        "porelo: an empty passphrase would leave the vault unprotected"
                                + " - nothing was changed");
                err.flush();
                return 70;
            }

            vault.rekey(current, fresh);
            out.println("re-encrypted " + vault.path());

            // The cached one is now the WRONG one, so it goes. Not a courtesy: leaving it turns the
            // next command into a failure that reads like a damaged vault.
            if (KernelKeyring.available()) {
                final KernelKeyring.Forgotten forgotten =
                        VaultLockCommand.forgetRetried(
                                () ->
                                        new KernelKeyring(context.paths().vault().vaultKeyringKey())
                                                .forget());
                if (forgotten == KernelKeyring.Forgotten.CLEARED) {
                    // Kept where the old one was: the person just typed it twice, and 'vault
                    // unlock' would only ask it a third time.
                    final char[] kept = fresh;
                    out.println(
                            afterRekey(
                                    forgotten,
                                    passphrase -> {
                                        try {
                                            new KernelKeyring(
                                                            context.paths()
                                                                    .vault()
                                                                    .vaultKeyringKey())
                                                    .store(passphrase, null);
                                            return true;
                                        } catch (final RuntimeException ex) {
                                            return false;
                                        }
                                    },
                                    kept));
                } else if (forgotten == KernelKeyring.Forgotten.UNKNOWN) {
                    // The cached passphrase is now the wrong one. Silence here would leave the next
                    // command failing in a way that reads like a damaged vault.
                    out.println(
                            "could not drop the cached passphrase, and it is now the OLD one;"
                                    + " run 'porelo vault lock' until it succeeds");
                }
            }

            // Said every time it is true, for the same reason 'vault lock' says it: a proxy read
            // its credential when its task started and holds it in its own memory, where this
            // cannot reach. An operator changing a passphrase because one leaked has to know that
            // stopping those tasks is the part that ends it.
            final long running =
                    context.podman().poreloTasks().stream()
                            .filter(ContainerSummary::running)
                            .count();
            if (running > 0) {
                out.println(
                        "running    "
                                + running
                                + " task"
                                + (running == 1 ? "" : "s")
                                + " still hold the credential their proxy already read; stop them to end that");
            }
            out.flush();
            return 0;

        } catch (VaultException ex) {
            err.println("porelo: " + ex.getMessage());
            err.flush();
            return 70;
        } finally {
            for (final char[] secret : new char[][] {fresh, again}) {
                if (secret != null) {
                    Arrays.fill(secret, '\0');
                }
            }
        }
    }

    /**
     * Says what became of a cached passphrase after the vault was re-keyed: the new one kept where
     * the old one was, or, where that failed, that the old one is gone and how to cache the new
     * one.
     *
     * @param forgotten What dropping the old one came to.
     * @param keep Keeps the new one; {@code false} when it could not.
     * @param fresh The new passphrase.
     * @return The line to say.
     */
    static String afterRekey(
            final KernelKeyring.Forgotten forgotten,
            final java.util.function.Predicate<char[]> keep,
            final char[] fresh) {
        return forgotten == KernelKeyring.Forgotten.CLEARED && keep.test(fresh)
                ? "cached     the new passphrase, where the old one was"
                : "dropped    the cached passphrase; 'porelo vault unlock' with the new one";
    }
}
