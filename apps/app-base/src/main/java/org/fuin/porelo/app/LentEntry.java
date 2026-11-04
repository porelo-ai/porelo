package org.fuin.porelo.app;

import java.util.Locale;
import org.fuin.porelo.core.project.AddressForm;
import org.jspecify.annotations.Nullable;

/**
 * The name of a vault entry a transport is lent for a server. One rule, for the lending and for
 * whoever must say the name beforehand: read one way here and another by the client that connects,
 * it would hand one server's token to the other, so only a plain https address and a homeserver's
 * Unix socket lend anything.
 */
public final class LentEntry {

    private LentEntry() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Returns the server an address lends for.
     *
     * @param address What the settings name, of whatever type.
     * @return The host, in lower case; a socket's address whole, since a path is one file and its
     *     case counts; or {@code null} when it lends nothing there.
     */
    public static @Nullable String server(final @Nullable Object address) {
        if (!(address instanceof String named)) {
            return null;
        }
        if (AddressForm.isUnixSocket(named)) {
            return named;
        }
        if (!AddressForm.isHttps(named)) {
            return null;
        }
        final String host = GitCredentialNames.hostOf(named);
        return host == null ? null : host.toLowerCase(Locale.ROOT);
    }

    /**
     * Returns the entry lent for a server.
     *
     * @param entry The entry's own name, such as {@code matrix-registration-token}.
     * @param address What the settings name, of whatever type.
     * @return {@code <entry>.<server>}, or {@code null} when it lends nothing there.
     */
    public static @Nullable String name(final String entry, final @Nullable Object address) {
        final String server = server(address);
        return server == null ? null : entry + "." + server;
    }
}
