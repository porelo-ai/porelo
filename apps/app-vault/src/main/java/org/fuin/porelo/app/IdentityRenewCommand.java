package org.fuin.porelo.app;

import java.io.IOException;
import java.util.List;
import java.util.Map;
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
 * Gives the person's card a new term with the same key, signed through the agent, kept as an
 * operation first and filed through the organization's store. A card past its end may be renewed
 * too: the key is still the person's, and otherwise an ended card would be a locked door.
 */
@Command(
        name = "renew",
        mixinStandardHelpOptions = true,
        exitCodeOnInvalidInput = 64,
        description = "Renews your card with its key and files it in the organization.")
public class IdentityRenewCommand implements Callable<Integer>, PoreloFactory.ContextAware {

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

    /** Replaced by a test, which has no forge to ask. */
    IdentityOrganization.Opener opener = IdentityOrganization::real;

    @Override
    public void setContext(final PoreloContext context) {
        this.context = context;
    }

    @Override
    public Integer call() throws IOException {
        final PoreloContext machine =
                java.util.Objects.requireNonNull(context, "picocli's factory sets the context");
        try {
            IdentityChange.checkId(id);
            final IdentityOperation again =
                    IdentityChange.kept(machine, id, IdentityOperation.RENEW);
            if (again != null && again.done()) {
                answer(IdentityChange.finished(machine, again), true);
                return 0;
            }
            final IdentityChange change = new IdentityChange(machine);
            final PersonCard card = change.card();
            final IdentityOrganization.Reading organization =
                    IdentityOrganization.required(machine, opener);
            if (again != null) {
                final IdentityChange.Filed filed =
                        change.file(organization, again, base, change.socket());
                answer(filed.operation(), filed.again());
                return 0;
            }
            if (change.revoked(card.key(), organization)) {
                throw IdentityChange.revokedRefusal(
                        "the card's key "
                                + card.fingerprint()
                                + " is revoked; replace it with identity revoke --lost",
                        organization);
            }
            final String socket = change.socket();
            final AgentKey own = IdentityChange.servedKey(card.fingerprint(), socket);
            change.refuseWhatReached(organization, card.login(), id);
            final String text =
                    IdentityChange.signed(
                            card.login(),
                            card.name(),
                            card.email(),
                            own,
                            IdentityChange.today(),
                            null);
            change.replaceWhatReachedNowhere(organization, card.login(), id);
            final IdentityChange.Begun begun =
                    change.begin(
                            id,
                            new IdentityOperation(
                                    "",
                                    IdentityOperation.RENEW,
                                    card.fingerprint(),
                                    card.fingerprint(),
                                    text,
                                    false,
                                    null,
                                    false,
                                    false));
            final IdentityChange.Filed filed =
                    change.file(organization, begun.operation(), base, socket);
            answer(filed.operation(), filed.again());
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

    private void answer(final IdentityOperation operation, final boolean again) {
        final String expires =
                PersonCard.read(operation.card(), "the operation's card").expires().toString();
        final Map<String, @Nullable Object> answer = operation.answer(again);
        answer.remove("predecessor");
        answer.put("expires", expires);
        IdentityChange.print(
                spec,
                json,
                answer,
                List.of(
                        "renew " + operation.id() + ": valid until " + expires,
                        "organization at " + operation.revision()),
                null);
    }
}
