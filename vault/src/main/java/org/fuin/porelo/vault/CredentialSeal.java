package org.fuin.porelo.vault;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.interfaces.XECPublicKey;
import java.security.spec.NamedParameterSpec;
import java.security.spec.XECPublicKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;

/**
 * A vault entry carried from one machine's daemon to another's as ciphertext only the receiving
 * daemon opens.
 *
 * <p>The receiver makes an X25519 key pair for one copy and hands out the public half. The sender
 * agrees a secret with a key pair of its own made for this seal alone, derives an AES-256-GCM key
 * from it with HKDF-SHA256, and encrypts the entry, binding its vault name and kind as associated
 * data, each length-prefixed: a blob opened under another name or kind is refused. What carries the
 * blob between the two - an interface - holds ciphertext only, never the value.
 *
 * <p>The sealed form, base64: a version byte, the sender's 32-byte public key, a 12-byte nonce,
 * then the ciphertext with its tag.
 */
public final class CredentialSeal {

    /** The form's version: the first byte of every sealed blob. */
    private static final byte VERSION = 1;

    /** What a key and a nonce are bound to, so the derivation is this form's alone. */
    private static final byte[] INFO = "porelo credential seal 1".getBytes(StandardCharsets.UTF_8);

    private static final int KEY = 32;

    private static final int NONCE = 12;

    private static final int TAG_BITS = 128;

    /** At most what an entry may weigh sealed, so a blob cannot make a daemon read without end. */
    private static final int MOST = 1 << 20;

    private static final SecureRandom RANDOM = new SecureRandom();

    private CredentialSeal() {}

    /** A blob, key or entry that cannot be sealed or opened, said without what it held. */
    public static final class Refused extends RuntimeException {

        private static final long serialVersionUID = 1L;

        Refused(final String message) {
            super(message);
        }
    }

    /** An entry too large to carry sealed: more than a receiver opens. */
    public static final class TooLarge extends RuntimeException {

        private static final long serialVersionUID = 1L;

        TooLarge() {
            super("the entry is too large to carry sealed");
        }
    }

    /** The receiving side of one copy: its private half never leaves the object. */
    public static final class Receiver {

        private final PrivateKey secret;

        private final String publicKey;

        private Receiver(final KeyPair pair) {
            this.secret = pair.getPrivate();
            this.publicKey = Base64.getEncoder().encodeToString(raw(pair.getPublic()));
        }

        /**
         * Returns the public half to seal to.
         *
         * @return 32 bytes, base64.
         */
        public String publicKey() {
            return publicKey;
        }

        /**
         * Opens a blob sealed to this receiver.
         *
         * @param sealed The blob, base64.
         * @param name The vault name it is for, as the sender answered it.
         * @param kind The entry's kind as the sender answered it, "" for none.
         * @return The entry.
         * @throws Refused When it is not sealed to this receiver, or under another name or kind.
         */
        public VaultEntry open(final String sealed, final String name, final String kind) {
            if (sealed.length() > (MOST + 2) / 3 * 4) {
                throw new Refused("the sealed credential is not of this form");
            }
            final byte[] blob;
            try {
                blob = Base64.getDecoder().decode(sealed);
            } catch (IllegalArgumentException ex) {
                throw new Refused("the sealed credential is not base64");
            }
            if (blob.length < 1 + KEY + NONCE + TAG_BITS / 8 || blob.length > MOST) {
                throw new Refused("the sealed credential is not of this form");
            }
            if (blob[0] != VERSION) {
                throw new Refused("the sealed credential is of another version");
            }
            final byte[] sender = Arrays.copyOfRange(blob, 1, 1 + KEY);
            final byte[] nonce = Arrays.copyOfRange(blob, 1 + KEY, 1 + KEY + NONCE);
            final byte[] body;
            try {
                final byte[] key = derive(agree(secret, publicFrom(sender)), sender);
                final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(
                        Cipher.DECRYPT_MODE,
                        new SecretKeySpec(key, "AES"),
                        new GCMParameterSpec(TAG_BITS, nonce));
                cipher.updateAAD(bound(name, kind));
                body = cipher.doFinal(blob, 1 + KEY + NONCE, blob.length - 1 - KEY - NONCE);
            } catch (GeneralSecurityException | IllegalArgumentException ex) {
                throw new Refused(
                        "the sealed credential does not open here: it is sealed to another key,"
                                + " or for another name or kind");
            }
            return entry(body, kind);
        }
    }

    /**
     * Makes a receiver for one copy.
     *
     * @return It.
     */
    public static Receiver receiver() {
        try {
            return new Receiver(KeyPairGenerator.getInstance("X25519").generateKeyPair());
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("X25519 is part of every Java", ex);
        }
    }

    /**
     * Seals an entry to a receiver's public half.
     *
     * @param publicKey The receiver's public half, base64.
     * @param name The vault name the entry goes under there.
     * @param entry The entry.
     * @return The blob, base64.
     * @throws Refused When the key is no X25519 public key.
     * @throws TooLarge When the sealed entry would be larger than a receiver opens.
     */
    public static String seal(final String publicKey, final String name, final VaultEntry entry) {
        final byte[] receiving;
        try {
            receiving = Base64.getDecoder().decode(publicKey);
        } catch (IllegalArgumentException ex) {
            throw new Refused("the receiving key is not base64");
        }
        if (receiving.length != KEY) {
            throw new Refused("the receiving key is no X25519 public key");
        }
        try {
            final KeyPair own = KeyPairGenerator.getInstance("X25519").generateKeyPair();
            final byte[] sender = raw(own.getPublic());
            final byte[] key = derive(agree(own.getPrivate(), publicFrom(receiving)), sender);
            final byte[] nonce = new byte[NONCE];
            RANDOM.nextBytes(nonce);
            final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(bound(name, kindOf(entry)));
            final byte[] body = cipher.doFinal(body(entry));
            if (1 + KEY + NONCE + body.length > MOST) {
                throw new TooLarge();
            }
            final byte[] blob = new byte[1 + KEY + NONCE + body.length];
            blob[0] = VERSION;
            System.arraycopy(sender, 0, blob, 1, KEY);
            System.arraycopy(nonce, 0, blob, 1 + KEY, NONCE);
            System.arraycopy(body, 0, blob, 1 + KEY + NONCE, body.length);
            return Base64.getEncoder().encodeToString(blob);
        } catch (GeneralSecurityException ex) {
            throw new Refused("the receiving key is no X25519 public key");
        }
    }

    /**
     * Returns an entry's kind as a seal binds it.
     *
     * @param entry The entry.
     * @return Its type, "" when it has none.
     */
    public static String kindOf(final VaultEntry entry) {
        return entry.type() == null ? "" : entry.type();
    }

    /**
     * The associated data: each part length-prefixed, so no NUL in a name or kind can move a byte
     * from one to the other.
     */
    private static byte[] bound(final String name, final String kind) {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            write(out, "porelo credential");
            write(out, name);
            write(out, kind);
        } catch (IOException ex) {
            throw new IllegalStateException("memory does not fail to be written", ex);
        }
        return bytes.toByteArray();
    }

    private static byte[] agree(final PrivateKey own, final PublicKey other)
            throws GeneralSecurityException {
        final KeyAgreement agreement = KeyAgreement.getInstance("X25519");
        agreement.init(own);
        agreement.doPhase(other, true);
        final byte[] shared = agreement.generateSecret();
        if (Arrays.equals(shared, new byte[shared.length])) {
            // A low-order point agrees on zero: nothing secret was agreed.
            throw new GeneralSecurityException("no secret was agreed");
        }
        return shared;
    }

    /** HKDF-SHA256 (RFC 5869) to one AES-256 key, salted with the sender's public half. */
    private static byte[] derive(final byte[] shared, final byte[] salt)
            throws GeneralSecurityException {
        final Mac extract = Mac.getInstance("HmacSHA256");
        extract.init(new SecretKeySpec(salt, "HmacSHA256"));
        final byte[] prk = extract.doFinal(shared);
        final Mac expand = Mac.getInstance("HmacSHA256");
        expand.init(new SecretKeySpec(prk, "HmacSHA256"));
        expand.update(INFO);
        expand.update((byte) 1);
        return Arrays.copyOf(expand.doFinal(), KEY);
    }

    private static PublicKey publicFrom(final byte[] raw) throws GeneralSecurityException {
        // RFC 7748: little-endian u-coordinate, its top bit masked.
        final byte[] reversed = new byte[raw.length];
        for (int i = 0; i < raw.length; i++) {
            reversed[i] = raw[raw.length - 1 - i];
        }
        reversed[0] &= 0x7f;
        return KeyFactory.getInstance("X25519")
                .generatePublic(
                        new XECPublicKeySpec(
                                NamedParameterSpec.X25519, new BigInteger(1, reversed)));
    }

    private static byte[] raw(final PublicKey key) {
        final byte[] big = ((XECPublicKey) key).getU().toByteArray();
        final byte[] little = new byte[KEY];
        // Big-endian, perhaps with a sign byte or fewer bytes: reversed into 32 little-endian.
        for (int i = 0; i < KEY && i < big.length; i++) {
            little[i] = big[big.length - 1 - i];
        }
        return little;
    }

    private static byte[] body(final VaultEntry entry) {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            write(out, entry.value());
            out.writeInt(entry.settings().size());
            for (final Map.Entry<String, String> setting : entry.settings().entrySet()) {
                write(out, setting.getKey());
                write(out, setting.getValue());
            }
        } catch (IOException ex) {
            throw new IllegalStateException("memory does not fail to be written", ex);
        }
        return bytes.toByteArray();
    }

    private static VaultEntry entry(final byte[] body, final String kind) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(body))) {
            final String value = read(in);
            final int count = in.readInt();
            if (count < 0 || count > body.length) {
                throw new Refused("the sealed credential is not of this form");
            }
            final Map<String, String> settings = new LinkedHashMap<>();
            for (int i = 0; i < count; i++) {
                settings.put(read(in), read(in));
            }
            final @Nullable String type = kind.isEmpty() ? null : kind;
            return new VaultEntry(value, type, settings);
        } catch (IOException | IllegalArgumentException ex) {
            throw new Refused("the sealed credential is not of this form");
        }
    }

    private static void write(final DataOutputStream out, final String text) throws IOException {
        final byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String read(final DataInputStream in) throws IOException {
        final int length = in.readInt();
        if (length < 0 || length > in.available()) {
            throw new IOException("a length past the end");
        }
        return new String(in.readNBytes(length), StandardCharsets.UTF_8);
    }
}
