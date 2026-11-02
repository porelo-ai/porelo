package org.fuin.porelo.app;

import java.io.IOException;
import java.util.concurrent.Callable;
import org.fuin.porelo.organization.git.HttpForgeDirectory;
import org.fuin.porelo.vault.CardException;
import org.fuin.porelo.vault.PersonCard;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/** Sends an operation's card again to each place it has not reached, and keeps what each said. */
@Command(
        name = "publish",
        mixinStandardHelpOptions = true,
        exitCodeOnInvalidInput = 64,
        description = "Sends a key change again to each place it has not reached.")
public class IdentityPublishCommand implements Callable<Integer>, PoreloFactory.ContextAware {

    @Option(names = "--id", required = true, paramLabel = "ID", description = "The operation.")
    private String id = "";

    @Option(names = "--json", description = "Answers the operation and its places as JSON.")
    private boolean json;

    @Spec private CommandSpec spec;

    private @Nullable PoreloContext context;

    /** Replaced by a test, which has no forge to ask. */
    RegistryReadCommand.Forges forges =
            (forge, token) ->
                    new HttpForgeDirectory(
                            forge,
                            token,
                            RegistryReadCommand.http(
                                    java.util.Objects.requireNonNull(
                                            context, "picocli's factory sets the context")));

    /** Replaced by a test, which pushes to a repository of its own. */
    IdentityChange.Remotes remotes = IdentityChange.FORGE;

    @Override
    public void setContext(PoreloContext context) {
        this.context = context;
    }

    @Override
    public Integer call() {
        final PoreloContext machine =
                java.util.Objects.requireNonNull(context, "picocli's factory sets the context");
        try {
            final IdentityOperation kept =
                    IdentityChange.finish(machine, IdentityStatusCommand.required(machine, id));
            final String login = PersonCard.read(kept.card(), "the operation's card").login();
            final IdentityOperation operation =
                    kept.publishedTo(
                            new IdentityChange(machine, forges, remotes).publish(kept, login));
            operation.keep(machine);
            IdentityChange.print(spec, json, operation);
            return 0;
        } catch (IdentityChange.Refused ex) {
            return IdentityChange.refuse(spec, json, ex);
        } catch (IOException | CardException ex) {
            return IdentityChange.refuse(
                    spec,
                    json,
                    new IdentityChange.Refused(66, null, String.valueOf(ex.getMessage())));
        }
    }
}
