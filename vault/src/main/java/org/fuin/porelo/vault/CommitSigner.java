package org.fuin.porelo.vault;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Base64;

/** The key a registry commit's SSH signature names, and Git's check of it against that key. */
final class CommitSigner {

    private CommitSigner() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Returns the key the commit's signature claims, before anything is verified.
     *
     * @param raw The commit object, as {@code git cat-file commit} prints it.
     * @return {@code ssh-ed25519 AAAA...}.
     * @throws CardException If the commit carries no readable SSH signature, or its key is not an
     *     Ed25519 key.
     */
    static String claimedKey(String raw) {
        final StringBuilder signature = new StringBuilder();
        boolean collecting = false;
        for (String line : raw.lines().toList()) {
            if (line.isEmpty()) {
                break;
            }
            if (line.startsWith("gpgsig ")) {
                collecting = true;
                signature.append(line.substring(7)).append('\n');
            } else if (collecting && line.startsWith(" ")) {
                signature.append(line.substring(1)).append('\n');
            } else {
                collecting = false;
            }
        }
        final byte[] blob = SshSignature.signerOf(signature.toString());
        if (blob == null) {
            throw new CardException("commit has no readable SSH signature");
        }
        final String key = "ssh-ed25519 " + Base64.getEncoder().encodeToString(blob);
        PersonCard.fingerprint(key);
        return key;
    }

    /**
     * Returns whether Git verifies the commit's signature against this key, and no other.
     *
     * @param repository The owned clone.
     * @param commit The commit.
     * @param key The key allowed to have signed it, {@code ssh-ed25519 AAAA...}.
     * @return {@code true} when {@code git verify-commit} accepts it.
     * @throws IOException If the allowed-signers file cannot be written.
     */
    static boolean verifies(RegistryRepository repository, String commit, String key)
            throws IOException {
        Files.writeString(repository.signers(), "signer namespaces=\"git\" " + key + "\n");
        return repository
                .git(
                        "-c",
                        "gpg.ssh.allowedSignersFile=" + repository.signers(),
                        "verify-commit",
                        "--end-of-options",
                        commit)
                .successful();
    }
}
