package org.fuin.porelo.app;

import java.io.PrintWriter;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import org.fuin.porelo.vault.VaultEntry;
import org.fuin.porelo.vault.VaultException;
import org.fuin.porelo.vault.VaultFile;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/** Marks an agent's own sign-in due for renewal by the next task's broker. */
@Command(
        name = "renew",
        mixinStandardHelpOptions = true,
        description =
                "Marks a sign-in due for renewal on the host by the next task's first request.")
public class VaultRenewCommand implements Callable<Integer>, PoreloFactory.ContextAware {

    @Parameters(index = "0", paramLabel = "<name>", description = "The sign-in kept in the vault.")
    private String name;

    @Spec private CommandSpec spec;

    private PoreloContext context = PoreloContext.real();

    private final Clock clock;

    /** Creates a command using the host's UTC clock. */
    public VaultRenewCommand() {
        this(Clock.systemUTC());
    }

    /**
     * Creates a command with a supplied clock.
     *
     * @param clock The time used to mark a sign-in due.
     */
    VaultRenewCommand(final Clock clock) {
        this.clock = clock;
    }

    /**
     * Sets the context that opens the vault.
     *
     * @param context The command's context.
     */
    @Override
    public void setContext(final PoreloContext context) {
        this.context = context;
    }

    /**
     * Marks the sign-in due while holding the vault's lock.
     *
     * @return Zero on success, two for an entry that cannot be renewed, 69 for no vault, or 70 if
     *     the vault cannot be opened or updated.
     */
    @Override
    public Integer call() {
        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();
        final VaultFile vault = context.vault();
        if (!vault.exists()) {
            err.println(
                    "porelo: there is no vault at "
                            + vault.path()
                            + "; create it with 'porelo vault init'");
            err.flush();
            return 69;
        }
        try {
            final VaultFile.Opener opener =
                    context.openerAsking(false, err)
                            .orElseThrow(
                                    () ->
                                            new VaultException(
                                                    "the vault is locked; open it with"
                                                            + " 'porelo vault unlock'"));
            vault.update(
                    opener,
                    entries -> {
                        final VaultEntry entry = entries.get(name);
                        if (entry == null) {
                            throw new IllegalArgumentException("the vault holds no '" + name + "'");
                        }
                        if (!LoginCredentials.renewable(entry)) {
                            throw new IllegalArgumentException(
                                    "'" + name + "' is not a renewable sign-in");
                        }
                        if (!entries.containsKey(TaskSecrets.GRANT_PREFIX + name)) {
                            throw new IllegalArgumentException(
                                    "'"
                                            + name
                                            + "' was imported from this machine's own agent;"
                                            + " renewing it would end that agent's session;"
                                            + " sign in with 'porelo vault login' to have it renewed here");
                        }
                        final Map<String, String> settings = new LinkedHashMap<>(entry.settings());
                        settings.put(LoginCredentials.EXPIRES_AT, clock.instant().toString());
                        entries.put(name, new VaultEntry(entry.value(), entry.type(), settings));
                        return entries;
                    });
        } catch (final IllegalArgumentException ex) {
            err.println("porelo: " + ex.getMessage());
            err.flush();
            return 2;
        } catch (final VaultException ex) {
            err.println("porelo: " + ex.getMessage());
            err.flush();
            return 70;
        }
        out.println(
                "marked '"
                        + name
                        + "' due: the next task's first request renews it on the host;"
                        + " a running task keeps its token until it ends");
        out.flush();
        return 0;
    }
}
