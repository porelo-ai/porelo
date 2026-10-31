package org.fuin.porelo.app;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;
import org.fuin.porelo.vault.AgentKey;
import org.fuin.porelo.vault.CardException;
import org.fuin.porelo.vault.HttpForgeDirectory;
import org.fuin.porelo.vault.PersonCard;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * Makes the person's first card for a key their agent already serves, signed through the agent, and
 * keeps it here for filing. Porelo never sees, reads or writes the private key.
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

    @Option(names = "--json", description = "Answers the card and the forge's listing as JSON.")
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

    /** A refusal with its typed reason and the further fields of its answer. */
    private static final class Refused extends Exception {
        private static final long serialVersionUID = 1L;

        private final int code;

        private final String reason;

        private final transient Map<String, @Nullable Object> more;

        Refused(final int code, final String reason, final String words) {
            this(code, reason, words, Map.of());
        }

        Refused(
                final int code,
                final String reason,
                final String words,
                final Map<String, @Nullable Object> more) {
            super(words);
            this.code = code;
            this.reason = reason;
            this.more = more;
        }
    }

    @Override
    public void setContext(final PoreloContext context) {
        this.context = context;
    }

    @Override
    public Integer call() {
        final PoreloContext machine =
                java.util.Objects.requireNonNull(context, "picocli's factory sets the context");
        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();
        final Map<String, @Nullable Object> answer;
        try {
            answer = create(machine);
        } catch (Refused ex) {
            return IdentityFacts.refuse(
                    out, err, json, ex.code, ex.reason, String.valueOf(ex.getMessage()), ex.more);
        } catch (IllegalArgumentException ex) {
            return plain(err, 64, String.valueOf(ex.getMessage()));
        } catch (IOException ex) {
            return plain(err, 66, "cannot read " + ex.getMessage());
        } catch (java.io.UncheckedIOException ex) {
            return plain(err, 70, "cannot keep the card: " + ex.getMessage());
        }
        if (json) {
            out.println(Json.write(IdentityFacts.printable(answer)));
        } else {
            out.println("login " + RegistryVerifyCommand.printable(login));
            out.println("fingerprint " + answer.get("fingerprint"));
            out.println(
                    "card kept at "
                            + RegistryVerifyCommand.printable(
                                    new IdentityStore(machine).cardFile().toString()));
            out.println(
                    "next: file it with porelo registry file card CHECKOUT --card "
                            + RegistryVerifyCommand.printable(
                                    new IdentityStore(machine).cardFile().toString()));
        }
        out.flush();
        return 0;
    }

    private Map<String, @Nullable Object> create(final PoreloContext machine)
            throws Refused, IOException {
        final String given = key;
        if ((given == null) == (keepassxc == null)) {
            throw new IllegalArgumentException("name the key with --key, or --keepassxc, not both");
        }
        if (entry != null && keepassxc == null) {
            throw new IllegalArgumentException("--entry goes with --keepassxc");
        }
        fields();
        final IdentityStore store = new IdentityStore(machine);
        if (store.holdsCard()) {
            throw new Refused(
                    65,
                    "identity-exists",
                    "a card is kept here already; change its key with porelo identity rotate");
        }
        final KeePassXcKey.Made made = given == null ? made(machine) : null;
        final String fingerprint =
                made != null
                        ? made.fingerprint()
                        : fingerprint(java.util.Objects.requireNonNull(given, "--key is given"));
        final AgentKey own = served(machine, fingerprint);
        final List<IdentityFacts.Registry> registries = IdentityFacts.registries(machine);
        final String line = PersonCard.keyLine(own);
        if (IdentityFacts.revoked(machine, registries, line)) {
            final Map<String, @Nullable Object> more = new LinkedHashMap<>();
            more.put("who_may", IdentityFacts.owners(registries));
            throw new Refused(
                    65,
                    "revoked",
                    "the key " + fingerprint + " is revoked; a registry owner may name another",
                    more);
        }
        final Optional<String> another =
                IdentityFacts.cardOfAnother(registries, fingerprint, login);
        if (another.isPresent()) {
            final Map<String, @Nullable Object> more = new LinkedHashMap<>();
            more.put("login", another.get());
            throw new Refused(
                    65,
                    "key-on-another-card",
                    "a registry this machine reads holds the key "
                            + fingerprint
                            + " on the card of "
                            + another.get(),
                    more);
        }
        final String card;
        try {
            card =
                    IdentityChange.signed(
                            login,
                            name,
                            email == null ? "" : email,
                            own,
                            LocalDate.now(ZoneOffset.UTC),
                            null);
        } catch (IdentityChange.Refused ex) {
            throw new Refused(ex.code(), String.valueOf(ex.reason()), ex.words(), Map.of());
        } catch (CardException ex) {
            throw new IllegalArgumentException(ex.getMessage(), ex);
        }
        try {
            store.keep(card);
        } catch (IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
        final Map<String, @Nullable Object> answer = new LinkedHashMap<>();
        answer.put("login", login);
        answer.put("fingerprint", fingerprint);
        answer.put("key", line);
        answer.put("card", card);
        answer.put("agent", "holds");
        if (made != null) {
            answer.put("entry", made.entry());
        }
        answer.put("forge", IdentityFacts.forgeAnswer(forges, registries, login, fingerprint));
        answer.put("next", List.of("file-card"));
        return answer;
    }

    /** Makes a new key into the KeePassXC database and waits for the agent to serve it. */
    private KeePassXcKey.Made made(final PoreloContext machine) throws Refused {
        final String socket = machine.environment().apply(IdentityChange.AGENT);
        try {
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
                    socket);
        } catch (IdentityChange.Refused ex) {
            final Map<String, @Nullable Object> typed = ex.answer();
            if (typed == null) {
                throw new IllegalArgumentException(ex.words(), ex);
            }
            final Map<String, @Nullable Object> more = new LinkedHashMap<>(typed);
            more.remove("reason");
            more.remove("words");
            throw new Refused(ex.code(), String.valueOf(ex.reason()), ex.words(), more);
        }
    }

    /** Refuses a login, name or email a card does not take, before the agent is asked. */
    private void fields() {
        if (!LOGIN.matcher(login).matches()) {
            throw new IllegalArgumentException(
                    "'"
                            + RegistryVerifyCommand.printable(login)
                            + "' is not a login: a lower-case letter, then up to 31 lower-case"
                            + " letters, digits or dashes");
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
    private static String fingerprint(final String given) throws Refused, IOException {
        if (FINGERPRINT.matcher(given).matches()) {
            return given;
        }
        final Path file = Path.of(given);
        if (!Files.isRegularFile(file) || Files.size(file) > LIMIT) {
            throw new IOException(
                    "'"
                            + RegistryVerifyCommand.printable(given)
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
                    "'" + RegistryVerifyCommand.printable(given) + "' holds no public key", ex);
        }
        if (blob.length == 0 || !IdentityFacts.type(blob).equals(words[0])) {
            throw new IllegalArgumentException(
                    "'" + RegistryVerifyCommand.printable(given) + "' holds no public key");
        }
        if (!words[0].equals("ssh-ed25519")) {
            throw new Refused(
                    65,
                    "key-not-ed25519",
                    "the key in '"
                            + RegistryVerifyCommand.printable(given)
                            + "' is "
                            + RegistryVerifyCommand.printable(words[0])
                            + "; a card takes an Ed25519 key");
        }
        return IdentityFacts.fingerprint(blob);
    }

    /** The agent's key of that fingerprint, which must be one a card takes. */
    private static AgentKey served(final PoreloContext machine, final String fingerprint)
            throws Refused {
        final Optional<List<AgentKey>> served = IdentityFacts.served(machine);
        if (served.isEmpty()) {
            throw new Refused(
                    65,
                    "key-not-in-agent",
                    "no ssh-agent answers at SSH_AUTH_SOCK, so it cannot sign with "
                            + fingerprint
                            + "; start one or unlock KeePassXC");
        }
        for (final AgentKey each : served.get()) {
            if (IdentityFacts.fingerprint(each.keyBlob()).equals(fingerprint)) {
                if (!IdentityFacts.ed25519(each)) {
                    throw new Refused(
                            65,
                            "key-not-ed25519",
                            "the key "
                                    + fingerprint
                                    + " is "
                                    + RegistryVerifyCommand.printable(
                                            IdentityFacts.type(each.keyBlob()))
                                    + "; a card takes an Ed25519 key");
                }
                return each;
            }
        }
        throw new Refused(
                65,
                "key-not-in-agent",
                "the ssh-agent does not serve the key "
                        + fingerprint
                        + "; unlock KeePassXC or load the entry, then ask again");
    }

    private static int plain(final PrintWriter err, final int code, final String message) {
        err.println("porelo: " + RegistryVerifyCommand.printable(message));
        err.flush();
        return code;
    }
}
