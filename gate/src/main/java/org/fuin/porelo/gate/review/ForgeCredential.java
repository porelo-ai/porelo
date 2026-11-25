package org.fuin.porelo.gate.review;

import java.util.Objects;

/**
 * A forge token lent for one call, read from the vault on the host; it is never written to a file,
 * a log or an argument.
 *
 * @param entry The vault entry it was read from, which may be shown.
 * @param token The token.
 */
public record ForgeCredential(String entry, String token) {

    /** Refuses a blank entry or token. */
    public ForgeCredential {
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(token, "token");
        if (entry.isBlank() || token.isBlank()) {
            throw new IllegalArgumentException(
                    "a lent credential names its entry and holds a token");
        }
    }

    /** Names the entry, never the token. */
    @Override
    public String toString() {
        return "ForgeCredential[entry=" + entry + "]";
    }
}
