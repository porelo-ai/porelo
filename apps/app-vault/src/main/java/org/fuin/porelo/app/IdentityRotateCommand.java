package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Callable;
import org.fuin.porelo.organization.git.HttpForgeDirectory;
import org.fuin.porelo.vault.AgentKey;
import org.fuin.porelo.vault.CardException;
import org.fuin.porelo.vault.PersonCard;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * Changes the person's key: a new card signed by the new key and the old, both through the agent,
 * kept as an operation first, then filed and pushed to each registry and named for each project and
 * host that must take it.
 */
@Command(
        name = "rotate",
        mixinStandardHelpOptions = true,
        exitCodeOnInvalidInput = 64,
        description =
                "Changes your key to one the ssh-agent serves, with a card signed by both keys,"
                        + " and publishes it.")
public class IdentityRotateCommand implements Callable<Integer>, PoreloFactory.ContextAware {

    @Option(
            names = "--key",
            paramLabel = "FINGERPRINT|PUBFILE",
            description = "The new key, as the ssh-agent serves it.")
    private @Nullable String key;

    @Option(
            names = "--keepassxc",
            paramLabel = "DATABASE",
            description =
                    "Makes the new key into this KeePassXC database, which hands it to the"
                            + " agent.")
    private @Nullable Path keepassxc;

    @Option(
            names = "--entry",
            paramLabel = "GROUP/NAME",
            description = "The KeePassXC entry, with --keepassxc.")
    private @Nullable String entry;

    @Option(
            names = "--id",
            paramLabel = "ID",
            description = "The operation's id; the same id again answers the same operation.")
    private @Nullable String id;

    @Option(names = "--json", description = "Answers the operation and its places as JSON.")
    private boolean json;

    @Spec private CommandSpec spec;

    private @Nullable PoreloContext context;

    /** Replaced by a test, which runs a stand-in keepassxc-cli and waits less. */
    KeePassXcKey store = KeePassXcKey.real();

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
            IdentityChange.checkId(id);
            final @Nullable String named = IdentityChange.keyChosen(key, keepassxc, entry);
            final IdentityOperation again =
                    IdentityChange.kept(machine, id, IdentityOperation.ROTATE);
            if (again != null) {
                IdentityChange.print(spec, json, IdentityChange.finish(machine, again));
                return 0;
            }
            final IdentityChange change = new IdentityChange(machine, forges, remotes);
            final PersonCard card = change.card();
            // A revoked key hands nothing on: its signature is refused wherever it would count.
            if (change.revoked(card.key())) {
                throw new IdentityChange.Refused(
                        65,
                        "revoked",
                        "the card's key "
                                + card.fingerprint()
                                + " is revoked; replace it with identity revoke --lost",
                        Map.of("who_may", IdentityChange.answers(change.owners())));
            }
            final String socket = change.socket();
            // Asked before a key is made, so a KeePassXC entry is never made for a rotation that
            // cannot be signed by the old key.
            final AgentKey old = IdentityChange.servedKey(card.fingerprint(), socket);
            final KeePassXcKey.Made made =
                    named != null
                            ? null
                            : store.make(
                                    machine,
                                    java.util.Objects.requireNonNull(
                                            keepassxc, "either --key or --keepassxc is given"),
                                    entry,
                                    card.login(),
                                    socket);
            final AgentKey successor =
                    change.successor(
                            made == null
                                    ? java.util.Objects.requireNonNull(named, "--key is given")
                                    : made.fingerprint(),
                            card.login(),
                            socket);
            final String fingerprint = PersonCard.fingerprint(PersonCard.keyLine(successor));
            if (fingerprint.equals(card.fingerprint())) {
                throw new IdentityChange.Refused(
                        64, null, "the key " + fingerprint + " is the card's own key already");
            }
            final String text =
                    IdentityChange.signed(
                            card.login(),
                            card.name(),
                            card.email(),
                            successor,
                            IdentityChange.today(),
                            old);
            final IdentityChange.Begun begun =
                    change.begin(
                            id,
                            new IdentityOperation(
                                    "",
                                    IdentityOperation.ROTATE,
                                    fingerprint,
                                    card.fingerprint(),
                                    text,
                                    false,
                                    change.places(
                                            card.login(), IdentityOperation.ROTATE, "pending"),
                                    false));
            IdentityOperation operation = begun.operation();
            if (begun.fresh()) {
                operation = operation.publishedTo(change.publish(operation, card.login()));
                operation.keep(machine);
            }
            IdentityChange.print(spec, json, operation, made);
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
