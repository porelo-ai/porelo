package org.fuin.porelo.gate.review;

import java.net.URI;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A pull request at a forge instance, by its https address.
 *
 * @param forge The forge instance's host, which is the address's host.
 * @param url The pull request's https address, without user info, query or fragment, at most 2048
 *     ASCII characters.
 */
public record PullRequestRef(String forge, URI url) {

    /** The longest address taken, in characters. */
    public static final int LIMIT = 2048;

    // Review profile 2's shape: the repository's segments, then the pull request's, the last its
    // number; unreserved characters only, so no payload rides in an encoded segment.
    private static final Pattern PATH =
            Pattern.compile("(/[A-Za-z0-9._~-]{1,100}){2,10}/[0-9]{1,20}");

    // A dot segment, which a reader would resolve to another place.
    private static final Pattern DOTS = Pattern.compile("/\\.{1,2}(?=/|$)");

    /**
     * Refuses an address that is not https, is over 2048 characters or not ASCII, carries user
     * info, a query or a fragment, or whose host is not the forge.
     */
    public PullRequestRef {
        Objects.requireNonNull(forge, "forge");
        Objects.requireNonNull(url, "url");
        final String written = url.toString();
        if (written.length() > LIMIT || !written.chars().allMatch(c -> c > 0x20 && c < 0x7f)) {
            throw new IllegalArgumentException(
                    "a pull request's address is at most 2048 printable ASCII characters");
        }
        if (!"https".equals(url.getScheme())) {
            throw new IllegalArgumentException("a pull request's address is https");
        }
        if (url.getRawUserInfo() != null
                || url.getRawQuery() != null
                || url.getRawFragment() != null) {
            throw new IllegalArgumentException(
                    "a pull request's address carries no user, query or fragment");
        }
        if (url.getHost() == null || !url.getHost().equals(forge)) {
            throw new IllegalArgumentException("the address's host is not the forge");
        }
        final String path = url.getRawPath();
        if (path == null || !PATH.matcher(path).matches() || DOTS.matcher(path).find()) {
            throw new IllegalArgumentException(
                    "a pull request's path is its repository's 2 to 8 segments and 1 to 3 more,"
                            + " the last its number");
        }
    }

    /**
     * Returns a pull request's reference from its address.
     *
     * @param url Its https address.
     * @return The reference, its forge the address's host.
     */
    public static PullRequestRef of(final URI url) {
        final String host = url.getHost();
        if (host == null) {
            throw new IllegalArgumentException("a pull request's address names a host");
        }
        return new PullRequestRef(host, url);
    }
}
