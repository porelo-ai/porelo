package org.fuin.porelo.app;

import java.util.Locale;
import java.util.regex.Pattern;

/** The machine identity used by transports and cleanup notices. */
public final class MachineName {

    /** What a machine's name looks like: never an option, never blank. */
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,62}");

    /** What a refusal says a machine's name is. */
    public static final String RULE =
            "letters, digits, dots, underscores and hyphens, a letter or digit first, at most 63"
                    + " characters";

    private MachineName() {}

    /**
     * Returns the local host name, or localhost when it cannot be resolved.
     *
     * @return The machine's name.
     */
    public static String hostName() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (java.net.UnknownHostException ex) {
            return "localhost";
        }
    }

    /**
     * Returns the name this account goes by on a transport: {@code <host>-<account>}.
     *
     * @return The name.
     * @throws IllegalArgumentException When the host and account make no machine's name.
     */
    public static String thisAccount() {
        return of(hostName(), System.getProperty("user.name", ""));
    }

    /**
     * Returns the name an account on a host goes by on a transport, so two accounts of one host
     * never share one.
     *
     * @param host The host's name.
     * @param account The account's name.
     * @return {@code <host>-<account>}, in lower case.
     * @throws IllegalArgumentException When the two make no machine's name.
     */
    public static String of(String host, String account) {
        final String name = (host + "-" + account).toLowerCase(Locale.ROOT);
        // Each half on its own: joined first, the host's letter would carry an empty account or
        // one named like an option.
        if (!valid(host) || !valid(account) || !valid(name)) {
            throw new IllegalArgumentException(
                    "'"
                            + name.replaceAll("\\p{Cntrl}", "?")
                            + "' is not a machine's name, each of host and account: "
                            + RULE);
        }
        return name;
    }

    /**
     * Tells whether a text is a machine's name: letters, digits, dots, underscores and hyphens, a
     * letter or digit first, at most 63 characters.
     *
     * @param name The text.
     * @return Whether it is one.
     */
    public static boolean valid(String name) {
        return NAME.matcher(name).matches();
    }
}
