package org.fuin.porelo.vault;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text as it may be printed or kept: in each word holding an {@code @}, everything from the word's
 * start up to and including its last {@code @} is left out, but for a {@code scheme://} the word
 * begins with.
 *
 * <p>Nothing else is parsed, no host and no form of URL: a rule that knew where a user part ends
 * would leak each token holding what it took for an end. It leaves out more than a user part by
 * design - a mail address keeps only its domain, and of two URLs in one word only what follows the
 * last {@code @} is kept.
 */
public final class Userinfo {

    /** A word's whitespace-free runs, and the whitespace between them. */
    private static final Pattern WORDS = Pattern.compile("\\S+|\\s+");

    /** A scheme the word begins with, kept: it names no secret. */
    private static final Pattern SCHEME = Pattern.compile("[A-Za-z0-9+.-]+://");

    private Userinfo() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * The text, no URL in it keeping its user part.
     *
     * @param text The text.
     * @return It, each word with an {@code @} cut after its last one, a leading scheme kept.
     */
    public static String leftOutOf(final String text) {
        final StringBuilder clean = new StringBuilder();
        final Matcher words = WORDS.matcher(text);
        while (words.find()) {
            clean.append(word(words.group()));
        }
        return clean.toString();
    }

    private static String word(final String word) {
        final int at = word.lastIndexOf('@');
        if (at < 0) {
            return word;
        }
        final Matcher scheme = SCHEME.matcher(word);
        final String kept = scheme.lookingAt() ? scheme.group() : "";
        return kept + word.substring(at + 1);
    }
}
