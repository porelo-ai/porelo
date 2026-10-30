package org.fuin.porelo.vault;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.events.Event;
import org.yaml.snakeyaml.events.MappingStartEvent;
import org.yaml.snakeyaml.events.ScalarEvent;

/**
 * A person's card: who they are and the one key they sign, log in and certify with.
 *
 * <p>The file is a YAML body followed by one or two armored SSH signatures over the body's exact
 * bytes, everything before the first signature, so {@code ssh-keygen -Y verify} checks it as well
 * as Porelo does. The first signature is the card's own key, which proves the key is held. A card
 * that changes the key names the old one as {@code predecessor}, and the second signature is the
 * predecessor's, which proves the identity was handed on by whoever held it. With one of the two
 * alone, a stranger could claim somebody's key as their predecessor, or the old key could hand the
 * identity to a key nobody holds.
 *
 * <p>It names no machine: a person signs from whichever machine holds their key.
 *
 * @param login The person's login, the identity; a lower-case name.
 * @param name What the person is called, for people to read.
 * @param email How to reach them, as they wrote it, or "" when none; nobody checks it.
 * @param key The card's key, {@code ssh-ed25519 AAAA...}.
 * @param created The day the card was made.
 * @param expires The day the card stops being valid.
 * @param predecessor The key this card's key replaces, or "" for a first card.
 * @param body The signed bytes, exactly as written.
 * @param signatures The armored signatures, own first, then the predecessor's.
 */
public record PersonCard(
        String login,
        String name,
        String email,
        String key,
        LocalDate created,
        LocalDate expires,
        String predecessor,
        String body,
        List<String> signatures) {

    /** What every signature on a card is for, so it cannot be replayed as another kind. */
    public static final String NAMESPACE = "porelo";

    private static final String BEGIN = "-----BEGIN SSH SIGNATURE-----";

    private static final String END = "-----END SSH SIGNATURE-----";

    private static final Set<String> FIELDS =
            Set.of("login", "name", "email", "key", "created", "expires", "predecessor");

    private static final Set<String> REQUIRED =
            Set.of("login", "name", "key", "created", "expires");

    /** A login: a lower-case name, as a Linux account and a forge login can both carry it. */
    private static final java.util.regex.Pattern KEY_LINE =
            java.util.regex.Pattern.compile("ssh-ed25519 [A-Za-z0-9+/]+={0,2}");

    private static final java.util.regex.Pattern LOGIN =
            java.util.regex.Pattern.compile("[a-z][a-z0-9-]{0,31}");

    /** Compact constructor: the signatures are kept as given and cannot be changed afterwards. */
    public PersonCard {
        signatures = List.copyOf(signatures);
    }

    /**
     * Reads a card, checking its form but not yet its signatures.
     *
     * @param text The file's content.
     * @param origin Where it came from, for messages.
     * @return The card.
     * @throws CardException If the body is not a card or no signature follows it.
     */
    public static PersonCard read(final String text, final String origin) {
        final int start = signatureStart(text);
        if (start < 0) {
            throw new CardException(origin + " carries no signature");
        }
        final String body = text.substring(0, start);
        final Map<String, String> fields = mapping(body, origin);
        for (final String field : fields.keySet()) {
            if (!FIELDS.contains(field)) {
                throw new CardException(
                        origin + ": '" + printable(field) + "' is not a field of a card");
            }
        }
        for (final String field : REQUIRED) {
            if (fields.getOrDefault(field, "").isBlank()) {
                throw new CardException(origin + ": '" + field + "' is missing");
            }
        }
        // A host prints these and writes them into files a line at a time.
        for (final Map.Entry<String, String> field : fields.entrySet()) {
            if (field.getValue().chars().anyMatch(Character::isISOControl)) {
                throw new CardException(
                        origin + ": '" + field.getKey() + "' holds a control character");
            }
        }
        final String login = fields.getOrDefault("login", "");
        if (!LOGIN.matcher(login).matches()) {
            throw new CardException(origin + ": '" + printable(login) + "' is not a login");
        }
        // Porelo's own accounts on a host carry these names; a person taking one would be them.
        if ("porelo".equals(login) || login.startsWith("porelo-")) {
            throw new CardException(origin + ": '" + login + "' is a name Porelo keeps for itself");
        }
        final String key = fields.getOrDefault("key", "");
        keyBlob(key, origin + ": 'key'");
        final String predecessor = fields.getOrDefault("predecessor", "");
        if (!predecessor.isEmpty()) {
            keyBlob(predecessor, origin + ": 'predecessor'");
        }
        return new PersonCard(
                login,
                fields.getOrDefault("name", ""),
                fields.getOrDefault("email", ""),
                key,
                day(fields.get("created"), origin + ": 'created'"),
                day(fields.get("expires"), origin + ": 'expires'"),
                predecessor,
                body,
                blocks(text.substring(start), origin));
    }

    /**
     * Writes a card and signs it.
     *
     * @param login The person's login.
     * @param name What they are called.
     * @param email How to reach them, or "".
     * @param own The card's key, which signs first.
     * @param created The day it is made.
     * @param expires The day it ends.
     * @param predecessor The key it replaces, which signs second, or {@code null} for a first card.
     * @return The file's content.
     */
    public static String write(
            final String login,
            final String name,
            final String email,
            final SigningKey own,
            final LocalDate created,
            final LocalDate expires,
            final @Nullable SigningKey predecessor) {
        final StringBuilder body = new StringBuilder();
        body.append("login: ").append(quoted(login)).append('\n');
        body.append("name: ").append(quoted(name)).append('\n');
        if (!email.isEmpty()) {
            body.append("email: ").append(quoted(email)).append('\n');
        }
        body.append("key: ").append(quoted(keyLine(own))).append('\n');
        body.append("created: ").append(quoted(created.toString())).append('\n');
        body.append("expires: ").append(quoted(expires.toString())).append('\n');
        if (predecessor != null) {
            body.append("predecessor: ").append(quoted(keyLine(predecessor))).append('\n');
        }
        final byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        final StringBuilder file = new StringBuilder(body);
        file.append(SshSignature.sign(own, bytes, NAMESPACE));
        if (predecessor != null) {
            file.append(SshSignature.sign(predecessor, bytes, NAMESPACE));
        }
        // Read back, so a card this writes is one this reads: a name it cannot quote is refused
        // here.
        read(file.toString(), "the card for " + login);
        return file.toString();
    }

    /**
     * Returns whether the card is signed as it must be: by its own key, and for a change of key by
     * its predecessor too, and by nothing else.
     *
     * @return {@code true} when every signature the card needs is there and verifies.
     */
    public boolean signedAsItMustBe() {
        final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        final int needed = predecessor.isEmpty() ? 1 : 2;
        if (signatures.size() != needed
                || !SshSignature.verify(bytes, signatures.get(0), keyBlob(key, "key"), NAMESPACE)) {
            return false;
        }
        return predecessor.isEmpty()
                || SshSignature.verify(
                        bytes, signatures.get(1), keyBlob(predecessor, "predecessor"), NAMESPACE);
    }

    /**
     * Returns the card's key's fingerprint.
     *
     * @return {@code SHA256:...}, as {@code ssh-keygen -l} prints it.
     */
    public String fingerprint() {
        return fingerprint(key);
    }

    /**
     * Returns a key's fingerprint.
     *
     * @param keyLine {@code ssh-ed25519 AAAA...}.
     * @return {@code SHA256:...}, as {@code ssh-keygen -l} prints it.
     */
    public static String fingerprint(final String keyLine) {
        try {
            return "SHA256:"
                    + Base64.getEncoder()
                            .withoutPadding()
                            .encodeToString(
                                    MessageDigest.getInstance("SHA-256")
                                            .digest(keyBlob(keyLine, "the key")));
        } catch (final NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    /**
     * Returns a key as a card names it.
     *
     * @param key The key.
     * @return {@code ssh-ed25519 AAAA...}, with no comment.
     */
    public static String keyLine(final SigningKey key) {
        return "ssh-ed25519 " + Base64.getEncoder().encodeToString(key.keyBlob());
    }

    private static byte[] keyBlob(final String keyLine, final String what) {
        // Exactly, not stripped: a host writes the line as it stands into authorized_keys.
        if (!KEY_LINE.matcher(keyLine).matches()) {
            throw new CardException(what + " is not an 'ssh-ed25519 AAAA...' key");
        }
        final String[] parts = keyLine.split(" ");
        if (parts.length != 2 || !"ssh-ed25519".equals(parts[0])) {
            throw new CardException(what + " is not an 'ssh-ed25519 AAAA...' key");
        }
        final byte[] blob;
        try {
            blob = Base64.getDecoder().decode(parts[1]);
        } catch (final IllegalArgumentException ex) {
            throw new CardException(what + " is not an 'ssh-ed25519 AAAA...' key");
        }
        // The blob's own type and length, not just the prefix: a key of another kind under the
        // ed25519 name would otherwise reach the verifier.
        try {
            final ByteBuffer buffer = ByteBuffer.wrap(blob);
            final byte[] type = SshWire.readString(buffer);
            final byte[] point = SshWire.readString(buffer);
            if (!"ssh-ed25519".equals(new String(type, StandardCharsets.US_ASCII))
                    || point.length != 32
                    || buffer.hasRemaining()) {
                throw new CardException(what + " is not an 'ssh-ed25519 AAAA...' key");
            }
        } catch (final RuntimeException ex) {
            if (ex instanceof CardException card) {
                throw card;
            }
            throw new CardException(what + " is not an 'ssh-ed25519 AAAA...' key");
        }
        return blob;
    }

    private static int signatureStart(final String text) {
        if (text.startsWith(BEGIN)) {
            return 0;
        }
        final int at = text.indexOf("\n" + BEGIN);
        return at < 0 ? -1 : at + 1;
    }

    private static List<String> blocks(final String text, final String origin) {
        final List<String> found = new ArrayList<>();
        int at = 0;
        while (at < text.length()) {
            if (!text.startsWith(BEGIN, at)) {
                throw new CardException(origin + ": only signatures may follow the card");
            }
            final int end = text.indexOf(END, at);
            if (end < 0) {
                throw new CardException(origin + ": a signature is not closed");
            }
            int next = end + END.length();
            if (next < text.length() && text.charAt(next) == '\n') {
                next++;
            }
            found.add(text.substring(at, next));
            at = next;
        }
        if (found.size() > 2) {
            throw new CardException(origin + " carries more than two signatures");
        }
        return found;
    }

    /**
     * Reads the body as one flat mapping of text to text, from the parser's events.
     *
     * <p>Not through a constructor: one resolves merge keys, which slip past the duplicate check,
     * aliases, tags and timestamps that roll an impossible day over. A card has none of them, so
     * any is refused, and every value stays the text that was signed.
     */
    private static Map<String, String> mapping(final String body, final String origin) {
        final LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(0);
        final List<Event> events = new ArrayList<>();
        try {
            new Yaml(new SafeConstructor(options))
                    .parse(new java.io.StringReader(body))
                    .forEach(events::add);
        } catch (final RuntimeException ex) {
            throw new CardException("Cannot read " + origin + ": " + ex.getMessage());
        }
        final int last = events.size() - 3;
        if (events.size() < 5
                || !events.get(0).is(Event.ID.StreamStart)
                || !events.get(1).is(Event.ID.DocumentStart)
                || !(events.get(2) instanceof MappingStartEvent start)
                || start.getAnchor() != null
                || start.getTag() != null
                || !events.get(last).is(Event.ID.MappingEnd)
                || !events.get(last + 1).is(Event.ID.DocumentEnd)
                || !events.get(last + 2).is(Event.ID.StreamEnd)
                || (last - 3) % 2 != 0) {
            throw new CardException(origin + " is not a card");
        }
        final Map<String, String> fields = new java.util.LinkedHashMap<>();
        for (int at = 3; at < last; at += 2) {
            final String field = text(events.get(at), origin);
            if (fields.put(field, text(events.get(at + 1), origin)) != null) {
                throw new CardException(origin + ": '" + printable(field) + "' is given twice");
            }
        }
        return fields;
    }

    private static String text(final Event event, final String origin) {
        if (!(event instanceof ScalarEvent scalar)
                || scalar.getAnchor() != null
                || scalar.getTag() != null) {
            throw new CardException(origin + " is not a card: only plain fields of text");
        }
        final String value = scalar.getValue();
        // A plain null is no value, as YAML reads it.
        return scalar.isPlain() && List.of("", "~", "null", "Null", "NULL").contains(value)
                ? ""
                : value;
    }

    private static LocalDate day(final @Nullable String value, final String what) {
        // Strictly the day as written: no time, no zone, no 31 February rolled into March.
        try {
            return LocalDate.parse(String.valueOf(value));
        } catch (final DateTimeParseException ex) {
            throw new CardException(what + " is not a day, YYYY-MM-DD");
        }
    }

    /** As YAML's double-quoted scalar; a control character is refused rather than escaped. */
    private static String quoted(final String value) {
        if (value.chars().anyMatch(Character::isISOControl)) {
            throw new CardException("'" + printable(value) + "' holds a control character");
        }
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    private static String printable(final String value) {
        return value.replaceAll("\\p{Cntrl}", "?");
    }
}
