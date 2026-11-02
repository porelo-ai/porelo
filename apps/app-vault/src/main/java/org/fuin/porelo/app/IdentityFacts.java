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
import org.fuin.porelo.organization.Organization;
import org.fuin.porelo.organization.OrganizationQuestions;
import org.fuin.porelo.vault.AgentKey;
import org.fuin.porelo.vault.CardException;
import org.fuin.porelo.vault.RegistryText;
import org.fuin.porelo.vault.SshAgentClient;
import org.fuin.porelo.vault.VaultException;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;

/**
 * What the identity verbs read about a key and the person holding it: the agent's keys, the
 * organization, the revocation list and the forge. Only public values; the private key stays the
 * agent's.
 */
final class IdentityFacts {

    /** Where the person's agent is named. */
    static final String AGENT = "SSH_AUTH_SOCK";

    private static final String ED25519 = "ssh-ed25519";

    private IdentityFacts() {
        throw new UnsupportedOperationException("Utility class");
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
     * Whether a key is revoked: in the revocation list this machine keeps, or by the organization.
     *
     * @param context The machine.
     * @param organization What the organization says, or {@code null} when this machine has none.
     * @param keyLine The key, {@code ssh-ed25519 AAAA...}.
     * @return Whether it is revoked.
     * @throws IOException When the revocation list is there but cannot be read: a list skipped
     *     would answer every key as kept.
     */
    static boolean revoked(
            final PoreloContext context,
            final IdentityOrganization.@Nullable Reading organization,
            final String keyLine)
            throws IOException {
        final String fingerprint = org.fuin.porelo.vault.PersonCard.fingerprint(keyLine);
        if (organization != null
                && organization.answers().getPersons().stream()
                        .anyMatch(
                                person ->
                                        person.revoked()
                                                && person.fingerprint().equals(fingerprint))) {
            return true;
        }
        return revokedHere(context, keyLine);
    }

    /**
     * Whether this machine's revocation list names a key.
     *
     * @param context The machine.
     * @param keyLine The key, {@code ssh-ed25519 AAAA...}.
     * @return Whether it does.
     * @throws IOException When the list is there but cannot be read.
     */
    static boolean revokedHere(final PoreloContext context, final String keyLine)
            throws IOException {
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
     * The person of another login whose accepted card holds this key.
     *
     * @param organization What the organization says, or {@code null} when this machine has none.
     * @param keyLine The key, {@code ssh-ed25519 AAAA...}.
     * @param login The login that may hold it, or {@code null} when any login is another.
     * @return Their login, or empty when none.
     */
    static Optional<String> cardOfAnother(
            final IdentityOrganization.@Nullable Reading organization,
            final String keyLine,
            final @Nullable String login) {
        return organization == null
                ? Optional.empty()
                : organization.answers().keyHolder(keyLine).filter(each -> !each.equals(login));
    }

    /**
     * A person as a contact, from the card the registry accepted for them.
     *
     * @param state What the registry says.
     * @param login Their login.
     * @return {@code login}, {@code name} and {@code email}; name and email {@code null} where no
     *     accepted card says them.
     */
    static Map<String, @Nullable Object> contact(final Organization state, final String login) {
        final Map<String, @Nullable Object> contact = new LinkedHashMap<>();
        contact.put("login", login);
        contact.put("name", null);
        contact.put("email", null);
        for (final Organization.Person person : state.people()) {
            if (person.login().equals(login) && !person.refused()) {
                contact.put("name", person.name());
                contact.put("email", person.email().isEmpty() ? null : person.email());
            }
        }
        return contact;
    }

    /**
     * The organization's owners as contacts, the offline backup keys left out.
     *
     * @param organization What the organization says, or {@code null} when this machine has none.
     * @return Each owner once, in the owners' order.
     */
    static List<Map<String, @Nullable Object>> owners(
            final IdentityOrganization.@Nullable Reading organization) {
        if (organization == null) {
            return List.of();
        }
        final OrganizationQuestions state = organization.answers();
        final List<Map<String, @Nullable Object>> contacts = new ArrayList<>();
        final List<String> named = new ArrayList<>();
        for (final Organization.Owner owner : state.getOrganizationOwners()) {
            if (!owner.backup() && !named.contains(owner.login())) {
                named.add(owner.login());
                contacts.add(contact(state, owner.login()));
            }
        }
        return contacts;
    }

    /**
     * A person as a contact, from their accepted card.
     *
     * @param state What the organization says.
     * @param login Their login.
     * @return {@code login}, {@code name} and {@code email}; name and email {@code null} where no
     *     accepted card says them.
     */
    private static Map<String, @Nullable Object> contact(
            final OrganizationQuestions state, final String login) {
        final Map<String, @Nullable Object> contact = new LinkedHashMap<>();
        contact.put("login", login);
        contact.put("name", null);
        contact.put("email", null);
        for (final OrganizationQuestions.Person person : state.getPersons()) {
            if (person.login().equals(login) && !Organization.REFUSED.equals(person.state())) {
                contact.put("name", person.name());
                contact.put("email", person.email().isEmpty() ? null : person.email());
            }
        }
        return contact;
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
     * Asks the forge for a login's keys; a stale read reached nobody, so asks nobody either.
     *
     * @param organization What the organization says.
     * @param login The login.
     * @param fingerprint The key asked about, or {@code null} for the whole list.
     * @return The keys, or empty when the forge could not be asked.
     */
    static Optional<IdentitySource.Keys> keys(
            final IdentityOrganization.@Nullable Reading organization,
            final String login,
            final @Nullable String fingerprint) {
        if (organization == null || organization.stale()) {
            return Optional.empty();
        }
        try {
            final IdentitySource directory = organization.opened().identities();
            return fingerprint == null ? directory.keys(login) : directory.keys(login, fingerprint);
        } catch (IllegalArgumentException ex) {
            // A login the forge's path cannot carry is a list nobody could be asked for.
            return Optional.empty();
        }
    }

    /**
     * The forge as {@code identity show} and {@code identity create} answer it.
     *
     * @param organization What the organization says, or {@code null} when this machine has none.
     * @param login The person's login.
     * @param fingerprint Their key.
     * @return {@code login}, {@code key_listed}, {@code keys_url} and {@code account_url}; {@code
     *     null} when no forge is known.
     */
    static @Nullable Map<String, @Nullable Object> forgeAnswer(
            final IdentityOrganization.@Nullable Reading organization,
            final String login,
            final String fingerprint) {
        final URI forge = organization == null ? null : organization.opened().forge();
        if (forge == null) {
            return null;
        }
        final String base = forge.toString();
        final Map<String, @Nullable Object> answer = new LinkedHashMap<>();
        answer.put("login", login);
        answer.put("key_listed", listed(keys(organization, login, fingerprint), fingerprint));
        answer.put("keys_url", base + "/" + login + ".keys");
        answer.put(
                "account_url",
                "github.com".equalsIgnoreCase(forge.getHost())
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
            answer.put("revision", null);
            answer.putAll(more);
            out.println(Json.write(RegistryVerifyCommand.printable(answer)));
            out.flush();
        }
        err.println("porelo: " + RegistryVerifyCommand.printable(words));
        err.flush();
        return code;
    }
}
