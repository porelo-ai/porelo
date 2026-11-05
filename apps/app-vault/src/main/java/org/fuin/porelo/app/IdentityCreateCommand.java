package org.fuin.porelo.app;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;
import org.fuin.porelo.organization.Organization;
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
 * Makes the person's first card for a key their agent already serves, signed through the agent, and
 * files it through the organization's store when this machine names one. With none it is kept here,
 * for a later run or for founding one. Porelo never sees, reads or writes the private key.
 */
@Command(
        name = "create",
        mixinStandardHelpOptions = true,
        exitCodeOnInvalidInput = 64,
        description =
                "Makes your first card for a key your ssh-agent serves, signed through the agent.")
public class IdentityCreateCommand implements Callable<Integer>, PoreloFactory.ContextAware {

    /** How long a card is valid, from the day it is made. */
    static final int DAYS = 30;

    /** The longest public key file read. */
    private static final long LIMIT = 64 * 1024;

    private static final Pattern FINGERPRINT = Pattern.compile("SHA256:[A-Za-z0-9+/]{43}");

    /** A login as a card takes it: a lower-case name a Linux account and a forge both carry. */
    private static final Pattern LOGIN = Pattern.compile("[a-z][a-z0-9-]{0,31}");

    @Option(names = "--login", required = true, paramLabel = "LOGIN", description = "Your login.")
    private String login = "";

    @Option(
            names = "--name",
            required = true,
            paramLabel = "NAME",
            description = "What you are called, for people to read.")
    private String name = "";

    @Option(names = "--email", paramLabel = "EMAIL", description = "How to reach you.")
    private @Nullable String email;

    @Option(
            names = "--key",
            paramLabel = "FINGERPRINT|PUBFILE",
            description = "The key your agent serves: its SHA256 fingerprint or its .pub file.")
    private @Nullable String key;

    @Option(
            names = "--keepassxc",
            paramLabel = "DATABASE",
            description =
                    "Makes a new key into this KeePassXC database, which hands it to the agent.")
    private @Nullable Path keepassxc;

    @Option(
            names = "--entry",
            paramLabel = "GROUP/NAME",
            description = "The KeePassXC entry the new key goes to.")
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

    @Option(names = "--json", description = "Answers the card and the forge's listing as JSON.")
    private boolean json;

    @Spec private CommandSpec spec;

    private @Nullable PoreloContext context;

    /** Replaced by a test, which runs a stand-in keepassxc-cli and waits less. */
    KeePassXcKey store = KeePassXcKey.real();

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
        final Map<String, @Nullable Object> answer;
        try {
            answer = create(machine);
        } catch (IdentityChange.Refused ex) {
            return IdentityChange.refuse(spec, json, ex);
        } catch (IllegalArgumentException ex) {
            return IdentityChange.refuse(
                    spec,
                    json,
                    new IdentityChange.Refused(64, null, String.valueOf(ex.getMessage())));
        } catch (IOException ex) {
            return plain(spec.commandLine().getErr(), 66, "cannot read " + ex.getMessage());
        }
        if (json) {
            out.println(Json.write(IdentityFacts.printable(answer)));
        } else {
            out.println("login " + AnswerText.printable(login));
            out.println("fingerprint " + answer.get("fingerprint"));
            if (answer.get("entry") instanceof String made) {
                out.println("entry " + AnswerText.printable(made));
            }
            if (answer.get("revision") != null) {
                out.println("filed in the organization at " + answer.get("revision"));
            } else {
                out.println(
                        "card kept at "
                                + AnswerText.printable(
                                        new IdentityStore(machine).cardFile().toString()));
                out.println(
                        "next: name your organization with porelo setup, or found one with porelo"
                                + " org create");
            }
            publicKey(out, answer);
        }
        out.flush();
        return 0;
    }

    private Map<String, @Nullable Object> create(final PoreloContext machine)
            throws IdentityChange.Refused, IOException {
        final String given = key;
        if ((given == null) == (keepassxc == null)) {
            throw new IllegalArgumentException("name the key with --key, or --keepassxc, not both");
        }
        if (entry != null && keepassxc == null) {
            throw new IllegalArgumentException("--entry goes with --keepassxc");
        }
        fields();
        IdentityChange.checkId(id);
        final IdentityOperation again = IdentityChange.kept(machine, id, IdentityOperation.CREATE);
        if (again != null && again.done()) {
            return answer(
                    machine,
                    IdentityChange.finished(machine, again),
                    true,
                    IdentityOrganization.read(machine, opener),
                    null);
        }
        // A kept operation is filed somewhere; only a new first card goes on with nothing to file
        // in.
        final IdentityOrganization.Reading organization =
                again != null
                        ? IdentityOrganization.requiredIfAny(machine, opener)
                        : IdentityOrganization.requiredUnlessEmpty(machine, opener);
        final IdentityChange change = new IdentityChange(machine);
        if (again != null) {
            final IdentityChange.Filed filed =
                    change.file(
                            java.util.Objects.requireNonNull(
                                    organization, "an operation is filed in one"),
                            again,
                            base,
                            change.socket());
            return answer(machine, filed.operation(), filed.again(), organization, null);
        }
        final IdentityStore store = new IdentityStore(machine);
        final IdentityOperation asked = askedAgain(store, change, given, organization);
        if (asked != null) {
            return answer(machine, asked, true, organization, null);
        }
        final Optional<PersonCard> held = held(machine, store, given, organization);
        if (organization != null) {
            change.refuseWhatReached(organization, login, id);
        }
        plan();
        final KeePassXcKey.Made made = given == null ? made(machine) : null;
        final String fingerprint =
                made != null
                        ? made.fingerprint()
                        : fingerprint(java.util.Objects.requireNonNull(given, "--key is given"));
        final AgentKey own = served(machine, fingerprint);
        final String line = PersonCard.keyLine(own);
        if (change.revoked(line, organization)) {
            throw IdentityChange.revokedRefusal(
                    "the key "
                            + fingerprint
                            + " is revoked; an organization owner may name another",
                    organization);
        }
        final Optional<String> another = IdentityFacts.cardOfAnother(organization, line, login);
        if (another.isPresent()) {
            final Map<String, @Nullable Object> more = new LinkedHashMap<>();
            more.put("login", another.get());
            throw new IdentityChange.Refused(
                    65,
                    "key-on-another-card",
                    "the organization holds the key "
                            + fingerprint
                            + " on the card of "
                            + another.get(),
                    more);
        }
        final String card;
        if (held.isPresent()) {
            card = held.get().body() + String.join("", held.get().signatures());
        } else {
            try {
                card =
                        IdentityChange.signed(
                                login,
                                name,
                                email == null ? "" : email,
                                own,
                                IdentityChange.today(),
                                null);
            } catch (CardException ex) {
                throw new IllegalArgumentException(ex.getMessage(), ex);
            }
        }
        if (organization == null) {
            try {
                store.keep(card);
                if (id != null) {
                    store.madeUnder(id);
                }
            } catch (IOException ex) {
                throw new IdentityChange.Refused(
                        70, "failed", "the card could not be kept: " + ex.getMessage());
            }
            final IdentityOperation unfiled =
                    new IdentityOperation(
                            id == null ? "" : id,
                            IdentityOperation.CREATE,
                            fingerprint,
                            "",
                            card,
                            false,
                            null,
                            true,
                            false);
            return answer(machine, unfiled, false, null, made);
        }
        change.replaceWhatReachedNowhere(organization, login, id);
        final IdentityChange.Begun begun =
                change.begin(
                        id,
                        new IdentityOperation(
                                "",
                                IdentityOperation.CREATE,
                                fingerprint,
                                "",
                                card,
                                false,
                                null,
                                false,
                                false));
        try {
            final IdentityChange.Filed filed =
                    change.file(organization, begun.operation(), base, change.socket());
            return answer(machine, filed.operation(), filed.again(), organization, made);
        } catch (IdentityChange.Refused ex) {
            throw made == null ? ex : ex.saying(IdentityChange.madeSoFar(made));
        }
    }

    /**
     * The first card this id already made: kept here with nothing to file in, it has no record to
     * find it by, and the organization may have taken it since, as its founder's.
     *
     * @return What to answer, or {@code null} when this is not that card.
     */
    private @Nullable IdentityOperation askedAgain(
            final IdentityStore store,
            final IdentityChange change,
            final @Nullable String given,
            final IdentityOrganization.@Nullable Reading organization)
            throws IdentityChange.Refused, IOException {
        if (id == null || given == null || !store.holdsCard() || !store.madeUnderId(id)) {
            return null;
        }
        final PersonCard held = store.card().orElse(null);
        if (held == null
                || !held.login().equals(login)
                || !held.name().equals(name)
                || !held.email().equals(email == null ? "" : email)
                || !held.predecessor().isEmpty()
                || !held.fingerprint().equals(fingerprint(given))
                || change.revoked(held.key(), organization)) {
            return null;
        }
        final String card = held.body() + String.join("", held.signatures());
        if (organization != null
                && (organization.answers().getCard(login).filter(card::equals).isEmpty()
                        || organization.answers().getPersons().stream()
                                .anyMatch(
                                        person ->
                                                person.login().equals(login)
                                                        && Organization.REFUSED.equals(
                                                                person.state())))) {
            return null;
        }
        return new IdentityOperation(
                id,
                IdentityOperation.CREATE,
                held.fingerprint(),
                "",
                card,
                false,
                organization == null ? null : organization.answers().revision(),
                true,
                false);
    }

    /**
     * The card kept here that this run files: one of this login and key the organization holds
     * nothing of. Any other card kept is the person's card, which a key changes with {@code
     * rotate}.
     */
    private Optional<PersonCard> held(
            final PoreloContext machine,
            final IdentityStore store,
            final @Nullable String given,
            final IdentityOrganization.@Nullable Reading organization)
            throws IdentityChange.Refused, IOException {
        if (!store.holdsCard()) {
            return Optional.empty();
        }
        final Optional<PersonCard> held = store.card();
        final boolean toFile =
                organization != null
                        && given != null
                        && held.isPresent()
                        && held.get().login().equals(login)
                        && held.get().predecessor().isEmpty()
                        && held.get().fingerprint().equals(fingerprint(given))
                        && organization.answers().getCard(login).isEmpty();
        if (!toFile) {
            throw new IdentityChange.Refused(
                    65,
                    "identity-exists",
                    "a card is kept here already; change its key with porelo identity rotate");
        }
        return held;
    }

    private Map<String, @Nullable Object> answer(
            final PoreloContext machine,
            final IdentityOperation operation,
            final boolean again,
            final IdentityOrganization.@Nullable Reading organization,
            final KeePassXcKey.@Nullable Made made)
            throws IdentityChange.Refused, IOException {
        final PersonCard card;
        try {
            card = PersonCard.read(operation.card(), "the card");
        } catch (CardException ex) {
            throw new IllegalArgumentException(ex.getMessage(), ex);
        }
        final Map<String, @Nullable Object> answer = new LinkedHashMap<>();
        answer.put("revision", operation.revision());
        answer.put("id", operation.id().isEmpty() ? null : operation.id());
        answer.put("again", again);
        answer.put("login", card.login());
        answer.put("fingerprint", card.fingerprint());
        answer.put("key", card.key());
        answer.put("card", operation.card());
        answer.put("agent", "holds");
        if (made != null) {
            answer.put("entry", made.entry());
        }
        answer.put(
                "forge", IdentityFacts.forgeAnswer(organization, card.login(), card.fingerprint()));
        answer.put("next", next(machine, organization));
        return answer;
    }

    /** Says what is about to happen, before a password is asked or anything is made. */
    private void plan() {
        if (json) {
            return;
        }
        final PrintWriter out = spec.commandLine().getOut();
        out.println(
                keepassxc == null
                        ? "This makes your card with the one key your agent serves: no key is made,"
                                + " and no file is written for it."
                        : "This makes one Ed25519 key in "
                                + AnswerText.printable(String.valueOf(keepassxc))
                                + ", which hands it to your agent: no file is written for it.");
        out.flush();
    }

    /** The public half and where it goes, unless the forge is known to hold it already. */
    private static void publicKey(
            final PrintWriter out, final Map<String, @Nullable Object> answer) {
        out.println("key " + AnswerText.printable(String.valueOf(answer.get("key"))));
        if (!(answer.get("forge") instanceof Map<?, ?> forge
                && Boolean.TRUE.equals(forge.get("key_listed")))) {
            out.println(
                    "add this key to your forge's account: at GitHub,"
                            + " https://github.com/settings/ssh/new, as an authentication and as a"
                            + " signing key");
        }
    }

    /** What the person does next: nothing once it is filed, or name or found the organization. */
    private static List<String> next(
            final PoreloContext machine, final IdentityOrganization.@Nullable Reading organization)
            throws IdentityChange.Refused, IOException {
        if (organization != null) {
            return List.of();
        }
        return List.of(
                IdentityOrganization.repository(machine).isPresent() ? "org create" : "setup");
    }

    /** Makes a new key into the KeePassXC database and waits for the agent to serve it. */
    private KeePassXcKey.Made made(final PoreloContext machine) throws IdentityChange.Refused {
        store.usable(machine);
        final String socket = machine.environment().apply(IdentityChange.AGENT);
        if (socket == null || socket.isEmpty()) {
            throw new IdentityChange.Refused(
                    65,
                    "key-not-in-agent",
                    "no ssh-agent is reachable (SSH_AUTH_SOCK is not set), so KeePassXC could"
                            + " not give it the key");
        }
        return store.make(
                machine,
                java.util.Objects.requireNonNull(keepassxc, "--keepassxc is given"),
                entry,
                login,
                socket,
                IdentityOperation.CREATE,
                id);
    }

    /** Refuses a login, name or email a card does not take, before the agent is asked. */
    private void fields() {
        if (!LOGIN.matcher(login).matches()) {
            throw new IllegalArgumentException(
                    "'"
                            + AnswerText.printable(login)
                            + "' is not a login: a lower-case letter, then up to 31 lower-case"
                            + " letters, digits or dashes, since on a host it is an account's"
                            + " name. The forge reads a login in any case, so yours in lower case"
                            + " is the same one");
        }
        // Porelo's own accounts on a host carry these names; a person taking one would be them.
        if (login.equals("porelo") || login.startsWith("porelo-")) {
            throw new IllegalArgumentException("'" + login + "' is a name Porelo keeps for itself");
        }
        if (name.isBlank()) {
            throw new IllegalArgumentException("--name is empty");
        }
        if (name.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("--name holds a control character");
        }
        if (email != null && email.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("--email holds a control character");
        }
    }

    /** The fingerprint the key is named by, or that of the public key file named. */
    private static String fingerprint(final String given)
            throws IdentityChange.Refused, IOException {
        if (FINGERPRINT.matcher(given).matches()) {
            return given;
        }
        final Path file = Path.of(given);
        if (!Files.isRegularFile(file) || Files.size(file) > LIMIT) {
            throw new IOException(
                    "'"
                            + AnswerText.printable(given)
                            + "': --key is neither a SHA256 fingerprint nor a public key file");
        }
        final String line =
                Files.readString(file, StandardCharsets.UTF_8)
                        .lines()
                        .map(String::strip)
                        .filter(each -> !each.isEmpty() && !each.startsWith("#"))
                        .findFirst()
                        .orElse("");
        final String[] words = line.split("\\s+");
        final byte[] blob;
        try {
            blob = words.length < 2 ? new byte[0] : Base64.getDecoder().decode(words[1]);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "'" + AnswerText.printable(given) + "' holds no public key", ex);
        }
        if (blob.length == 0 || !IdentityFacts.type(blob).equals(words[0])) {
            throw new IllegalArgumentException(
                    "'" + AnswerText.printable(given) + "' holds no public key");
        }
        if (!words[0].equals("ssh-ed25519")) {
            throw new IdentityChange.Refused(
                    65,
                    "key-not-ed25519",
                    "the key in '"
                            + AnswerText.printable(given)
                            + "' is "
                            + AnswerText.printable(words[0])
                            + "; a card takes an Ed25519 key");
        }
        return IdentityFacts.fingerprint(blob);
    }

    /** The agent's key of that fingerprint, which must be one a card takes. */
    private static AgentKey served(final PoreloContext machine, final String fingerprint)
            throws IdentityChange.Refused {
        final Optional<List<AgentKey>> served = IdentityFacts.served(machine);
        if (served.isEmpty()) {
            throw new IdentityChange.Refused(
                    69,
                    "no-key",
                    "no ssh-agent answers at SSH_AUTH_SOCK, so it cannot sign with "
                            + fingerprint
                            + "; start one or unlock KeePassXC");
        }
        for (final AgentKey each : served.get()) {
            if (IdentityFacts.fingerprint(each.keyBlob()).equals(fingerprint)) {
                if (!IdentityFacts.ed25519(each)) {
                    throw new IdentityChange.Refused(
                            65,
                            "key-not-ed25519",
                            "the key "
                                    + fingerprint
                                    + " is "
                                    + AnswerText.printable(IdentityFacts.type(each.keyBlob()))
                                    + "; a card takes an Ed25519 key");
                }
                return each;
            }
        }
        throw new IdentityChange.Refused(
                69,
                "no-key",
                "the ssh-agent does not serve the key "
                        + fingerprint
                        + "; unlock KeePassXC or load the entry, then ask again");
    }

    private static int plain(final PrintWriter err, final int code, final String message) {
        err.println("porelo: " + AnswerText.printable(message));
        err.flush();
        return code;
    }
}
