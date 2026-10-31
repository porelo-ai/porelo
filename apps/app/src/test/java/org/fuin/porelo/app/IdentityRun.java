package org.fuin.porelo.app;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import picocli.CommandLine;

/** Runs {@code porelo identity ...} on a registry checkout's machine, with its forge in place. */
final class IdentityRun {

    private IdentityRun() {}

    /** Runs the verb; its output is in the checkout's {@code out} and {@code err}. */
    static int identity(final RegistryCheckout registry, final String... args) {
        registry.out.getBuffer().setLength(0);
        registry.err.getBuffer().setLength(0);
        final CommandLine cli = PoreloCli.commandLine(registry.context());
        cli.setOut(new PrintWriter(registry.out));
        cli.setErr(new PrintWriter(registry.err));
        final RegistryReadCommand.Forges forges =
                (address, token) -> {
                    registry.forge.opened.add(address + " " + token);
                    return registry.forge;
                };
        final var verbs = cli.getSubcommands().get("identity").getSubcommands();
        ((IdentityShowCommand) verbs.get("show").getCommand()).forges = forges;
        ((IdentityKeysCommand) verbs.get("keys").getCommand()).forges = forges;
        ((IdentityCreateCommand) verbs.get("create").getCommand()).forges = forges;
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
