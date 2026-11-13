package org.fuin.porelo.app;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.fuin.porelo.core.project.AddressForm;
import org.jspecify.annotations.Nullable;

/**
 * The name of a vault entry a transport is lent for a server. One rule, for the lending and for
 * whoever must say the name beforehand: read one way here and another by the client that connects,
 * it would hand one server's token to the other, so only a plain https address, a homeserver's Unix
 * socket and a plain http address on this machine's loopback lend anything.
 */
public final class LentEntry {

    /** An octet of an IPv4 address in decimal, spelled the one way: no leading zero. */
    private static final String OCTET = "(?:25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])";

    /**
     * Plain http to this machine's loopback: {@code 127.0.0.0/8} in dotted decimal, {@code
     * localhost} or {@code [::1]}, an optional port, and a path with no query, fragment, blank or
     * control character. No user part, and no other spelling of a loopback address, so no client
     * reads it as another host.
     */
    private static final Pattern LOOPBACK_HTTP =
            Pattern.compile(
                    "(?i:http)://(127\\."
                            + OCTET
                            + "\\."
                            + OCTET
                            + "\\."
                            + OCTET
                            + "|(?i:localhost)|\\[::1\\])"
                            + "(?::([0-9]{0,5}))?"
                            + "(?:/[^?#\\s\\p{Z}\\p{C}]*)?");

    /** The port plain http goes to when the address names none. */
    private static final int HTTP_PORT = 80;

    private LentEntry() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Returns the server an address lends for.
     *
     * @param address What the settings name, of whatever type.
     * @return The host, in lower case; a socket's address whole, since a path is one file and its
     *     case counts; for loopback http {@code http:<host>:<port>}, the host in lower case and the
     *     port always given, which no https host can be read as, since a host holds no colon
     *     outside brackets; or {@code null} when it lends nothing there.
     */
    public static @Nullable String server(final @Nullable Object address) {
        if (!(address instanceof String named)) {
            return null;
        }
        if (AddressForm.isUnixSocket(named)) {
            return named;
        }
        final Matcher loopback = LOOPBACK_HTTP.matcher(named);
        if (loopback.matches()) {
            return loopbackServer(loopback.group(1), loopback.group(2));
        }
        if (!AddressForm.isHttps(named)) {
            return null;
        }
        final String host = GitCredentialNames.hostOf(named);
        return host == null ? null : host.toLowerCase(Locale.ROOT);
    }

    /**
     * Returns the server a loopback http address lends for: another port is another listener.
     *
     * @param host The loopback host as written.
     * @param port The port as written, empty or {@code null} for http's own.
     * @return {@code http:<host>:<port>}, or {@code null} for a port no listener can have.
     */
    private static @Nullable String loopbackServer(final String host, final @Nullable String port) {
        final int number = port == null || port.isEmpty() ? HTTP_PORT : Integer.parseInt(port);
        if (number < 1 || number > 65_535) {
            return null;
        }
        return "http:" + host.toLowerCase(Locale.ROOT) + ":" + number;
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
