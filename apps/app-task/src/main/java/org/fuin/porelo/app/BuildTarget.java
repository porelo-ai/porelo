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
     * Reads what opening a task's pull request carries.
     *
     * <p>On a host the builds credential is the person's own token, and a pull request opened with
     * it names the person as its author, whose approval the forge then refuses. So there it is
     * opened with the task's own forge token, which the host made for the task's account at its
     * start, and with nothing else: without that token no pull request is opened.
     *
     * @param fromHost Whether the builds come from the host's file.
     * @param container The task's container, whose own token is asked for.
     */
    static Target pullRequest(
            final PoreloContext context,
            final String upstream,
            final String api,
            final String credential,
            final boolean fromHost,
            final String container) {
        if (!fromHost) {
            return of(context, upstream, api, credential, false);
        }
        if (context.readableCredentials().isEmpty()) {
            throw new BuildWatch.NotNow(
                    BuildWatch.NotNow.Reason.VAULT_SHUT,
                    "the vault is shut, so Porelo cannot read the task's own forge token; it opens"
                            + " the pull request once the vault is unlocked");
        }
        final String token = new TaskSecrets(context).forgeToken(container).orElse("");
        if (token.isBlank()) {
            throw new BuildWatch.NotNow(
                    BuildWatch.NotNow.Reason.ENTRY_MISSING,
                    "the task's own forge token for "
                            + container
                            + " is not in the vault or could not be read, so its pull request is not"
                            + " opened: on a host it is opened as the"
                            + " task, never with the person's token, whose approval the forge"
                            + " would then refuse");
        }
        return new Target(upstream, api, token);
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
