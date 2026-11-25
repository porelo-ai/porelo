package org.fuin.porelo.build.api;

import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The shapes and bounds of text a forge adapter answers, and how such text is shown in a message.
 *
 * <p>An adapter is a separate executable: what it answers is held to profile 2's shapes before
 * Porelo uses it, and shown only scrubbed of the token lent to it and bounded.
 */
final class AdapterText {

    /** The most characters an id the contract carries has, such as a thread's. */
    static final int MOST_ID = 64;

    /** The most characters a page's cursor has. */
    static final int MOST_CURSOR = 256;

    /** The most characters a field {@code NotInProfile} names has. */
    static final int MOST_FIELD = 128;

    /** The most characters a branch name has. */
    static final int MOST_BRANCH = 255;

    /** The most characters of an adapter's text a message shows. */
    static final int MOST_SHOWN = 512;

    private static final String HOST = "https://[A-Za-z0-9.-]{1,253}(?::[0-9]{1,5})?";

    private static final String SEGMENT = "/(?!\\.{1,2}(?:/|$))[A-Za-z0-9._~-]{1,100}";

    private static final Pattern REPOSITORY = Pattern.compile(HOST + "(?:" + SEGMENT + "){2,8}");

    // A repository's 2 to 8 segments and the pull request's 1 to 3, the last all digits.
    private static final Pattern PULL_REQUEST =
            Pattern.compile(HOST + "(?:" + SEGMENT + "){2,10}/[0-9]{1,100}");

    private AdapterText() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Tells whether a text is a forge repository's https address as profile 2 shapes it.
     *
     * @param text The text.
     * @return {@code true} for {@code https://<host>[:<port>]/} and 2 to 8 segments.
     */
    static boolean isRepository(final String text) {
        return REPOSITORY.matcher(text).matches();
    }

    /**
     * Tells whether a text is a pull request's https address as profile 2 shapes it.
     *
     * @param text The text.
     * @return {@code true} for a repository's address and 1 to 3 segments, the last all digits.
     */
    static boolean isPullRequest(final String text) {
        return PULL_REQUEST.matcher(text).matches();
    }

    /**
     * Tells whether a text is a branch name git accepts, as {@code git check-ref-format --branch}
     * does.
     *
     * @param text The text.
     * @return {@code true} for a branch name of at most {@link #MOST_BRANCH} characters.
     */
    static boolean isBranch(final String text) {
        if (text.isEmpty()
                || text.length() > MOST_BRANCH
                || text.equals("@")
                || text.startsWith("-")
                || text.startsWith("/")
                || text.endsWith("/")
                || text.endsWith(".")
                || text.contains("..")
                || text.contains("//")
                || text.contains("@{")) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (c <= ' ' || c == 0x7f || "~^:?*[\\".indexOf(c) >= 0) {
                return false;
            }
        }
        for (final String component : text.split("/", -1)) {
            if (component.startsWith(".") || component.endsWith(".lock")) {
                return false;
            }
        }
        return true;
    }

    /**
     * Tells whether a text is within a bound and free of control characters.
     *
     * @param text The text.
     * @param most The most characters it may have.
     * @return {@code true} when it is not blank, at most {@code most} long and has no control
     *     character.
     */
    static boolean isPlain(final String text, final int most) {
        return !text.isBlank() && text.length() <= most && !hasControl(text);
    }

    /**
     * Returns an adapter's text as a message may show it: the lent token replaced by {@code
     * [token]}, every control character by a space, and at most {@link #MOST_SHOWN} characters.
     *
     * @param text The adapter's text.
     * @param token The token lent for the call; {@code null} or empty when none was.
     * @return The text to show.
     */
    static String shown(final @Nullable Object text, final @Nullable String token) {
        final String shown = scrubbed(String.valueOf(text), token);
        final StringBuilder plain = new StringBuilder(Math.min(shown.length(), MOST_SHOWN + 1));
        for (int i = 0; i < shown.length() && plain.length() <= MOST_SHOWN; i++) {
            final char c = shown.charAt(i);
            plain.append(Character.isISOControl(c) ? ' ' : c);
        }
        return plain.length() > MOST_SHOWN
                ? plain.substring(0, MOST_SHOWN - 3) + "..."
                : plain.toString();
    }

    /**
     * Returns a text with the lent token replaced by {@code [token]}.
     *
     * @param text The text.
     * @param token The token lent for the call; {@code null} or empty when none was.
     * @return The text without the token.
     */
    static String scrubbed(final String text, final @Nullable String token) {
        return token == null || token.isEmpty() ? text : text.replace(token, "[token]");
    }

    private static boolean hasControl(final String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isISOControl(text.charAt(i))) {
                return true;
            }
        }
        return false;
    }
}
