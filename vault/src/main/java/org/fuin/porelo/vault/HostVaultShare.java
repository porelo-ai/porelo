package org.fuin.porelo.vault;

import java.nio.charset.StandardCharsets;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.params.HKDFParameters;

/** Derives a host account's vault share from an Ed25519 SSH signature. */
public final class HostVaultShare {
    private HostVaultShare() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Derives the share for this host and account.
     *
     * @param key The person's Ed25519 key, normally held by their SSH agent.
     * @param hostFingerprint The host key's fingerprint.
     * @param account The login on that host.
     * @return The 32-byte share. Neither it nor the signature may be logged.
     */
    public static byte[] derive(AgentKey key, String hostFingerprint, String account) {
        // Read once and checked exactly: the share is over this blob, so a second, other answer
        // from the agent must not be the one verified.
        final byte[] keyBlob = SshSignature.ed25519(key);
        // Checked, not trusted: a space in either would let two different pairs sign the same text.
        if (!hostFingerprint.matches("SHA256:[A-Za-z0-9+/]{43}")
                || !account.matches("[a-z][a-z0-9-]{0,31}")) {
            throw new VaultException("A host vault share needs a host key fingerprint and a login");
        }
        final byte[] message =
                ("host=" + hostFingerprint + " account=" + account)
                        .getBytes(StandardCharsets.UTF_8);
        final byte[] signature = SshSignature.signBlob(key, message, "porelo-vault");
        return deriveVerified(message, signature, keyBlob);
    }

    static byte[] deriveVerified(byte[] message, byte[] signature, byte[] keyBlob) {
        try {
            // An agent's signature must verify before it can produce an opening share.
            if (!SshSignature.verifyBlob(message, signature, keyBlob, "porelo-vault")) {
                throw new VaultException(
                        "The SSH agent's signature does not verify against the key");
            }
            final HKDFBytesGenerator generator = new HKDFBytesGenerator(new SHA256Digest());
            generator.init(
                    new HKDFParameters(
                            signature,
                            new byte[0],
                            "porelo-vault-share-v1".getBytes(StandardCharsets.UTF_8)));
            final byte[] share = new byte[32];
            generator.generateBytes(share, 0, share.length);
            return share;
        } finally {
            java.util.Arrays.fill(signature, (byte) 0);
        }
    }
}
