package org.fuin.porelo.core.project;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The forms an address is written in, and an address as it may be printed or kept.
 *
 * <p>A URL's host is read as git reads it: for {@code ssh://} all before the first {@code /}, which
 * git hands to ssh and ssh splits at its last {@code @}; for {@code https://} up to the first
 * {@code /}, {@code ?} or {@code #}, where git and libcurl end it. A form taken holds no
 * credential: an {@code https://} address no user part at all, since a token alone may stand as the
 * user, and an ssh address only a user ssh reads as a plain name; a token comes from the vault,
 * chosen by the host. No blank or control character of any script is taken, as none is cut around.
 */
public final class AddressForm {

    /**
     * A host name, or an IPv6 address in brackets with an optional zone, which ssh takes as given
     * and libcurl after {@code %25}.
     */
    private static final String HOST =
            "(?:[\\p{L}\\p{N}_][\\p{L}\\p{M}\\p{N}._-]*"
                    + "|\\[[0-9A-Fa-f:.]+(?:%(?:25)?[A-Za-z0-9._~-]+)?\\])";

    /** A port, possibly empty as git and libcurl allow. */
    private static final String PORT = "(?::[0-9]*)?";

    /**
     * An ssh user as ssh reads it, and never a secret: no {@code :} that would hold a password, no
     * {@code %} that would spell one, no {@code @}, and no leading {@code -} read as an option.
     */
    private static final String SSH_USER = "(?!-)[^\\s:@%/?#\\[\\]]+";

    /** {@code ssh://}, {@code https://} or {@code user@host:path}, each with no at sign after. */
    private static final Pattern REPOSITORY =
            Pattern.compile(
                    "ssh://(?:"
                            + SSH_USER
                            + "@)?"
                            + HOST
                            + PORT
                            + "(?:/[^@]*)?"
                            + "|https://"
                            + HOST
                            + PORT
                            + "(?:[/?#][^@]*)?"
                            + "|"
                            + SSH_USER
                            + "@"
                            + HOST
                            + ":[^@]+");

    /**
     * An https base URL with a real host, as a homeserver is written: no user part, query or
     * fragment, and an {@code @} only in its path.
     */
    private static final Pattern HTTPS =
            Pattern.compile("(?i:https)://" + HOST + PORT + "(?:/[^?#]*)?");

    /** A host and port, all an authority may hold besides a user part. */
    private static final Pattern HOST_PORT = Pattern.compile(HOST + PORT);

    /** A scheme an address begins with. */
    private static final Pattern SCHEME = Pattern.compile("[A-Za-z][A-Za-z0-9+.-]*://");

    /** A remote helper's {@code transport::}, its name as git reads a scheme. */
    private static final Pattern TRANSPORT = Pattern.compile("[A-Za-z][A-Za-z0-9+.-]*::");

    /** The schemes git hands to ssh, whose authority ends at the first slash alone. */
    private static final List<String> SSH_SCHEMES = List.of("ssh://", "git+ssh://", "ssh+git://");

    private AddressForm() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Says whether an address is written {@code ssh://}, {@code https://} or {@code
     * user@host:path}, with a real host, no credential, no blank or control character and no remote
     * helper's {@code transport::}.
     *
     * @param address The address.
     * @return Whether it has that form.
     */
    public static boolean isRepository(final String address) {
        return !blank(address)
                && !TRANSPORT.matcher(address).lookingAt()
                && REPOSITORY.matcher(address).matches();
    }

    /**
     * Says whether a value is an {@code https://} base URL with a real host, no user part, query or
     * fragment, and no blank or control character.
     *
     * @param url The value.
     * @return Whether it is one.
     */
    public static boolean isHttps(final String url) {
        return !blank(url) && HTTPS.matcher(url).matches();
    }

    /**
     * Returns an address with nothing of its user part but an ssh user: of a URL, all before its
     * first {@code /} up to the last {@code @} there; of {@code user@host:path}, all up to the last
     * {@code @} before its host's colon. An authority that holds no real host after that is left
     * out whole, and so is one that spells its at sign {@code %40}, since its end does not show.
     *
     * @param address The address.
     * @return It without a credential; an address that has none is returned as given.
     */
    public static String withoutUserinfo(final String address) {
        int lead = 0;
        while (lead < address.length() && blank(address.charAt(lead))) {
            lead++;
        }
        final Matcher scheme = SCHEME.matcher(address).region(lead, address.length());
        return scheme.lookingAt() ? url(address, scheme.group(), scheme.end()) : scp(address);
    }

    private static String url(final String address, final String scheme, final int start) {
        // The widest reading: ssh takes all before the first slash, up to its last at sign, as
        // the user, while git and libcurl end an https host earlier, at a question mark too.
        final int slash = address.indexOf('/', start);
        final int end = slash < 0 ? address.length() : slash;
        final String authority = address.substring(start, end);
        final int at = authority.lastIndexOf('@');
        if (at < 0) {
            return authority.toLowerCase(Locale.ROOT).contains("%40")
                    ? address.substring(0, start) + address.substring(end)
                    : address;
        }
        final String user = authority.substring(0, at);
        final boolean ssh = SSH_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT));
        if (ssh && plainUser(user)) {
            return address;
        }
        final String after = authority.substring(at + 1);
        int hostEnd = 0;
        while (hostEnd < after.length() && (ssh || "?#".indexOf(after.charAt(hostEnd)) < 0)) {
            hostEnd++;
        }
        final String host = after.substring(0, hostEnd);
        final int colon = user.indexOf(':');
        final String name = colon < 0 ? user : user.substring(0, colon);
        final String kept =
                !HOST_PORT.matcher(host).matches()
                        ? after.substring(hostEnd)
                        : (ssh && plainUser(name) ? name + "@" : "") + after;
        return address.substring(0, start) + kept + address.substring(end);
    }

    /**
     * {@code user@host:path}: git splits it at the colon after its host, which may be bracketed
     * after the user, and ssh takes the user up to the last at sign before that.
     */
    private static String scp(final String address) {
        final int slash = address.indexOf('/');
        final String head = slash < 0 ? address : address.substring(0, slash);
        final int colon = head.lastIndexOf(':');
        final int at = colon < 0 ? -1 : head.lastIndexOf('@', colon);
        if (at < 0 || plainUser(address.substring(0, at))) {
            return address;
        }
        return address.substring(at + 1);
    }

    /** Whether a user part is one ssh reads as a plain name, and so no secret. */
    private static boolean plainUser(final String user) {
        return user.matches(SSH_USER) && !blank(user);
    }

    /** Whether a value holds a blank or a control character of any script. */
    private static boolean blank(final String value) {
        return value.chars().anyMatch(AddressForm::blank);
    }

    private static boolean blank(final int c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c) || Character.isISOControl(c);
    }
}
