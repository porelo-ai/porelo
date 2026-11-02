package org.fuin.porelo.app;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine;

/** Runs {@code porelo identity ...} on a test's machine, with its organization in place. */
final class IdentityRun {

    private IdentityRun() {}

    /** Runs the verb on a machine that names no organization. */
    static int identity(final RegistryCheckout registry, final String... args) {
        return run(registry, (context, repository) -> nowhere(), null, args);
    }

    /** Runs the verb on the organization's machine; its output is in the machine's writers. */
    static int identity(final TestOrganization organization, final String... args) {
        return run(organization.machine, organization.opener(), null, args);
    }

    /** Runs the verb on the organization's machine, its store opened by this. */
    static int identity(
            final TestOrganization organization,
            final IdentityOrganization.Opener opener,
            final String... args) {
        return run(organization.machine, opener, null, args);
    }

    /** Runs the verb on the organization's machine, a stand-in KeePassXC beside it. */
    static int identity(
            final TestOrganization organization, final KeePassXcKey store, final String... args) {
        return run(organization.machine, organization.opener(), store, args);
    }

    private static IdentityOrganization.Opened nowhere() {
        throw new IllegalStateException("this machine names no organization");
    }

    /** Puts the store and KeePassXC of a test on the command line. */
    static void wire(
            final CommandLine cli,
            final IdentityOrganization.Opener opener,
            final @Nullable KeePassXcKey store) {
        final var verbs = cli.getSubcommands().get("identity").getSubcommands();
        ((IdentityShowCommand) verbs.get("show").getCommand()).opener = opener;
        ((IdentityKeysCommand) verbs.get("keys").getCommand()).opener = opener;
        ((IdentityCreateCommand) verbs.get("create").getCommand()).opener = opener;
        ((IdentityRotateCommand) verbs.get("rotate").getCommand()).opener = opener;
        ((IdentityRevokeCommand) verbs.get("revoke").getCommand()).opener = opener;
        ((IdentityBackupKeyCommand) verbs.get("backup-key").getCommand()).opener = opener;
        if (store != null) {
            ((IdentityCreateCommand) verbs.get("create").getCommand()).store = store;
            ((IdentityRotateCommand) verbs.get("rotate").getCommand()).store = store;
            ((IdentityRevokeCommand) verbs.get("revoke").getCommand()).store = store;
        }
    }

    private static int run(
            final RegistryCheckout registry,
            final IdentityOrganization.Opener opener,
            final @Nullable KeePassXcKey store,
            final String... args) {
        registry.out.getBuffer().setLength(0);
        registry.err.getBuffer().setLength(0);
        final CommandLine cli = PoreloCli.commandLine(registry.context());
        cli.setOut(new PrintWriter(registry.out));
        cli.setErr(new PrintWriter(registry.err));
        wire(cli, opener, store);
        final List<String> all = new ArrayList<>(List.of("identity"));
        all.addAll(List.of(args));
        return cli.execute(all.toArray(String[]::new));
    }

    /** Keeps a card on the checkout's machine, as {@code identity create} keeps one. */
    static void keep(final RegistryCheckout registry, final String card) throws Exception {
        new IdentityStore(registry.context()).keep(card);
    }

    /** Puts a login's key in the machine's revocation list, as {@code ssh-keygen -k} makes it. */
    static void revoke(final RegistryCheckout registry, final String login) {
        registry.runner.runOrFail(
                org.fuin.porelo.core.process.Command.of(
                        "sh",
                        "-c",
                        "mkdir -p \"$(dirname \"$1\")\" && ssh-keygen -q -k -f \"$1\" \"$2\"",
                        "revoke",
                        registry.paths.projects().revocations().toString(),
                        registry.keyFiles.get(login) + ".pub"));
    }
}
