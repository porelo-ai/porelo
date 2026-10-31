package org.fuin.porelo.app;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.fuin.porelo.vault.CardException;
import org.fuin.porelo.vault.PersonCard;
import org.fuin.porelo.vault.SshSignature;
import org.jspecify.annotations.Nullable;

/**
 * A registry commit as Git stores it, read here and not by Git: its first parent, its SSH signature
 * and the bytes it signs, and its message's trailers.
 *
 * <p>Git's own readers of a commit start the programs the checkout's configuration names to check a
 * signature, and read trailers as that configuration spells them; nothing here does either.
 *
 * @param parent The first parent's object name, or "" for a root commit.
 * @param payload The commit without its {@code gpgsig} header: what its signature signs.
 * @param signature The SSH signature, armored; {@code null} when there is none, more than one, or
 *     another kind.
 * @param message The message, every byte after the headers.
 */
record RegistryCommit(String parent, String payload, @Nullable String signature, String message) {

    private static final String BEGIN = "-----BEGIN SSH SIGNATURE-----\n";

    private static final String END = "-----END SSH SIGNATURE-----\n";

    /** The namespace Git signs a commit in. */
    private static final String NAMESPACE = "git";

    /**
     * Reads a commit's bytes as Git stores them. Bytes that are no UTF-8 cannot be the text a
     * signature is checked over here: such a commit is read for its trailers, as having no
     * signature.
     *
     * @param raw The commit object's bytes.
     * @return The commit.
     */
    static RegistryCommit parse(final byte[] raw) {
        try {
            return parse(
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                            .decode(java.nio.ByteBuffer.wrap(raw))
                            .toString());
        } catch (java.nio.charset.CharacterCodingException ex) {
            final RegistryCommit lenient = parse(new String(raw, StandardCharsets.UTF_8));
            return new RegistryCommit(lenient.parent(), lenient.payload(), null, lenient.message());
        }
    }

    /**
     * Reads a commit as {@code git cat-file commit} prints it.
     *
     * @param raw The commit object.
     * @return The commit; one without headers has no parent, no signature and no message.
     */
    static RegistryCommit parse(final String raw) {
        final int end = raw.indexOf("\n\n");
        if (end < 0) {
            return new RegistryCommit("", raw, null, "");
        }
        final List<String> kept = new ArrayList<>();
        final StringBuilder signature = new StringBuilder();
        int signatures = 0;
        boolean inSignature = false;
        String parent = "";
        for (String line : raw.substring(0, end).split("\n", -1)) {
            if (line.startsWith(" ") && inSignature) {
                signature.append(line.substring(1)).append('\n');
                continue;
            }
            inSignature = line.startsWith("gpgsig ");
            if (inSignature) {
                signatures++;
                signature.append(line.substring("gpgsig ".length())).append('\n');
                continue;
            }
            if (parent.isEmpty() && line.startsWith("parent ")) {
                parent = line.substring("parent ".length());
            }
            kept.add(line);
        }
        final String armored = signature.toString();
        final boolean ssh = signatures == 1 && armored.startsWith(BEGIN) && armored.endsWith(END);
        return new RegistryCommit(
                parent,
                String.join("\n", kept) + raw.substring(end),
                ssh ? armored : null,
                raw.substring(end + 2));
    }

    /**
     * Returns whether the commit's SSH signature is the key's with this fingerprint, over the
     * commit's bytes, in Git's namespace.
     *
     * @param fingerprint {@code SHA256:...}.
     * @return {@code true} when it verifies here.
     */
    boolean signedBy(final String fingerprint) {
        if (signature == null) {
            return false;
        }
        final byte[] blob = SshSignature.signerOf(signature);
        if (blob == null) {
            return false;
        }
        try {
            if (!PersonCard.fingerprint("ssh-ed25519 " + Base64.getEncoder().encodeToString(blob))
                    .equals(fingerprint)) {
                return false;
            }
        } catch (CardException ex) {
            return false;
        }
        return SshSignature.verify(
                payload.getBytes(StandardCharsets.UTF_8), signature, blob, NAMESPACE);
    }

    /**
     * Returns the fingerprint of the key the commit's SSH signature claims, when it verifies for
     * that key over the commit's bytes.
     *
     * @return {@code SHA256:...}, or {@code null} when there is no signature that verifies.
     */
    @Nullable String signer() {
        if (signature == null) {
            return null;
        }
        final byte[] blob = SshSignature.signerOf(signature);
        if (blob == null) {
            return null;
        }
        final String fingerprint;
        try {
            fingerprint =
                    PersonCard.fingerprint(
                            "ssh-ed25519 " + Base64.getEncoder().encodeToString(blob));
        } catch (CardException ex) {
            return null;
        }
        return signedBy(fingerprint) ? fingerprint : null;
    }

    /**
     * Returns whether the message carries the trailer exactly once, with exactly this value.
     *
     * <p>Every line of the message that starts with the key and a colon counts, and nothing but the
     * message's final line feed is trimmed: a second one, or another spelling, is no match.
     *
     * @param key The trailer's key, as Porelo writes it.
     * @param value The value.
     * @return {@code true} when it is there once, and as written.
     */
    boolean carries(final String key, final String value) {
        final List<String> lines = lines(key);
        return lines.size() == 1 && lines.getFirst().equals(key + ": " + value);
    }

    /**
     * Returns the value of the trailer when the message carries it exactly once.
     *
     * @param key The trailer's key.
     * @return The value, or {@code null} when it is not there once, or not as Porelo writes it.
     */
    @Nullable String once(final String key) {
        final List<String> lines = lines(key);
        return lines.size() == 1 && lines.getFirst().startsWith(key + ": ")
                ? lines.getFirst().substring(key.length() + 2)
                : null;
    }

    /** Whether any line of the message starts with the key and a colon. */
    boolean names(final String key) {
        return !lines(key).isEmpty();
    }

    private List<String> lines(final String key) {
        final String text =
                message.endsWith("\n") ? message.substring(0, message.length() - 1) : message;
        return List.of(text.split("\n", -1)).stream()
                .filter(line -> line.startsWith(key + ":"))
                .toList();
    }
}
