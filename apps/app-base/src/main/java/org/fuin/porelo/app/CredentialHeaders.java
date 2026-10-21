package org.fuin.porelo.app;

import java.util.Map;
import org.fuin.porelo.vault.VaultEntry;
import org.jspecify.annotations.Nullable;

/**
 * What a stored credential must hold for the headers its provider sends beside the token.
 *
 * <p>One check for everyone who asks: a start, the broker, a report of who is signed in, and the
 * sign-in that decides what to keep. A provider that wants a header answers a request without it as
 * if the credential were wrong.
 */
public final class CredentialHeaders {

    private CredentialHeaders() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Says whether a value may be sent as a header and kept as a printed setting.
     *
     * <p>Short and printable: a line break would write a header of its own into the request, and a
     * long value is a token, not an identifier.
     *
     * @param value The value, or {@code null}.
     * @return Whether it is usable.
     */
    public static boolean usable(@Nullable String value) {
        return value != null && value.matches("[\\x21-\\x7e]{1,256}");
    }

    /**
     * Returns the setting a stored credential lacks that its provider sends beside the token.
     *
     * @param entry The stored credential, or {@code null} when there is none.
     * @param credentialHeaders The provider's header to the setting it is filled from.
     * @return The first setting missing or unusable, or {@code null} when none is.
     */
    public static @Nullable String lacking(
            @Nullable VaultEntry entry, Map<String, String> credentialHeaders) {
        for (final String setting : credentialHeaders.values()) {
            if (!usable(entry == null ? null : entry.settings().get(setting))) {
                return setting;
            }
        }
        return null;
    }
}
