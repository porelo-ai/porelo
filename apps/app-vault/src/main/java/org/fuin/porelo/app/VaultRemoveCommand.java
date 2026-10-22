package org.fuin.porelo.app;

import java.io.PrintWriter;
import java.util.concurrent.Callable;
import org.fuin.porelo.supervisor.Grants;
import org.fuin.porelo.supervisor.TokenPurchase;
import org.fuin.porelo.vault.VaultEntry;
import org.fuin.porelo.vault.VaultException;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Removes one credential from the vault.
 *
 * <p>An entry a person granted an authorization for takes the grant with it, and the service is
 * told first (RFC 7009): a refresh token deleted here and still honoured there is a grant nobody
 * can see any more.
 */
@Command(
        name = "remove",
        mixinStandardHelpOptions = true,
        description = "Removes a credential from the vault.")
public class VaultRemoveCommand implements Callable<Integer>, PoreloFactory.ContextAware {

    @Parameters(index = "0", paramLabel = "<name>", description = "Name of the entry to remove.")
    private String name;

    @Option(
            names = "--without-revoking",
            description =
                    "Remove a granted authorization here although the service could not be told - or, for a"
                            + " token that does not expire, cannot be: it then works there until revoked there.")
    private boolean withoutRevoking;

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

        if (!context.vault().exists()) {
            err.println("porelo: no vault at " + context.vault().path());
            err.flush();
            return 69;
        }
        if (TaskSecrets.reserved(name) && !name.startsWith(TaskSecrets.TRANSPORT_PREFIX)) {
            // A task's own token: removing it by hand would break that task's return after a
            // reboot.
            err.println(
                    "porelo: '"
                            + name
                            + "' belongs to a task, not to you - removing the task removes it");
            err.flush();
            return 64;
        }

        final String grantName = TaskSecrets.GRANT_PREFIX + name;
        final VaultEntry grant;
        try {
            // Through the one view of grants: the credentials hide them, so a grant looked up there
            // was never found, and every removal deleted it here without telling the service.
            grant = context.readableGrants().map(grants -> grants.get(name)).orElse(null);
        } catch (VaultException ex) {
            err.println("porelo: " + ex.getMessage());
            err.flush();
            return 70;
        }
        if (grant != null && VaultAuthorizeCommand.HELD.equals(grant.type())) {
            // A token that never expires, granted to a public client: the service revokes it only
            // for the application's owner, which this machine is not. Forgetting it here leaves it
            // working there, so that is done only when the person says they know.
            final String where = revokedWhere(context.credentials().get(name));
            if (!withoutRevoking
                    && withoutRevokingAfterAll(
                            name,
                            where,
                            Offer.atTerminal()
                                    ? new Offer(Offer.Asker.terminal(), false, false, err)
                                    : Offer.NOBODY)) {
                withoutRevoking = true;
            }
            if (!withoutRevoking) {
                err.println(
                        "porelo: '"
                                + name
                                + "' holds a token that does not expire, and nothing here can revoke it:"
                                + " only the application's owner can. Revoke it "
                                + where
                                + ", then run 'porelo vault remove "
                                + name
                                + " --without-revoking'");
                err.flush();
                return 1;
            }
            out.println(
                    "not revoked at the service: the token works there until it is revoked "
                            + where);
            out.flush();
        } else if (grant != null
                && LoginCredentials.REFRESH.equals(grant.type())
                && !grantedByAPerson(context.credentials().get(name))) {
            // What renews an agent's own sign-in. The service has no revocation Porelo knows of;
            // the person ends the session there by signing out. Forgotten here with the token it
            // renews.
            out.println(
                    "forgotten what renewed this sign-in; signing out at the service ends the session there too");
            out.flush();
        } else if (grant != null) {
            final String refused = revoke(context.credentials().get(name), grant.value());
            if (refused == null) {
                out.println("revoked   " + name + " at the service");
            } else if (withoutRevoking) {
                out.println("not revoked at the service: " + refused);
            } else {
                // Kept rather than deleted: deleting it would leave a grant alive there that nobody
                // here can revoke.
                err.println("porelo: " + refused);
                err.println(
                        "porelo: '"
                                + name
                                + "' is kept; revoke the grant at the service and run"
                                + " 'porelo vault remove "
                                + name
                                + " --without-revoking'");
                err.flush();
                return 1;
            }
            out.flush();
        }

        final int unregistered = unregister(out, err);
        if (unregistered != 0) {
            return unregistered;
        }

        final boolean[] removed = {false};
        try {
            context.vault()
                    .update(
                            context.requirePassphrase(),
                            entries -> {
                                removed[0] = entries.remove(name) != null;
                                removed[0] |= entries.remove(grantName) != null;
                                removed[0] |=
                                        entries.remove(TaskSecrets.CLIENT_SECRET_PREFIX + name)
                                                != null;
                                removed[0] |=
                                        entries.remove(VaultNames.REGISTRATION_PREFIX + name)
                                                != null;
                                return entries;
                            });
        } catch (VaultException ex) {
            err.println("porelo: " + ex.getMessage());
            err.flush();
            return 70;
        }

        if (!removed[0]) {
            // Not an error: removing something that is not there leaves the wanted state.
            out.println("nothing named '" + name + "' was in the vault");
            out.flush();
            return 0;
        }
        out.println("removed   " + name);
        out.flush();
        return 0;
    }

    /**
     * Tells a person's grant from what renews an agent's own sign-in: both are kept as a refresh
     * token, and only the entry beside it says which, so only a grant is revoked at its service.
     *
     * @param entry The entry the refresh token is kept for, or {@code null}.
     * @return true when the entry is of a kind a person grants.
     */
    private static boolean grantedByAPerson(@Nullable VaultEntry entry) {
        return entry != null && Grants.isGrant(entry.type());
    }

    /**
     * Deletes the client Porelo registered for the entry at the service, after the grant is revoked
     * (RFC 7592). A registration the service cannot be told about keeps the entry, as a grant does,
     * unless the person said to go on without.
     *
     * @param out Where what was done is said.
     * @param err Where a refusal goes.
     * @return 0 to go on removing, otherwise the exit code.
     */
    private int unregister(final PrintWriter out, final PrintWriter err) {
        final VaultEntry registration;
        try {
            registration = ServiceRegistration.kept(context, name);
        } catch (VaultException ex) {
            err.println("porelo: " + ex.getMessage());
            err.flush();
            return 70;
        }
        if (registration == null) {
            return 0;
        }
        final String refused =
                ServiceRegistration.delete(registration, VaultAuthorizeCommand.http(context));
        if (refused == null) {
            out.println("deleted   the client registered for " + name + " at the service");
        } else if (withoutRevoking) {
            out.println("not deleted at the service: " + refused);
        } else {
            err.println("porelo: " + refused);
            err.println(
                    "porelo: '"
                            + name
                            + "' is kept; delete the client registration at the service and run"
                            + " 'porelo vault remove "
                            + name
                            + " --without-revoking'");
            err.flush();
            return 1;
        }
        out.flush();
        return 0;
    }

    /**
     * Says where a person revokes a token this machine cannot.
     *
     * @param entry The entry naming the service, or {@code null}.
     * @return Where, as a phrase.
     */
    static String revokedWhere(@Nullable VaultEntry entry) {
        final String url = entry == null ? null : entry.settings().get("token_url");
        final String host = url == null ? null : java.net.URI.create(url).getHost();
        if (host != null && (host.equals("github.com") || host.endsWith(".github.com"))) {
            // GitHub lists the two kinds apart, and a client id says which it is: a GitHub App's
            // begins
            // with 'Iv', an OAuth App's with 'Ov'. Told the wrong list, a person finds nothing
            // there.
            final String client =
                    entry == null ? "" : entry.settings().getOrDefault("client_id", "");
            if (client.startsWith("Iv")) {
                return "on GitHub under Settings → Applications → Authorized GitHub Apps";
            }
            if (client.startsWith("Ov")) {
                return "on GitHub under Settings → Applications → Authorized OAuth Apps";
            }
            return "on GitHub under Settings → Applications → Authorized GitHub Apps, or Authorized OAuth Apps";
        }
        return "in the service's own settings for authorized applications"
                + (host == null ? "" : ", at " + host);
    }

    /**
     * Tells the service a grant is finished with.
     *
     * @param entry The entry naming the service, or {@code null} if it is gone.
     * @param refreshToken The grant's refresh token.
     * @return {@code null} when the service revoked it, else why it could not be told.
     */
    static @Nullable String revoke(@Nullable VaultEntry entry, String refreshToken) {
        if (entry == null || !Grants.isGrant(entry.type())) {
            return "the entry that names the service is gone, so it cannot be told";
        }
        try {
            Grants.revoke(
                    Grants.service(entry.type(), entry.value(), entry.settings()),
                    org.fuin.porelo.core.net.TrustedCertificates.builder()
                            .connectTimeout(java.time.Duration.ofSeconds(30))
                            .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                            .build(),
                    refreshToken);
            return null;
        } catch (TokenPurchase.Refused | IllegalArgumentException ex) {
            return ex.getMessage();
        }
    }

    /**
     * Asks whether to forget a token here that only the service can revoke, no by default: it goes
     * on working there.
     *
     * @param name The vault entry.
     * @param where Where it is revoked.
     * @param offer Who is asked.
     * @return Whether to forget it without revoking it.
     */
    static boolean withoutRevokingAfterAll(
            final String name, final String where, final Offer offer) {
        return offer != Offer.NOBODY
                && offer.confirm(
                        "'" + name + "' holds a token that nothing here can revoke",
                        "Forget it here all the same?",
                        "the token, which works there until it is revoked " + where,
                        true);
    }
}
