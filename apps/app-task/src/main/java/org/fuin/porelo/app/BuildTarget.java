package org.fuin.porelo.app;

import org.fuin.porelo.build.api.Target;

/** Reads exactly the vault entry the project names for a question to the forge. */
final class BuildTarget {
    private BuildTarget() {}

    static Target of(
            final PoreloContext context,
            final String upstream,
            final String api,
            final String credential) {
        return of(context, upstream, api, credential, false);
    }

    /**
     * Reads the entry for one question to the forge.
     *
     * @param fromHost Whether the builds come from the host's file, whose entry the host's own
     *     command stores: {@code porelo vault put} stores no token the host's forge gives.
     */
    static Target of(
            final PoreloContext context,
            final String upstream,
            final String api,
            final String credential,
            final boolean fromHost) {
        final var entries = context.readableCredentials();
        if (entries.isEmpty()) {
            throw new BuildWatch.NotNow(
                    BuildWatch.NotNow.Reason.VAULT_SHUT,
                    "the vault is shut, so Porelo cannot read the forge credential '"
                            + credential
                            + "'; it asks the forge again once the vault is unlocked");
        }
        final var entry = entries.get().get(credential);
        if (entry == null && fromHost) {
            throw new BuildWatch.NotNow(
                    BuildWatch.NotNow.Reason.ENTRY_MISSING,
                    "the vault holds no entry '"
                            + credential
                            + "', which this host names for builds; store it with '"
                            + String.join(
                                    " ",
                                    MissingCredential.hostBuilds(credential, "", "restart")
                                            .storeCommand())
                            + "' on this host");
        }
        if (entry == null) {
            throw new BuildWatch.NotNow(
                    BuildWatch.NotNow.Reason.ENTRY_MISSING,
                    "the vault holds no entry '"
                            + credential
                            + "', which the project names for builds; store it with 'porelo vault put "
                            + credential
                            + "'");
        }
        return new Target(upstream, api, entry.value());
    }
}
