package org.fuin.porelo.app;

import java.io.PrintWriter;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Says which credential a destination would use and, for git, whether the destination accepts it.
 *
 * <p>It said only what would be used, and a key the forge refused was found at the next start,
 * folded into the gate's failure (found on 2026-10-13). For git it now reads the destination as a
 * start does, so a person can check first.
 */
@Command(
        name = "check",
        mixinStandardHelpOptions = true,
        description =
                "Says which credential a URL would use and, for git, reads it with that credential.")
public class CredentialsCheckCommand implements Callable<Integer>, PoreloFactory.ContextAware {

    @Parameters(index = "0", paramLabel = "<url>", description = "Where something would connect.")
    private String url;

    @Option(
            names = "--purpose",
            paramLabel = "<what>",
            description = "What for. Default: ${DEFAULT-VALUE}")
    private String purpose = "git";

    @Option(
            names = "--no-connect",
            description = "Only says which credential would be used, and does not connect.")
    private boolean noConnect;

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
        final CredentialDeclarations.Check check =
                new CredentialDeclarations(context).check(url, purpose);
        if (check.outcome() == CredentialDeclarations.Outcome.READY
                || check.outcome() == CredentialDeclarations.Outcome.NOT_NEEDED) {
            out.println(
                    check.outcome().name().toLowerCase(java.util.Locale.ROOT)
                            + "      "
                            + check.detail());
            out.flush();
            return read(out, err);
        }
        err.println(
                check.outcome().name().toLowerCase(java.util.Locale.ROOT)
                        + "      "
                        + check.detail());
        final org.fuin.porelo.core.credential.Credential credential = check.credential();
        final java.util.List<String> store =
                credential == null || check.storeCommand().isEmpty()
                        ? null
                        : store(
                                credential.id(),
                                credential.kind(),
                                Offer.atTerminal()
                                        ? new Offer(Offer.Asker.terminal(), false, false, err)
                                        : Offer.NOBODY);
        if (store != null) {
            // The command's own code, on this terminal; then the declaration is checked again,
            // never assumed.
            final java.util.List<String> command =
                    new java.util.ArrayList<>(java.util.List.of(PoreloBinary.path()));
            command.addAll(store);
            context.exec().applyAsInt(command);
            final CredentialDeclarations.Check again =
                    new CredentialDeclarations(context).check(url, purpose);
            if (again.outcome() == CredentialDeclarations.Outcome.READY) {
                out.println("ready      " + again.detail());
                out.flush();
                return read(out, err);
            }
        }
        if (!check.storeCommand().isEmpty()) {
            err.println("           " + check.storeCommand());
        }
        err.flush();
        return 70;
    }

    /**
     * Reads a git destination with the credential it would be given, as the gate does.
     *
     * @param out Where a read is said.
     * @param err Where why not is said.
     * @return 0 when it was read or there is nothing to read, 70 when it could not be.
     */
    private int read(final PrintWriter out, final PrintWriter err) {
        if (noConnect || !"git".equals(purpose) || !UpstreamProbe.remote(url)) {
            return 0;
        }
        final UpstreamProbe.Answer answer =
                UpstreamProbe.probe(context, new VaultGitCredentials(context, "check"), url);
        if (answer == null) {
            out.println("read       " + url);
            out.flush();
            return 0;
        }
        err.println(
                answer.cause().name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ')
                        + "  "
                        + answer.detail());
        if (!answer.fix().isEmpty()) {
            err.println("           " + answer.fix());
        }
        err.flush();
        return 70;
    }

    /**
     * Asks how to store a missing secret, as 'porelo vault put' stores it: an ssh key from a path
     * the person gives, a token or another key typed into that command without echo.
     *
     * @param id The vault entry.
     * @param kind What it holds.
     * @param offer Who is asked.
     * @return The arguments of 'porelo vault put', or {@code null} when nobody chose to store it.
     */
    static java.util.@org.jspecify.annotations.Nullable List<String> store(
            final String id,
            final org.fuin.porelo.core.credential.Credential.Kind kind,
            final Offer offer) {
        if (offer == Offer.NOBODY) {
            return null;
        }
        if (kind == org.fuin.porelo.core.credential.Credential.Kind.SSH_KEY) {
            final String path =
                    offer.text(
                            "'" + id + "' has no key in the vault",
                            "Path of the private key to store:",
                            "porelo vault put " + id + " --from-file <path>");
            return path == null ? null : java.util.List.of("vault", "put", id, "--from-file", path);
        }
        if (!offer.confirm(
                "'" + id + "' has no value in the vault",
                "Store it now, as 'porelo vault put' does?",
                "",
                false)) {
            return null;
        }
        return kind == org.fuin.porelo.core.credential.Credential.Kind.TOKEN
                ? java.util.List.of("vault", "put", id, "--type", "token")
                : java.util.List.of("vault", "put", id);
    }
}
