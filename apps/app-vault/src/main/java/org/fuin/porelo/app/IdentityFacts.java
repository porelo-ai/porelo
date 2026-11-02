package org.fuin.porelo.app;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.organization.IdentitySource;
import org.fuin.porelo.organization.git.RegistryOrigin;
import org.fuin.porelo.organization.git.RegistryState;
import org.fuin.porelo.vault.AgentKey;
import org.fuin.porelo.vault.CardException;
import org.fuin.porelo.vault.RegistryText;
import org.fuin.porelo.vault.SshAgentClient;
import org.fuin.porelo.vault.VaultException;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;

/**
 * What the identity verbs read about a key and the person holding it: the agent's keys, the
 * registries this machine read, the revocation list and the forge. Only public values; the private
 * key stays the agent's.
 */
final class IdentityFacts {

    /** Where the person's agent is named. */
    static final String AGENT = "SSH_AUTH_SOCK";

    private static final String ED25519 = "ssh-ed25519";

    private IdentityFacts() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * A registry this machine read, and the state its last read ended with.
     *
     * @param known Where it was read.
     * @param state What it says.
     */
    record Registry(RegistryReadCommand.Known known, RegistryState state) {}

    /**
     * The registries this machine read, each with its trust record, as {@link
     * org.fuin.porelo.organization.git.TrustRecords#read} reads them: every one, or none at all.
     *
     * @param context The machine.
     * @return Each, in the order of their records' names.
     * @throws IOException When a directory on the way or a record cannot be taken.
     */
    static List<Registry> registries(final PoreloContext context) throws IOException {
        final List<Registry> read = new ArrayList<>();
        for (final org.fuin.porelo.organization.git.TrustRecords.Read each :
                org.fuin.porelo.organization.git.TrustRecords.read(context.paths().xdg().state())) {
            read.add(
                    new Registry(
                            new RegistryReadCommand.Known(
                                    each.origin(), each.checkout(), each.file()),
                            each.state()));
        }
        return List.copyOf(read);
    }

    /**
     * The keys the agent serves, in its order.
     *
     * @param context The machine, whose environment names the agent.
     * @return The keys, or empty when no agent answers.
     */
    static Optional<List<AgentKey>> served(final PoreloContext context) {
        final String socket = context.environment().apply(AGENT);
        if (socket == null || socket.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new SshAgentClient(Path.of(socket)).identities());
        } catch (VaultException | java.nio.file.InvalidPathException ex) {
            return Optional.empty();
        }
    }

    /**
     * A key's fingerprint, whatever its type.
     *
     * @param blob The OpenSSH public key blob.
     * @return {@code SHA256:...}, as {@code ssh-keygen -l} prints it.
     */
    static String fingerprint(final byte[] blob) {
        try {
            return "SHA256:"
                    + Base64.getEncoder()
                            .withoutPadding()
                            .encodeToString(MessageDigest.getInstance("SHA-256").digest(blob));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    /**
     * A key's type, as its blob names it.
     *
     * @param blob The OpenSSH public key blob.
     * @return The type, {@code ssh-ed25519} for one a card takes; "" for a blob that names none.
     */
    static String type(final byte[] blob) {
        try {
            final ByteBuffer buffer = ByteBuffer.wrap(blob);
            final int length = buffer.getInt();
            if (length < 0 || length > buffer.remaining()) {
                return "";
            }
            final byte[] name = new byte[length];
            buffer.get(name);
            return new String(name, StandardCharsets.US_ASCII);
        } catch (RuntimeException ex) {
            return "";
        }
    }

    /**
     * A key as the answers name it.
     *
     * @param key The agent's key.
     * @return Its type and base64, without the comment.
     */
    static String keyLine(final AgentKey key) {
        final byte[] blob = key.keyBlob();
        return type(blob) + " " + Base64.getEncoder().encodeToString(blob);
    }

    /**
     * Whether a key is an Ed25519 key, the only kind a card takes.
     *
     * @param key The agent's key.
     * @return Whether it is.
     */
    static boolean ed25519(final AgentKey key) {
        if (!ED25519.equals(type(key.keyBlob()))) {
            return false;
        }
        try {
            // The blob whole, as a card reads it: a point of another length is no key it takes.
            org.fuin.porelo.vault.PersonCard.fingerprint(
                    org.fuin.porelo.vault.PersonCard.keyLine(key));
            return true;
        } catch (CardException ex) {
            return false;
        }
    }

    /**
     * Whether a key is revoked: in the revocation list this machine keeps, or named revoked by a
     * registry it read.
     *
     * @param context The machine.
     * @param registries What the registries say.
     * @param keyLine The key, {@code ssh-ed25519 AAAA...}.
     * @return Whether it is revoked.
     * @throws IOException When the revocation list is there but cannot be read: a list skipped
     *     would answer every key as kept.
     */
    static boolean revoked(
            final PoreloContext context, final List<Registry> registries, final String keyLine)
            throws IOException {
        final String fingerprint = org.fuin.porelo.vault.PersonCard.fingerprint(keyLine);
        for (final Registry registry : registries) {
            if (registry.state().revoked().contains(fingerprint)) {
                return true;
            }
        }
        final Path krl = context.paths().projects().revocations();
        if (Files.notExists(krl, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        ConfigurationGate.requireRevocations(context.runner(), krl);
        final Path scratch = Files.createTempDirectory("porelo-identity-");
        try {
            final Path pub = scratch.resolve("key.pub");
            Files.writeString(pub, keyLine + "\n", StandardCharsets.UTF_8);
            final CommandResult checked =
                    context.runner()
                            .run(
                                    Command.of(
                                            "ssh-keygen",
                                            "-Q",
                                            "-f",
                                            krl.toAbsolutePath().toString(),
                                            pub.toString()));
            // ssh-keygen says 0 for a key not revoked and 1 for one that is.
            if (checked.exitCode() != 0 && checked.exitCode() != 1) {
                throw new IOException("cannot read the revocation list " + krl);
            }
            return checked.exitCode() == 1;
        } finally {
            try (var entries = Files.walk(scratch)) {
                for (final Path each :
                        entries.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(each);
                }
            }
        }
    }

    /**
     * The person of another login whose accepted card a registry holds with this key.
     *
     * @param registries What the registries say.
     * @param fingerprint The key's fingerprint.
     * @param login The login that may hold it, or {@code null} when any login is another.
     * @return Their login, or empty when none.
     */
    static Optional<String> cardOfAnother(
            final List<Registry> registries,
            final String fingerprint,
            final @Nullable String login) {
        for (final Registry registry : registries) {
            for (final RegistryState.Person person : registry.state().people()) {
                // A refused card is answered but is no card.
                if (!person.state().equals(RegistryState.REFUSED)
                        && person.fingerprint().equals(fingerprint)
                        && !person.login().equals(login)) {
                    return Optional.of(person.login());
                }
            }
        }
        return Optional.empty();
    }

    /**
     * A person as a contact, from the card the registry accepted for them.
     *
     * @param state What the registry says.
     * @param login Their login.
     * @return {@code login}, {@code name} and {@code email}; name and email {@code null} where no
     *     accepted card says them.
     */
    static Map<String, @Nullable Object> contact(final RegistryState state, final String login) {
        final Map<String, @Nullable Object> contact = new LinkedHashMap<>();
        contact.put("login", login);
        contact.put("name", null);
        contact.put("email", null);
        for (final RegistryState.Person person : state.people()) {
            if (person.login().equals(login) && !person.state().equals(RegistryState.REFUSED)) {
                contact.put("name", person.name());
                contact.put("email", person.email().isEmpty() ? null : person.email());
            }
        }
        return contact;
    }

    /**
     * A registry's owners as contacts, the offline backup keys left out.
     *
     * @param state What the registry says.
     * @return Each owner once, in the owners file's order.
     */
    static List<Map<String, @Nullable Object>> owners(final RegistryState state) {
        return contacts(state, state.owners());
    }

    /**
     * A host's admins as contacts, the offline backup keys left out.
     *
     * @param state What the registry says.
     * @param host The host.
     * @return Each admin once, in the owners file's order; none for a host it names no admin of.
     */
    static List<Map<String, @Nullable Object>> hostAdmins(
            final RegistryState state, final String host) {
        return contacts(state, state.hostAdmins().getOrDefault(host, List.of()));
    }

    /** Each login once as a contact, the backup keys left out: they are kept offline. */
    private static List<Map<String, @Nullable Object>> contacts(
            final RegistryState state, final List<RegistryState.Owner> listed) {
        final List<Map<String, @Nullable Object>> contacts = new ArrayList<>();
        final List<String> named = new ArrayList<>();
        for (final RegistryState.Owner owner : listed) {
            if (!owner.backup() && !named.contains(owner.login())) {
                named.add(owner.login());
                contacts.add(contact(state, owner.login()));
            }
        }
        return contacts;
    }

    /**
     * Every registry's owners as contacts, each login once.
     *
     * @param registries What the registries say.
     * @return The owners.
     */
    static List<Map<String, @Nullable Object>> owners(final List<Registry> registries) {
        final List<Map<String, @Nullable Object>> owners = new ArrayList<>();
        for (final Registry registry : registries) {
            for (final Map<String, @Nullable Object> owner : owners(registry.state())) {
                if (owners.stream()
                        .noneMatch(
                                each ->
                                        java.util.Objects.equals(
                                                each.get("login"), owner.get("login")))) {
                    owners.add(owner);
                }
            }
        }
        return owners;
    }

    /**
     * The forge of the first registry read, which a person's keys are listed on.
     *
     * @param registries What the registries say.
     * @return Its address, {@code https://<host>}, or empty when no registry was read.
     */
    static Optional<URI> forge(final List<Registry> registries) {
        if (registries.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(RegistryOrigin.of(registries.getFirst().known().origin()).forge());
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    /**
     * Asks a forge for a login's keys, without a token: the list is public.
     *
     * @param forges How a forge is opened.
     * @param forge Its address.
     * @param login The login.
     * @param fingerprint The key asked about, or {@code null} for the whole list.
     * @return The keys, or empty when the forge could not be asked.
     */
    static Optional<IdentitySource.Keys> keys(
            final RegistryReadCommand.Forges forges,
            final URI forge,
            final String login,
            final @Nullable String fingerprint) {
        try {
            final IdentitySource directory = forges.open(forge, null);
            return fingerprint == null ? directory.keys(login) : directory.keys(login, fingerprint);
        } catch (IllegalArgumentException ex) {
            // A login the forge's path cannot carry is a list nobody could be asked for.
            return Optional.empty();
        }
    }

    /**
     * Whether a list names a key: {@code null} when there is no list, or the key is missing from
     * one known to be partial, which proves nothing.
     *
     * @param keys The forge's list, if it answered.
     * @param fingerprint The key.
     * @return {@code true}, {@code false} or {@code null}.
     */
    static @Nullable Boolean listed(
            final Optional<IdentitySource.Keys> keys, final String fingerprint) {
        if (keys.isEmpty()) {
            return null;
        }
        if (org.fuin.porelo.vault.PersonCard.lists(keys.get().keys(), fingerprint)) {
            return true;
        }
        return keys.get().complete() ? Boolean.FALSE : null;
    }

    /**
     * The forge as {@code identity show} and {@code identity create} answer it.
     *
     * @param forges How a forge is opened.
     * @param registries What the registries say.
     * @param login The person's login.
     * @param fingerprint Their key.
     * @return {@code login}, {@code key_listed}, {@code keys_url} and {@code account_url}; {@code
     *     null} when no registry was read, so no forge is known.
     */
    static @Nullable Map<String, @Nullable Object> forgeAnswer(
            final RegistryReadCommand.Forges forges,
            final List<Registry> registries,
            final String login,
            final String fingerprint) {
        final Optional<URI> forge = forge(registries);
        if (forge.isEmpty()) {
            return null;
        }
        final String base = forge.get().toString();
        final Map<String, @Nullable Object> answer = new LinkedHashMap<>();
        answer.put("login", login);
        answer.put(
                "key_listed", listed(keys(forges, forge.get(), login, fingerprint), fingerprint));
        answer.put("keys_url", base + "/" + login + ".keys");
        answer.put(
                "account_url",
                "github.com".equalsIgnoreCase(forge.get().getHost())
                        ? "https://github.com/settings/keys"
                        : base + "/user/settings/keys");
        return answer;
    }

    /**
     * An answer made printable, the card's text line by line: its lines stay lines, as the card was
     * filed, and nothing within one can move a terminal's cursor or reorder what it shows.
     *
     * @param answer The answer, which may hold the card's text under {@code card}.
     * @return What may be printed as JSON.
     */
    static Map<String, @Nullable Object> printable(final Map<String, @Nullable Object> answer) {
        final Map<String, @Nullable Object> safe = new LinkedHashMap<>();
        for (final Map.Entry<String, @Nullable Object> entry : answer.entrySet()) {
            final Object value = entry.getValue();
            safe.put(
                    RegistryText.printable(entry.getKey()),
                    entry.getKey().equals("card") && value instanceof String card
                            ? String.join(
                                    "\n",
                                    java.util.Arrays.stream(card.split("\n", -1))
                                            .map(RegistryText::printable)
                                            .toList())
                            : RegistryVerifyCommand.printable(value));
        }
        return safe;
    }

    /**
     * Refuses with a typed answer: with {@code --json} the reason and words on standard output, and
     * the words on standard error either way.
     *
     * @param out Standard output.
     * @param err Standard error.
     * @param json Whether the answer is JSON.
     * @param code The exit code.
     * @param reason The fixed kebab-case word.
     * @param words What a person reads.
     * @param more Further fields of the answer, in order.
     * @return The exit code.
     */
    static int refuse(
            final PrintWriter out,
            final PrintWriter err,
            final boolean json,
            final int code,
            final String reason,
            final String words,
            final Map<String, @Nullable Object> more) {
        if (json) {
            final Map<String, @Nullable Object> answer = new LinkedHashMap<>();
            answer.put("reason", reason);
            answer.put("words", words);
            answer.putAll(more);
            out.println(Json.write(RegistryVerifyCommand.printable(answer)));
            out.flush();
        }
        err.println("porelo: " + RegistryVerifyCommand.printable(words));
        err.flush();
        return code;
    }
}
