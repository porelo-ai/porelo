package org.fuin.porelo.app;

import java.util.List;
import java.util.Map;
import org.fuin.porelo.gate.GitCredentials;

/**
 * The vault, answering the gate's question about a URL.
 *
 * <p>The seam exists because the module that runs git cannot see the vault. This is the one
 * implementation that matters: it hands out what {@link FollowCredential} lends, so a push
 * forwarded by the gate reaches a private forge by the same key a follow fetches with and a task
 * pushes with.
 */
public final class VaultGitCredentials implements GitCredentials {

    private final PoreloContext context;

    private final String label;
    private final java.util.function.Supplier<org.fuin.porelo.core.credential.CredentialRegistry>
            registries;

    /**
     * Constructor.
     *
     * @param context Where the vault is.
     * @param label Names the agent socket, so two commands cannot collide.
     */
    public VaultGitCredentials(final PoreloContext context, final String label) {
        this(context, label, context::credentialRegistry);
    }

    VaultGitCredentials(
            PoreloContext context,
            String label,
            java.util.function.Supplier<org.fuin.porelo.core.credential.CredentialRegistry>
                    registries) {
        this.context = context;
        this.label = label;
        this.registries = registries;
    }

    @Override
    public Lease forPush(final String url) {
        final var declared = registries.get().forUrl("git", url);
        if (GitCredentialNames.kindOf(url) == GitCredentialNames.Kind.KEY) {
            if (declared == null
                    || declared.source() == org.fuin.porelo.core.credential.Credential.Source.AGENT
                            && declared.id().isBlank()) {
                throw PushCredentialHelp.refused(context, url, declared, "NO_CREDENTIAL", null);
            }
        }
        return lease(url, true, declared);
    }

    @Override
    public Lease forUrl(final String url) {
        return lease(url, false, registries.get().forUrl("git", url));
    }

    private Lease lease(
            String url,
            boolean push,
            org.fuin.porelo.core.credential.@org.jspecify.annotations.Nullable Credential
                    declared) {
        final FollowCredential lent;
        try {
            lent = FollowCredential.open(context, url, label, "git", declared);
        } catch (org.fuin.porelo.core.credential.CredentialException ex) {
            if (push && GitCredentialNames.kindOf(url) == GitCredentialNames.Kind.KEY) {
                throw PushCredentialHelp.refused(context, url, declared, "KEY_UNAVAILABLE", null);
            }
            throw ex;
        }
        if (push && GitCredentialNames.kindOf(url) == GitCredentialNames.Kind.KEY && !lent.lent()) {
            lent.close();
            throw PushCredentialHelp.refused(context, url, declared, "KEY_UNAVAILABLE", null);
        }
        return new Lease() {

            @Override
            public Map<String, String> environment() {
                return lent.environment();
            }

            @Override
            public List<String> arguments() {
                return lent.arguments();
            }

            @Override
            public org.fuin.porelo.gate.GateException failure(
                    org.fuin.porelo.core.process.CommandException failure) {
                return push && GitCredentialNames.kindOf(url) == GitCredentialNames.Kind.KEY
                        ? PushCredentialHelp.refused(
                                context, url, declared, "PUSH_REFUSED", failure)
                        : new org.fuin.porelo.gate.GateException(
                                failure.getMessage() == null ? "git failed" : failure.getMessage(),
                                failure);
            }

            @Override
            public void close() {
                lent.close();
            }
        };
    }
}
