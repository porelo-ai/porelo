package org.fuin.porelo.organization.git;

import java.net.URI;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The forge and organisation a registry checkout's {@code origin} names.
 *
 * <p>Only the three forms a forge hands out for a repository are read, so nothing in a URL can make
 * the forge another host than the one git fetches from.
 *
 * @param url The origin URL, as the checkout's configuration has it.
 * @param forge The forge, {@code https://<host>}.
 * @param organisation The organisation owning the registry.
 * @param port The port the URL names, or {@code -1} when it names none.
 */
public record RegistryOrigin(String url, URI forge, String organisation, int port) {

    /** A host name: dot-separated DNS labels. */
    private static final String HOST =
            "[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?"
                    + "(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*";

    /** An organisation or repository: what the forge directory accepts as a name. */
    private static final String NAME = "[A-Za-z0-9][A-Za-z0-9._-]{0,99}";

    private static final String PATH = "/(?<org>" + NAME + ")/(?:" + NAME + ")";

    private static final String PORT = "(?::(?<port>[0-9]{1,5}))?";

    private static final List<Pattern> FORMS =
            List.of(
                    Pattern.compile("https://(?<host>" + HOST + ")" + PORT + PATH + "/?"),
                    Pattern.compile(
                            "git@(?<host>" + HOST + "):(?<org>" + NAME + ")/(?:" + NAME + ")"),
                    Pattern.compile("ssh://git@(?<host>" + HOST + ")" + PORT + PATH + "/?"));

    /**
     * Reads an origin URL.
     *
     * @param url The URL.
     * @return Its forge and organisation.
     * @throws IllegalArgumentException Naming the URL, made printable, when it is none of the
     *     forms.
     */
    public static RegistryOrigin of(final String url) {
        for (final Pattern form : FORMS) {
            final Matcher matcher = form.matcher(url);
            if (matcher.matches()) {
                final boolean https = url.startsWith("https://");
                final String port =
                        https || url.startsWith("ssh://") ? matcher.group("port") : null;
                // An ssh port is the forge's git over ssh; its web interface is asked on 443.
                return new RegistryOrigin(
                        url,
                        URI.create(
                                "https://"
                                        + matcher.group("host")
                                        + (https && port != null ? ":" + port : "")),
                        matcher.group("org"),
                        port == null ? -1 : Integer.parseInt(port));
            }
        }
        throw new IllegalArgumentException(
                "the checkout's origin '"
                        + org.fuin.porelo.vault.RegistryText.printable(withoutUserinfo(url))
                        + "' is not https://HOST/ORG/REPO, git@HOST:ORG/REPO or"
                        + " ssh://git@HOST/ORG/REPO");
    }

    /**
     * A URL as words may name it: as {@link org.fuin.porelo.vault.Userinfo#leftOutOf} leaves it,
     * all up to its last {@code @} left out but for its scheme.
     *
     * @param url The URL.
     * @return It, any user information left out.
     */
    public static String withoutUserinfo(final String url) {
        return org.fuin.porelo.vault.Userinfo.leftOutOf(url);
    }

    /**
     * Text as it may be printed or kept, as {@link org.fuin.porelo.vault.Userinfo#leftOutOf} leaves
     * it: no URL in it keeping its user part.
     *
     * @param text The text.
     * @return It, each user part left out.
     */
    public static String withoutUserinfoIn(final String text) {
        return org.fuin.porelo.vault.Userinfo.leftOutOf(text);
    }
}
