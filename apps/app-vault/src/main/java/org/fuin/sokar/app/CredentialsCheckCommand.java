package org.fuin.sokar.app;

import java.io.PrintWriter;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/** Says which credential a destination would use, without connecting to it. */
@Command(name = "check",
        mixinStandardHelpOptions = true,
        description = "Says which credential a URL would use, and whether it would work.")
public class CredentialsCheckCommand implements Callable<Integer>, SokarFactory.ContextAware {

    @Parameters(index = "0", paramLabel = "<url>", description = "Where something would connect.")
    private String url;

    @Option(names = "--purpose", paramLabel = "<what>",
            description = "What for. Default: ${DEFAULT-VALUE}")
    private String purpose = "git";

    @Spec
    private CommandSpec spec;

    private SokarContext context = SokarContext.real();

    @Override
    public void setContext(final SokarContext context) {
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
            out.println(check.outcome().name().toLowerCase(java.util.Locale.ROOT)
                    + "      " + check.detail());
            out.flush();
            return 0;
        }
        err.println(check.outcome().name().toLowerCase(java.util.Locale.ROOT)
                + "      " + check.detail());
        final org.fuin.sokar.core.credential.Credential credential = check.credential();
        final java.util.List<String> store = credential == null || check.storeCommand().isEmpty() ? null
                : store(credential.id(), credential.kind(), Offer.atTerminal()
                        ? new Offer(Offer.Asker.terminal(), false, false, err) : Offer.NOBODY);
        if (store != null) {
            // The command's own code, on this terminal; then the declaration is checked again, never assumed.
            final java.util.List<String> command = new java.util.ArrayList<>(java.util.List.of(SokarBinary.path()));
            command.addAll(store);
            context.exec().applyAsInt(command);
            final CredentialDeclarations.Check again = new CredentialDeclarations(context).check(url, purpose);
            if (again.outcome() == CredentialDeclarations.Outcome.READY) {
                out.println("ready      " + again.detail());
                out.flush();
                return 0;
            }
        }
        if (!check.storeCommand().isEmpty()) {
            err.println("           " + check.storeCommand());
        }
        err.flush();
        return 70;
    }

    /**
     * Asks how to store a missing secret, as 'sokar vault put' stores it: an ssh key from a path the person gives, a token
     * or another key typed into that command without echo.
     *
     * @param id The vault entry.
     * @param kind What it holds.
     * @param offer Who is asked.
     * @return The arguments of 'sokar vault put', or {@code null} when nobody chose to store it.
     */
    static java.util.@org.jspecify.annotations.Nullable List<String> store(final String id,
            final org.fuin.sokar.core.credential.Credential.Kind kind, final Offer offer) {
        if (offer == Offer.NOBODY) {
            return null;
        }
        if (kind == org.fuin.sokar.core.credential.Credential.Kind.SSH_KEY) {
            final String path = offer.text("'" + id + "' has no key in the vault", "Path of the private key to store:",
                    "sokar vault put " + id + " --from-file <path>");
            return path == null ? null : java.util.List.of("vault", "put", id, "--from-file", path);
        }
        if (!offer.confirm("'" + id + "' has no value in the vault", "Store it now, as 'sokar vault put' does?", "",
                false)) {
            return null;
        }
        return kind == org.fuin.sokar.core.credential.Credential.Kind.TOKEN
                ? java.util.List.of("vault", "put", id, "--type", "token") : java.util.List.of("vault", "put", id);
    }
}
