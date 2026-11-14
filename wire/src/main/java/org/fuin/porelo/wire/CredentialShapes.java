package org.fuin.porelo.wire;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The credentials Porelo knows by their shape: forge, provider, cloud, chat, registry, Porelo and
 * web tokens, and a private key's header line.
 *
 * <p>One list for every place that looks for them: a command line is masked with it, and a task's
 * workspace is scanned with it at start, so the two cannot drift apart. Each pattern is plain POSIX
 * extended regular expression syntax that Java reads alike, so {@code git grep -E} takes it as it
 * stands. A shape is a net for the forms named here, never a proof that a text holds none.
 */
public final class CredentialShapes {

    /**
     * One shape.
     *
     * @param kind What a match is said to look like, never the match itself.
     * @param pattern The expression, POSIX ERE and Java alike.
     */
    public record Shape(String kind, String pattern) {

        /**
         * Returns the shape compiled for Java.
         *
         * @return The pattern.
         */
        public Pattern compiled() {
            return Pattern.compile(pattern);
        }

        /**
         * Returns the shape as a token of its own: not preceded by a character a token holds.
         *
         * <p>For a search that warns: inside a longer word, {@code sk-} is a task name or a disk's,
         * and a scan of porelo's own tree found 105 places so and 43 without. Masking keeps the
         * unbounded shape, where a run masked by chance costs only that run.
         *
         * @return The expression, POSIX ERE and Java alike.
         */
        public String bounded() {
            return pattern.startsWith("-") ? pattern : "(^|[^A-Za-z0-9_-])(" + pattern + ")";
        }
    }

    /** What a private key is said to be. */
    public static final String PRIVATE_KEY = "private key";

    /** A private key's header line: the one line of a key block a line-wise search sees. */
    public static final Shape PRIVATE_KEY_HEADER =
            new Shape(PRIVATE_KEY, "-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----");

    /**
     * The tokens, each matching inside a longer token too: a credential glued to other characters
     * is still one. The more particular shape comes before the one it is a case of.
     */
    public static final List<Shape> TOKENS =
            List.of(
                    new Shape("forge token", "gh[pousr]_[A-Za-z0-9]{30,}"),
                    new Shape("forge token", "github_pat_[A-Za-z0-9_]{20,}"),
                    new Shape("forge token", "glpat-[A-Za-z0-9_-]{20,}"),
                    new Shape("provider key", "sk-ant-[A-Za-z0-9_-]{20,}"),
                    new Shape("provider key", "sk-(proj-)?[A-Za-z0-9_-]{20,}"),
                    new Shape("cloud key", "(AKIA|ASIA)[0-9A-Z]{16}"),
                    new Shape("cloud key", "AIza[0-9A-Za-z_-]{35}"),
                    new Shape("chat token", "xox[abposr]-[A-Za-z0-9-]{10,}"),
                    new Shape("registry token", "npm_[A-Za-z0-9]{36}"),
                    new Shape("registry token", "pypi-[A-Za-z0-9_-]{50,}"),
                    new Shape("registry token", "hf_[A-Za-z0-9]{30,}"),
                    new Shape("Porelo token", "porelo_[a-z]{2}_[A-Za-z0-9_-]{16,}"),
                    new Shape(
                            "web token",
                            "eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}"));

    /** Every shape a workspace is scanned for: the key's header first, then the tokens. */
    public static final List<Shape> ALL =
            java.util.stream.Stream.concat(
                            java.util.stream.Stream.of(PRIVATE_KEY_HEADER), TOKENS.stream())
                    .toList();

    private static final List<Pattern> COMPILED = ALL.stream().map(Shape::compiled).toList();

    private CredentialShapes() {}

    /**
     * Returns the tokens as one expression, any of them.
     *
     * @return The expression.
     */
    public static String anyToken() {
        return "(" + String.join("|", TOKENS.stream().map(Shape::pattern).toList()) + ")";
    }

    /**
     * Names what a text looks like, by the first shape found in it.
     *
     * @param text The text, used here and not kept.
     * @return The kind, or nothing when no shape is in it.
     */
    public static Optional<String> kindOf(final String text) {
        for (int i = 0; i < COMPILED.size(); i++) {
            if (COMPILED.get(i).matcher(text).find()) {
                return Optional.of(ALL.get(i).kind());
            }
        }
        return Optional.empty();
    }
}
