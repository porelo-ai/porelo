package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
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
 * kept as an operation first, then filed through the organization's store, and only then kept here
 * as the person's own.
 */
@Command(
        name = "rotate",
        mixinStandardHelpOptions = true,
        exitCodeOnInvalidInput = 64,
        description =
                "Changes your key to one the ssh-agent serves, with a card signed by both keys,"
                        + " and files it in the organization.")
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
            names = "--base",
            paramLabel = "REVISION",
            description = "The organization's revision it is made against.")
    private @Nullable String base;

    @Option(
            names = "--id",
            paramLabel = "ID",
            description = "Names the change; the same id again answers the same change.")
    private @Nullable String id;

    @Option(names = "--json", description = "Answers the change as JSON.")
    private boolean json;

    @Spec private CommandSpec spec;

    private @Nullable PoreloContext context;

    /** Replaced by a test, which runs a stand-in keepassxc-cli and waits less. */
    KeePassXcKey store = KeePassXcKey.real();

    /** Replaced by a test, which has no forge to ask. */
    IdentityOrganization.Opener opener = IdentityOrganization::real;

    @Override
    public void setContext(PoreloContext context) {
        this.context = context;
    }

    @Override
    public Integer call() throws IOException {
        final PoreloContext machine =
                java.util.Objects.requireNonNull(context, "picocli's factory sets the context");
        try {
            IdentityChange.checkId(id);
            final @Nullable String named = IdentityChange.keyChosen(key, keepassxc, entry);
            final IdentityOperation again =
                    IdentityChange.kept(machine, id, IdentityOperation.ROTATE);
            if (again != null && again.done()) {
                answer(IdentityChange.finished(machine, again), true, null);
                return 0;
            }
            final IdentityChange change = new IdentityChange(machine);
            final PersonCard card = change.card();
            final IdentityOrganization.Reading organization =
                    IdentityOrganization.required(machine, opener);
            if (again != null) {
                final IdentityChange.Filed filed =
                        change.file(organization, again, base, change.socket());
                answer(filed.operation(), filed.again(), null);
                return 0;
            }
            // A revoked key hands nothing on: its signature is refused wherever it would count.
            if (change.revoked(card.key(), organization)) {
                throw IdentityChange.revokedRefusal(
                        "the card's key "
                                + card.fingerprint()
                                + " is revoked; replace it with identity revoke --lost",
                        organization);
            }
            final String socket = change.socket();
            // Asked before a key is made, so a KeePassXC entry is never made for a rotation that
            // cannot be signed by the old key, or that another stands in the way of.
            final AgentKey old = IdentityChange.servedKey(card.fingerprint(), socket);
            change.refuseWhatReached(organization, card.login(), id);
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
                            socket,
                            organization);
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
            change.replaceWhatReachedNowhere(organization, card.login(), id);
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
                                    null,
                                    false,
                                    false));
            try {
                final IdentityChange.Filed filed =
                        change.file(organization, begun.operation(), base, socket);
                answer(filed.operation(), filed.again(), made);
                return 0;
            } catch (IdentityChange.Refused ex) {
                throw made == null ? ex : ex.saying(IdentityChange.madeSoFar(made));
            }
        } catch (IdentityChange.Refused ex) {
            return IdentityChange.refuse(spec, json, ex);
        } catch (IOException | CardException ex) {
            return IdentityChange.refuse(
                    spec,
                    json,
                    new IdentityChange.Refused(66, null, String.valueOf(ex.getMessage())));
        }
    }

    private void answer(
            final IdentityOperation operation,
            final boolean again,
            final KeePassXcKey.@Nullable Made made) {
        IdentityChange.print(
                spec,
                json,
                operation.answer(again),
                List.of(
                        operation.action()
                                + " "
                                + operation.id()
                                + ": "
                                + operation.predecessor()
                                + " -> "
                                + operation.fingerprint(),
                        "organization at " + operation.revision()),
                made);
    }
}
