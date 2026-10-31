package org.fuin.porelo.vault;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/**
 * An OpenSSH user certificate of an Ed25519 key, signed by an Ed25519 key: what a person's key
 * gives a task's key, so a signature by the task is traced to the person who started it.
 *
 * <p>Read as {@code PROTOCOL.certkeys} lays it out, and refused unless its signer's signature over
 * it holds. Who the signer may be is the caller's to judge: the certificate only names it. A host
 * certificate, a critical option or anything after the signature is refused, since nothing here
 * would honour them.
 *
 * @param blob The certificate's blob, as a signature made with it carries it.
 * @param key The certified key, {@code ssh-ed25519 AAAA...}.
 * @param keyId The certificate's key id.
 * @param principals The principals it is valid for.
 * @param validAfter The first instant it is valid.
 * @param validBefore The first instant it is no longer valid.
 * @param signer The key that signed it, {@code ssh-ed25519 AAAA...}.
 */
public record SshCertificate(
        byte[] blob,
        String key,
        String keyId,
        List<String> principals,
        Instant validAfter,
        Instant validBefore,
        String signer) {

    /** The only certificate type read. */
    public static final String TYPE = "ssh-ed25519-cert-v01@openssh.com";

    private static final String ED25519 = "ssh-ed25519";

    private static final int USER_CERTIFICATE = 1;

    /** A certificate line is a few hundred bytes; anything far longer is not one. */
    private static final int MAX_LINE = 16 * 1024;

    /**
     * Constructor.
     *
     * @param blob The certificate's blob.
     * @param key The certified key.
     * @param keyId The key id.
     * @param principals The principals.
     * @param validAfter The first instant it is valid.
     * @param validBefore The first instant it is no longer valid.
     * @param signer The key that signed it.
     */
    public SshCertificate {
        blob = blob.clone();
        principals = List.copyOf(principals);
    }

    @Override
    public byte[] blob() {
        return blob.clone();
    }

    /**
     * Reads a certificate line.
     *
     * @param line {@code ssh-ed25519-cert-v01@openssh.com AAAA...}, a comment allowed.
     * @return The certificate, its signer's signature checked.
     * @throws CardException If it is not such a certificate, or its signature does not hold.
     */
    public static SshCertificate read(final String line) {
        if (line.length() > MAX_LINE) {
            throw new CardException("A certificate is at most " + MAX_LINE + " characters");
        }
        final String[] fields = line.strip().split(" ", 3);
        if (fields.length < 2 || !TYPE.equals(fields[0])) {
            throw new CardException("A certificate is a " + TYPE + " line");
        }
        final byte[] blob;
        try {
            blob = Base64.getDecoder().decode(fields[1]);
        } catch (IllegalArgumentException ex) {
            throw new CardException("A certificate's key is not base64");
        }
        try {
            return parse(blob);
        } catch (SshProtocolException ex) {
            throw new CardException("The certificate is malformed: " + ex.getMessage());
        }
    }

    private static SshCertificate parse(final byte[] blob) {
        final ByteBuffer in = ByteBuffer.wrap(blob);
        if (!TYPE.equals(text(SshWire.readString(in)))) {
            throw new CardException("A certificate's blob names " + TYPE);
        }
        SshWire.readString(in); // nonce
        final byte[] material = SshWire.readString(in);
        if (material.length != 32) {
            throw new CardException("A certified Ed25519 key is 32 bytes");
        }
        long64(in); // serial
        if (int32(in) != USER_CERTIFICATE) {
            throw new CardException("Only a user certificate is accepted");
        }
        final String keyId = text(SshWire.readString(in));
        final List<String> principals = new ArrayList<>();
        final ByteBuffer named = ByteBuffer.wrap(SshWire.readString(in));
        while (named.hasRemaining()) {
            principals.add(text(SshWire.readString(named)));
        }
        if (principals.isEmpty()) {
            throw new CardException("A certificate naming no principal is valid for all of them");
        }
        final Instant validAfter = instant(long64(in));
        final Instant validBefore = instant(long64(in));
        if (!validBefore.isAfter(validAfter)) {
            throw new CardException("The certificate is never valid");
        }
        if (SshWire.readString(in).length != 0) {
            throw new CardException("A certificate with a critical option is not accepted");
        }
        SshWire.readString(in); // extensions: permissions for a login, nothing here uses them
        SshWire.readString(in); // reserved
        final byte[] signerBlob = SshWire.readString(in);
        final int signedLength = in.position();
        final byte[] signature = SshWire.readString(in);
        if (in.hasRemaining()) {
            throw new CardException("The certificate has bytes after its signature");
        }
        final ByteBuffer signerKey = ByteBuffer.wrap(signerBlob);
        if (!ED25519.equals(text(SshWire.readString(signerKey)))
                || SshWire.readString(signerKey).length != 32
                || signerKey.hasRemaining()) {
            throw new CardException("A certificate is accepted only signed by an ssh-ed25519 key");
        }
        if (!SigningKey.verify(signerBlob, Arrays.copyOf(blob, signedLength), signature)) {
            throw new CardException("The certificate's signature does not hold");
        }
        return new SshCertificate(
                blob,
                ED25519
                        + " "
                        + Base64.getEncoder().encodeToString(SshWire.ed25519KeyBlob(material)),
                keyId,
                principals,
                validAfter,
                validBefore,
                ED25519 + " " + Base64.getEncoder().encodeToString(signerBlob));
    }

    /**
     * Returns the certificate as a line, without a comment.
     *
     * @return {@code ssh-ed25519-cert-v01@openssh.com AAAA...}.
     */
    public String line() {
        return TYPE + " " + Base64.getEncoder().encodeToString(blob);
    }

    /**
     * Returns the certified key's fingerprint.
     *
     * @return {@code SHA256:...}.
     */
    public String fingerprint() {
        return PersonCard.fingerprint(key);
    }

    /**
     * Whether the certificate is valid at an instant.
     *
     * @param at The instant.
     * @return true from valid-after up to, not including, valid-before.
     */
    public boolean validAt(final Instant at) {
        return !at.isBefore(validAfter) && at.isBefore(validBefore);
    }

    private static String text(final byte[] bytes) {
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException ex) {
            throw new CardException("A certificate's text is not UTF-8");
        }
    }

    private static int int32(final ByteBuffer in) {
        if (in.remaining() < 4) {
            throw new CardException("The certificate is truncated");
        }
        return in.getInt();
    }

    private static long long64(final ByteBuffer in) {
        if (in.remaining() < 8) {
            throw new CardException("The certificate is truncated");
        }
        return in.getLong();
    }

    /** OpenSSH's times are unsigned seconds; "forever" is all ones, and so are far ones. */
    private static Instant instant(final long seconds) {
        return seconds < 0 || seconds > Instant.MAX.getEpochSecond()
                ? Instant.MAX
                : Instant.ofEpochSecond(seconds);
    }
}
