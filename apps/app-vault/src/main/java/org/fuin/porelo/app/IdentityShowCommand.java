package org.fuin.porelo.app;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import org.fuin.porelo.organization.Organization;
import org.fuin.porelo.organization.OrganizationQuestions;
import org.fuin.porelo.vault.AgentKey;
import org.fuin.porelo.vault.CardException;
import org.fuin.porelo.vault.PersonCard;
import org.fuin.porelo.vault.PersonCardChain;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * Answers who the person on this machine is, from the card kept here: public values only, and what
 * the organization, the agent and the forge say about it.
 *
 * <p>A card is answered whatever its state: the agent, the revocation and the card's own signatures
 * are fields of the answer, so an interface can show the card while it waits for the key.
 */
@Command(
        name = "show",
        mixinStandardHelpOptions = true,
        exitCodeOnInvalidInput = 64,
        description =
                "Shows your card, whether your agent holds its key, and where it is filed and"
                        + " listed.")
public class IdentityShowCommand implements Callable<Integer>, PoreloFactory.ContextAware {

    /** How many days before its end a card is said to be due. */
    private static final long RENEW_DAYS = 7;

    @Option(names = "--json", description = "Answers the card and where it stands as JSON.")
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
        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();
        final IdentityStore store = new IdentityStore(machine);
        final Map<String, @Nullable Object> answer;
        try {
            final Optional<PersonCard> kept = store.card();
            if (kept.isEmpty()) {
                return IdentityFacts.refuse(
                        out,
                        err,
                        json,
                        65,
                        "no-identity",
                        "no card is kept here; make one with porelo identity create",
                        Map.of());
            }
            answer = answer(machine, kept.get());
        } catch (IdentityChange.Refused ex) {
            return IdentityChange.refuse(spec, json, ex);
        } catch (IOException | CardException ex) {
            err.println(
                    "porelo: cannot read " + AnswerText.printable(String.valueOf(ex.getMessage())));
            err.flush();
            return 66;
        }
        if (json) {
            out.println(Json.write(IdentityFacts.printable(answer)));
        } else {
            lines(out, answer);
        }
        out.flush();
        return 0;
    }

    /** The answer, in the order the contract names its fields. */
    private Map<String, @Nullable Object> answer(final PoreloContext machine, final PersonCard card)
            throws IOException, IdentityChange.Refused {
        final IdentityOrganization.Reading organization =
                IdentityOrganization.read(machine, opener);
        final Map<String, @Nullable Object> answer = new LinkedHashMap<>();
        answer.put("login", card.login());
        answer.put("name", card.name());
        answer.put("email", card.email().isEmpty() ? null : card.email());
        answer.put("fingerprint", card.fingerprint());
        answer.put("key", card.key());
        answer.put("created", card.created().toString());
        answer.put("expires", card.expires().toString());
        final long left =
                java.time.temporal.ChronoUnit.DAYS.between(IdentityChange.today(), card.expires());
        answer.put("days_left", left);
        answer.put("renew_due", left <= RENEW_DAYS);
        answer.put(
                "predecessor",
                card.predecessor().isEmpty() ? null : PersonCard.fingerprint(card.predecessor()));
        answer.put("card", card.body() + String.join("", card.signatures()));
        final String agent = agent(machine, card);
        answer.put("agent", agent);
        answer.put("agent_holds_key", agent.equals("holds"));
        answer.put("revoked", IdentityFacts.revoked(machine, organization, card.key()));
        answer.put("card_refused", refused(card));
        answer.put(
                "organization",
                organization == null ? List.of() : List.of(filed(organization, card)));
        answer.put(
                "forge", IdentityFacts.forgeAnswer(organization, card.login(), card.fingerprint()));
        answer.put("pending", pending(machine, organization));
        answer.put("sole_without_backup", sole(organization, card.login()));
        return answer;
    }

    /**
     * Each place of the organization where the person is the one owner or admin and has no backup
     * key, so the interface can offer to make one.
     */
    private static List<Map<String, Object>> sole(
            final IdentityOrganization.@Nullable Reading organization, final String login) {
        final List<Map<String, Object>> sole = new ArrayList<>();
        if (organization != null) {
            for (final Organization.Sole one : organization.answers().getSoleWithoutBackup()) {
                if (one.login().equals(login)) {
                    final String host = one.host();
                    sole.add(
                            host != null
                                    ? Map.of(
                                            "role",
                                            one.role(),
                                            "scope",
                                            Map.of("kind", "host", "name", host))
                                    : Map.of(
                                            "role",
                                            "organization-owner",
                                            "scope",
                                            Map.of(
                                                    "kind",
                                                    "organization",
                                                    "name",
                                                    organization.address())));
                }
            }
        }
        return sole;
    }

    /**
     * Whether the agent holds the card's key: {@code holds}, {@code not-held} or {@code absent}.
     */
    private static String agent(final PoreloContext machine, final PersonCard card) {
        final Optional<List<AgentKey>> served = IdentityFacts.served(machine);
        if (served.isEmpty()) {
            return "absent";
        }
        for (final AgentKey key : served.get()) {
            if (IdentityFacts.fingerprint(key.keyBlob()).equals(card.fingerprint())) {
                return "holds";
            }
        }
        return "not-held";
    }

    /** Why the card does not verify, as {@code card verify} says it, or {@code null}. */
    private static @Nullable String refused(final PersonCard card) {
        try {
            PersonCardChain.check(card, List.of(), card.fingerprint());
            return null;
        } catch (CardException ex) {
            return String.valueOf(ex.getMessage());
        }
    }

    /** Where the card stands in the organization. */
    private static Map<String, @Nullable Object> filed(
            final IdentityOrganization.Reading organization, final PersonCard card) {
        final OrganizationQuestions state = organization.answers();
        final OrganizationQuestions.@Nullable Person person =
                state.getPersons().stream()
                        .filter(each -> each.login().equals(card.login()))
                        .findFirst()
                        .orElse(null);
        final boolean refused = person != null && Organization.REFUSED.equals(person.state());
        final Map<String, @Nullable Object> entry = new LinkedHashMap<>();
        entry.put("address", organization.address());
        if (person == null) {
            entry.put("filed", "absent");
        } else if (refused) {
            entry.put("filed", "refused");
            entry.put("why", person.why());
        } else if (person.state().equals(Organization.TRUSTED)
                && person.fingerprint().equals(card.fingerprint())) {
            entry.put("filed", "done");
        } else {
            entry.put("filed", "pending");
        }
        entry.put("revision", state.revision());
        entry.put("stale", organization.stale());
        // A card with no predecessor replacing an accepted one of another key: a lost key, which
        // only an owner may file.
        final boolean mayFile =
                !(card.predecessor().isEmpty()
                        && person != null
                        && !refused
                        && !person.fingerprint().equals(card.fingerprint()));
        entry.put("may_file", mayFile);
        entry.put("who_may", mayFile ? List.of() : IdentityFacts.owners(organization));
        return entry;
    }

    /**
     * The changes started and not finished, each with the new key's fingerprint. A lost key's card
     * an owner has since accepted waits for nobody.
     */
    private static List<Map<String, @Nullable Object>> pending(
            final PoreloContext machine, final IdentityOrganization.@Nullable Reading organization)
            throws IOException {
        final List<Map<String, @Nullable Object>> pending = new ArrayList<>();
        for (final IdentityOperation each : IdentityOperation.pending(machine)) {
            final boolean accepted =
                    organization != null
                            && each.action().equals(IdentityOperation.REVOKE_LOST)
                            && organization.answers().getPersons().stream()
                                    .anyMatch(
                                            person ->
                                                    person.fingerprint().equals(each.fingerprint())
                                                            && !Organization.REFUSED.equals(
                                                                    person.state()));
            if (!accepted) {
                final Map<String, @Nullable Object> operation = new LinkedHashMap<>();
                operation.put("id", each.id());
                operation.put("action", each.action());
                operation.put("fingerprint", each.fingerprint());
                pending.add(operation);
            }
        }
        return pending;
    }

    /** The answer as lines, a field and its value on each. */
    private static void lines(final PrintWriter out, final Map<String, @Nullable Object> answer) {
        for (final String field :
                List.of(
                        "login",
                        "name",
                        "email",
                        "fingerprint",
                        "key",
                        "created",
                        "expires",
                        "predecessor",
                        "agent",
                        "revoked",
                        "card_refused")) {
            final Object value = answer.get(field);
            if (value != null) {
                out.println(field + " " + AnswerText.printable(value.toString()));
            }
        }
        if (answer.get("organization") instanceof List<?> organizations) {
            for (final Object each : organizations) {
                if (each instanceof Map<?, ?> entry) {
                    out.println(
                            "organization "
                                    + AnswerText.printable(
                                            entry.get("address") + " " + entry.get("filed")));
                }
            }
        }
        if (answer.get("forge") instanceof Map<?, ?> forge) {
            out.println(
                    "forge "
                            + AnswerText.printable(
                                    forge.get("keys_url") + " listed " + forge.get("key_listed")));
        }
        if (answer.get("pending") instanceof List<?> operations) {
            for (final Object each : operations) {
                if (each instanceof Map<?, ?> operation) {
                    out.println(
                            "pending "
                                    + AnswerText.printable(
                                            operation.get("id")
                                                    + " "
                                                    + operation.get("action")
                                                    + " "
                                                    + operation.get("fingerprint")));
                }
            }
        }
    }
}
