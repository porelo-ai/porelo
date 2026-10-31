package org.fuin.porelo.app;

import java.io.PrintWriter;
import java.util.concurrent.Callable;
import org.fuin.porelo.runtime.ContainerSummary;
import org.fuin.porelo.vault.KernelKeyring;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * Drops the cached passphrase, so the next command asks for it again.
 *
 * <p>A verb of its own rather than a flag on {@code unlock}, because an operator who wants the
 * vault shut does not go looking for the opposite command - and this is a security control, which
 * is the worst kind of thing to hide behind the option of its inverse.
 */
@Command(
        name = "lock",
        mixinStandardHelpOptions = true,
        description =
                "Drops the cached passphrase and shares; later vault openings need an unlock.")
public class VaultLockCommand implements Callable<Integer>, PoreloFactory.ContextAware {

    @Spec private CommandSpec spec;

    private PoreloContext context = PoreloContext.real();

    @Override
    public void setContext(PoreloContext context) {
        this.context = context;
    }

    /**
     * Forgets the cached passphrase and says what that did and did not do.
     *
     * <p>Shared with {@code unlock --forget}, which is the same operation under its older name.
     *
     * @param context Where the keyring name and the running tasks come from.
     * @param out Where to report.
     * @return Exit code. Zero whether or not anything was cached - the wanted state is "not
     *     cached", and it holds either way - and non-zero when the keyring could not say, because
     *     zero would let a script believe it had locked something.
     */
    static int lock(PoreloContext context, PrintWriter out) {

        final KernelKeyring.Forgotten forgotten;
        try {
            forgotten = forget(context);
        } catch (org.fuin.porelo.vault.VaultException ex) {
            out.println("could not release the host vault share: " + ex.getMessage());
            out.flush();
            return 70;
        }
        if (forgotten == KernelKeyring.Forgotten.UNKNOWN) {
            // Never reported as "nothing was cached": that is a claim about a secret nobody
            // checked, and the passphrase may still be sitting in the keyring.
            out.println(
                    "could not be established whether a passphrase is cached; the keyring did"
                            + " not answer. Check 'keyctl show @u' and try again, and treat the"
                            + " passphrase as still cached until it does.");
            out.flush();
            return 70;
        }
        out.println(
                forgotten == KernelKeyring.Forgotten.CLEARED
                        ? "locked    use 'porelo host unlock <host>' for a host vault, or unlock with the vault's passphrase or device"
                        : "nothing was cached");

        // Said only when it is true, and it changes what locking means: a running task's proxy read
        // its credential when it started and holds it in its own memory, where this cannot reach.
        // Stopping the task is what ends that.
        running(context, out);
        out.flush();
        return 0;
    }

    /** Erases every cached way into this vault, including the daemon's host share. */
    public static KernelKeyring.Forgotten forget(PoreloContext context) {
        final boolean available = KernelKeyring.available();
        return forget(
                context,
                () ->
                        available
                                ? new KernelKeyring(context.paths().vault().vaultKeyringKey())
                                        .forget()
                                : KernelKeyring.Forgotten.NOTHING_CACHED,
                () ->
                        available
                                ? VaultShare.forget(context.paths())
                                : KernelKeyring.Forgotten.NOTHING_CACHED);
    }

    static KernelKeyring.Forgotten forget(
            PoreloContext context,
            java.util.function.Supplier<KernelKeyring.Forgotten> passphraseForget,
            java.util.function.Supplier<KernelKeyring.Forgotten> shareForget) {
        boolean hostShare = false;
        org.fuin.porelo.vault.VaultException unavailable = null;
        final KernelKeyring.Forgotten passphrase = forgetRetried(passphraseForget);
        final KernelKeyring.Forgotten share = forgetRetried(shareForget);
        try {
            hostShare = context.forgetHostVault();
        } catch (org.fuin.porelo.vault.VaultException ex) {
            unavailable = ex;
        }
        if (unavailable != null) {
            throw new org.fuin.porelo.vault.VaultException(
                    unavailable.getMessage()
                            + "; local keyring passphrase: "
                            + passphrase
                            + ", device share: "
                            + share
                            + "; retry 'porelo vault lock'",
                    unavailable);
        }
        if (passphrase == KernelKeyring.Forgotten.UNKNOWN
                || share == KernelKeyring.Forgotten.UNKNOWN) {
            return KernelKeyring.Forgotten.UNKNOWN;
        }
        return hostShare
                        || passphrase == KernelKeyring.Forgotten.CLEARED
                        || share == KernelKeyring.Forgotten.CLEARED
                ? KernelKeyring.Forgotten.CLEARED
                : KernelKeyring.Forgotten.NOTHING_CACHED;
    }

    private static void running(PoreloContext context, PrintWriter out) {
        final long running =
                context.podman().poreloTasks().stream().filter(ContainerSummary::running).count();
        if (running > 0) {
            out.println(
                    "running   "
                            + running
                            + " task"
                            + (running == 1 ? "" : "s")
                            + " still hold the credential their proxy already read; stop them to end that");
        }
    }

    @Override
    public Integer call() {
        return lock(context, spec.commandLine().getOut());
    }

    /**
     * Forgets through the keyring, asking again when it did not answer: three tries, a moment
     * apart.
     *
     * @param forget One try.
     * @return The first answer that is not {@code UNKNOWN}, or {@code UNKNOWN} after three.
     */
    static KernelKeyring.Forgotten forgetRetried(
            final java.util.function.Supplier<KernelKeyring.Forgotten> forget) {
        KernelKeyring.Forgotten answer = forget.get();
        for (int tries = 1; answer == KernelKeyring.Forgotten.UNKNOWN && tries < 3; tries++) {
            try {
                Thread.sleep(200);
            } catch (final InterruptedException ex) {
                Thread.currentThread().interrupt();
                return answer;
            }
            answer = forget.get();
        }
        return answer;
    }
}
