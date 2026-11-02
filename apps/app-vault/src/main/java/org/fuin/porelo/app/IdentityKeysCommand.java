package org.fuin.porelo.app;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import org.fuin.porelo.organization.IdentitySource;
import org.fuin.porelo.vault.AgentKey;
import org.fuin.porelo.vault.CardException;
import org.fuin.porelo.vault.PersonCard;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * Lists the keys the person's agent serves, each with whether {@code identity create --key} would
 * take it, so a person picks the key their card names. Only public keys are asked for; the private
 * key stays the agent's.
 */
@Command(
        name = "keys",
        mixinStandardHelpOptions = true,
        exitCodeOnInvalidInput = 64,
        description = "Lists the keys your agent serves and whether a card can name each.")
public class IdentityKeysCommand implements Callable<Integer>, PoreloFactory.ContextAware {

    @Option(names = "--json", description = "Answers the keys as JSON.")
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
        final Optional<List<AgentKey>> served = IdentityFacts.served(machine);
        if (served.isEmpty()) {
            return IdentityFacts.refuse(
                    out,
                    err,
                    json,
                    69,
                    "agent-absent",
                    "no ssh-agent answers at SSH_AUTH_SOCK; start one or unlock KeePassXC",
                    Map.of());
        }
        final List<Map<String, @Nullable Object>> keys = new ArrayList<>();
        final IdentityOrganization.Reading organization;
        try {
            organization = IdentityOrganization.read(machine, opener);
            final @Nullable String login = login(machine);
            // One list for every key: the forge is asked once, not once per key.
            final Optional<IdentitySource.Keys> listed =
                    login == null
                            ? Optional.empty()
                            : IdentityFacts.keys(organization, login, null);
            for (final AgentKey key : served.get()) {
                keys.add(entry(machine, organization, login, listed, key));
            }
        } catch (IdentityChange.Refused ex) {
            return IdentityChange.refuse(spec, json, ex);
        } catch (IOException ex) {
            err.println(
                    "porelo: cannot read " + AnswerText.printable(String.valueOf(ex.getMessage())));
            err.flush();
            return 66;
        }
        if (json) {
            final Map<String, @Nullable Object> answer = new LinkedHashMap<>();
            answer.put("revision", organization == null ? null : organization.revision());
            answer.put("stale", organization != null && organization.stale());
            answer.put("keys", keys);
            out.println(Json.write(AnswerText.printable(answer)));
        } else {
            for (final Map<String, @Nullable Object> key : keys) {
                out.println(
                        AnswerText.printable(
                                key.get("fingerprint")
                                        + " "
                                        + key.get("comment")
                                        + (Boolean.TRUE.equals(key.get("usable"))
                                                ? " usable"
                                                : " " + key.get("reason"))));
            }
        }
        out.flush();
        return 0;
    }

    /** The login of the card kept here, whose key may be on it; {@code null} without one. */
    private static @Nullable String login(final PoreloContext machine) throws IOException {
        try {
            // A directory that is a link or another's is refused, never read as no card.
            return new IdentityStore(machine).card().map(PersonCard::login).orElse(null);
        } catch (CardException ex) {
            // A card that cannot be read names no login: every card there is is another's.
            return null;
        }
    }

    private static Map<String, @Nullable Object> entry(
            final PoreloContext machine,
            final IdentityOrganization.@Nullable Reading organization,
            final @Nullable String login,
            final Optional<IdentitySource.Keys> listed,
            final AgentKey key)
            throws IOException {
        final String fingerprint = IdentityFacts.fingerprint(key.keyBlob());
        final Map<String, @Nullable Object> entry = new LinkedHashMap<>();
        entry.put("fingerprint", fingerprint);
        entry.put("key", IdentityFacts.keyLine(key));
        entry.put("comment", key.comment());
        if (!IdentityFacts.ed25519(key)) {
            entry.put("usable", false);
            entry.put("reason", "key-not-ed25519");
            entry.put("key_listed", null);
            return entry;
        }
        final String line = PersonCard.keyLine(key);
        final Optional<String> another = IdentityFacts.cardOfAnother(organization, line, login);
        if (IdentityFacts.revoked(machine, organization, line)) {
            entry.put("usable", false);
            entry.put("reason", "revoked");
        } else if (another.isPresent()) {
            entry.put("usable", false);
            entry.put("reason", "key-on-another-card");
            entry.put("login", another.get());
        } else {
            entry.put("usable", true);
            entry.put("reason", null);
        }
        entry.put("key_listed", IdentityFacts.listed(listed, fingerprint));
        return entry;
    }
}
