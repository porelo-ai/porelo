package org.fuin.porelo.vault;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import org.jspecify.annotations.Nullable;

/**
 * Detached signatures in OpenSSH's {@code SSHSIG} format.
 *
 * <p>The format OpenSSH's {@code ssh-keygen -Y sign} and {@code -Y verify} speak, so a signature
 * made here can be checked by somebody who has no Porelo - with the key in an {@code
 * allowed_signers} file and nothing else. That is the whole reason for using it rather than a shape
 * of our own: a record that only this program can verify is a record nobody else can audit.
 *
 * <p><strong>Detached, and over the exact bytes.</strong> The message is never re-serialized,
 * reformatted or re-indented anywhere between signing and verifying, which is why the filter that
 * reads a message is forbidden to rewrite it.
 */
public final class SshSignature {

    /** What OpenSSH puts in front of both the blob and the bytes that are signed. */
    private static final byte[] MAGIC = "SSHSIG".getBytes(StandardCharsets.US_ASCII);

    private static final int VERSION = 1;

    private static final String HASH = "sha512";

    private static final String BEGIN = "-----BEGIN SSH SIGNATURE-----";

    private static final String END = "-----END SSH SIGNATURE-----";

    private SshSignature() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Signs a message, detached.
     *
     * @param key Key to sign with.
     * @param message The exact bytes to sign.
     * @param namespace What the signature is for. Verification must use the same one, which is how
     *     a signature over a message cannot be replayed as a signature over something else.
     * @return The armored signature, as {@code ssh-keygen -Y sign} writes it.
     */
    public static String sign(final AgentKey key, final byte[] message, final String namespace) {
        final byte[] blob = signBlob(key, message, namespace);
        try {
            return armor(blob);
        } finally {
            Arrays.fill(blob, (byte) 0);
        }
    }

    /**
     * Signs a message without armoring the SSHSIG blob.
     *
     * @param key Local or agent-backed signing key.
     * @param message The exact bytes to sign.
     * @param namespace What the signature is for.
     * @return The binary SSHSIG blob, including its magic and version.
     */
    public static byte[] signBlob(
            final AgentKey key, final byte[] message, final String namespace) {
        return signBlob(key, ed25519(key), message, namespace);
    }

    /**
     * Signs a message, detached, naming the signer by a blob of its own choice: a certificate of
     * the key, as {@code ssh-keygen -Y sign -f key-cert.pub} names it, so a verifier traces the
     * signature through the certificate to the key that certified it.
     *
     * @param key Key to sign with.
     * @param publicBlob The key's own blob, or a certificate of it.
     * @param message The exact bytes to sign.
     * @param namespace What the signature is for.
     * @return The armored signature.
     */
    public static String sign(
            final AgentKey key,
            final byte[] publicBlob,
            final byte[] message,
            final String namespace) {
        final byte[] blob = signBlob(key, publicBlob, message, namespace);
        try {
            return armor(blob);
        } finally {
            Arrays.fill(blob, (byte) 0);
        }
    }

    private static byte[] signBlob(
            final AgentKey key,
            final byte[] keyBlob,
            final byte[] message,
            final String namespace) {
        final byte[] signature = key.sign(preimage(message, namespace), 0);
        try {
            final byte[] scope = namespace.getBytes(StandardCharsets.UTF_8);
            final byte[] hash = HASH.getBytes(StandardCharsets.US_ASCII);
            final ByteBuffer blob =
                    ByteBuffer.allocate(
                            MAGIC.length
                                    + 4
                                    + 5 * 4
                                    + keyBlob.length
                                    + scope.length
                                    + hash.length
                                    + signature.length);
            blob.put(MAGIC).putInt(VERSION);
            blob.putInt(keyBlob.length).put(keyBlob);
            blob.putInt(scope.length).put(scope);
            blob.putInt(0);
            blob.putInt(hash.length).put(hash);
            blob.putInt(signature.length).put(signature);
            return blob.array();
        } finally {
            Arrays.fill(signature, (byte) 0);
        }
    }

    /**
     * Verifies a detached signature against one key.
     *
     * @param message The exact bytes that were signed.
     * @param armored The signature, as {@link #sign} returns it.
     * @param keyBlob The OpenSSH public key blob the signature must carry and verify against.
     * @param namespace The namespace the signature must carry.
     * @return {@code true} when the signature is this key's, over these bytes, in this namespace.
     */
    public static boolean verify(
            final byte[] message,
            final String armored,
            final byte[] keyBlob,
            final String namespace) {
        try (Parsed parsed = parse(armored)) {
            return verifies(message, parsed, keyBlob, namespace);
        }
    }

    /**
     * Verifies a signature as {@link #signBlob} returns it, without armoring it into a string that
     * nobody can clear.
     *
     * @param message The exact bytes that were signed.
     * @param blob The binary SSHSIG.
     * @param keyBlob The OpenSSH public key blob the signature must carry and verify against.
     * @param namespace The namespace the signature must carry.
     * @return {@code true} when the signature is this key's, over these bytes, in this namespace.
     */
    static boolean verifyBlob(
            final byte[] message, final byte[] blob, final byte[] keyBlob, final String namespace) {
        try (Parsed parsed = parseBlob(blob)) {
            return verifies(message, parsed, keyBlob, namespace);
        }
    }

    private static boolean verifies(
            byte[] message, @Nullable Parsed parsed, byte[] keyBlob, String namespace) {
        return parsed != null
                && Arrays.equals(parsed.keyBlob(), keyBlob)
                && namespace.equals(parsed.namespace())
                && HASH.equals(parsed.hash())
                && SigningKey.verify(
                        parsed.keyBlob(), preimage(message, namespace), parsed.signature());
    }

    /**
     * Returns a key's blob when it is exactly an Ed25519 key, as OpenSSH writes one.
     *
     * <p>Exactly: a blob with a byte more still verifies here, while {@code ssh-keygen} refuses it,
     * and a signature over it is not the one an honest agent makes.
     *
     * @param key The key.
     * @return Its blob.
     * @throws VaultException If it is another kind of key or not in OpenSSH's form.
     */
    static byte[] ed25519(final AgentKey key) {
        final byte[] blob = key.keyBlob();
        try {
            final ByteBuffer buffer = ByteBuffer.wrap(blob);
            final byte[] type = SshWire.readString(buffer);
            final byte[] point = SshWire.readString(buffer);
            if ("ssh-ed25519".equals(new String(type, StandardCharsets.US_ASCII))
                    && point.length == 32
                    && !buffer.hasRemaining()) {
                return blob;
            }
        } catch (final RuntimeException ex) {
            // Falls through to the refusal: a blob that cannot be read is no Ed25519 key.
        }
        throw new VaultException("An SSH signature needs an Ed25519 key in OpenSSH's form");
    }

    /**
     * Returns the public key a signature claims to be from, so a caller can decide whether that key
     * is allowed to speak for a peer before it verifies anything.
     *
     * @param armored The signature.
     * @return The OpenSSH public key blob, or {@code null} when this is not a signature.
     */
    public static byte @Nullable [] signerOf(final String armored) {
        try (Parsed parsed = parse(armored)) {
            return parsed == null ? null : parsed.keyBlob();
        }
    }

    private static byte[] preimage(final byte[] message, final String namespace) {
        try {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(MAGIC);
            SshWire.writeString(out, namespace);
            SshWire.writeString(out, new byte[0]);
            SshWire.writeString(out, HASH);
            SshWire.writeString(out, MessageDigest.getInstance("SHA-512").digest(message));
            return out.toByteArray();
        } catch (final IOException e) {
            throw new UncheckedIOException("Cannot build the signed data", e);
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-512 is not available", e);
        }
    }

    private static String armor(final byte[] blob) {
        final String base64 = Base64.getEncoder().encodeToString(blob);
        final StringBuilder out = new StringBuilder(BEGIN).append('\n');
        for (int start = 0; start < base64.length(); start += 70) {
            out.append(base64, start, Math.min(start + 70, base64.length())).append('\n');
        }
        return out.append(END).append('\n').toString();
    }

    private static @Nullable Parsed parse(final String armored) {
        final String body = armored.replace(BEGIN, "").replace(END, "").replaceAll("\\s", "");
        final byte[] blob;
        try {
            blob = Base64.getDecoder().decode(body);
        } catch (final IllegalArgumentException e) {
            return null;
        }
        try {
            // Padded and canonical, as ssh-keygen reads it: no other spelling of the same bytes.
            if (!Base64.getEncoder().encodeToString(blob).equals(body)) {
                return null;
            }
            return parseBlob(blob);
        } finally {
            Arrays.fill(blob, (byte) 0);
        }
    }

    private static @Nullable Parsed parseBlob(final byte[] blob) {
        if (blob.length < MAGIC.length + 4
                || !Arrays.equals(Arrays.copyOf(blob, MAGIC.length), MAGIC)) {
            return null;
        }
        final ByteBuffer buffer = ByteBuffer.wrap(blob, MAGIC.length, blob.length - MAGIC.length);
        byte[] signature = null;
        boolean returned = false;
        try {
            if (buffer.getInt() != VERSION) {
                return null;
            }
            final byte[] key = SshWire.readString(buffer);
            final byte[] namespace = SshWire.readString(buffer);
            final byte[] reserved = SshWire.readString(buffer);
            final byte[] hash = SshWire.readString(buffer);
            signature = SshWire.readString(buffer);
            // As ssh-keygen -Y verify: nothing reserved, and nothing after the signature.
            if (reserved.length > 0 || buffer.hasRemaining()) {
                return null;
            }
            final Parsed parsed =
                    new Parsed(
                            key,
                            new String(namespace, StandardCharsets.UTF_8),
                            new String(hash, StandardCharsets.UTF_8),
                            signature);
            returned = true;
            return parsed;
        } catch (final RuntimeException e) {
            // A truncated or malformed signature is not a signature. It is never an exception to
            // the caller, because "this did not verify" is the only answer that matters.
            return null;
        } finally {
            if (!returned && signature != null) Arrays.fill(signature, (byte) 0);
        }
    }

    private record Parsed(byte[] keyBlob, String namespace, String hash, byte[] signature)
            implements AutoCloseable {
        @Override
        public void close() {
            Arrays.fill(signature, (byte) 0);
        }
    }
}
