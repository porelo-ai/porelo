package org.fuin.porelo.app;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Which vault entry a git URL reaches for, and what to call it.
 *
 * <p><strong>The scheme decides the kind.</strong> An {@code https://} URL is opened by a token and
 * an {@code ssh://} or {@code git@host:} URL by a key; git itself asks for one or the other and
 * never both, so this is not a preference to configure but a fact to read off the URL.
 *
 * <p><strong>The host decides which one.</strong> A company GitLab and a public forge are two
 * different credentials, and a machine that offered the same token to both would hand a company's
 * token to whatever host a project file names. So an entry may be per host, with a plain one as the
 * fallback for a machine that has only one:
 *
 * <pre>
 *   git.ssh.github.com     a key for that host        git.token.github.com
 *   git.ssh                the key for anywhere else  git.token
 *   ssh.default            what a task already pushes with, still read for ssh
 * </pre>
 *
 * The names are the whole interface: {@code porelo vault put git.token.github.com} is what a person
 * types and what an interface shows them to type, and nothing else has to be configured for a
 * project that names that host.
 */
public final class GitCredentialNames {

    /** What a task's ssh-agent has always read, kept as the last fallback for a key. */
    public static final String LEGACY_SSH = "ssh.default";

    private static final String SSH = "git.ssh";

    private static final String TOKEN = "git.token";

    private static final String HTTPS = "https://";

    private static final String SSH_SCHEME = "ssh://";

    private static final Pattern AUTHORITY_END = Pattern.compile("[/?#]");

    /**
     * A host, in brackets when IPv6, and a port: without '=', which would end git's {@code -c key}
     * early and make the rest of the host the helper it runs, without '*', which git reads as a
     * wildcard, and without a second '@'.
     */
    private static final Pattern HOST_AND_PORT =
            Pattern.compile("(\\[[^\\]\\s=@]+]|[^\\[\\]:=@*\\s\\p{Cntrl}]+)(:\\d+)?");

    private GitCredentialNames() {
        throw new UnsupportedOperationException("Utility class");
    }

    /** What kind of credential a URL is opened by. */
    public enum Kind {

        /** An ssh key, served through an agent. */
        KEY,

        /** A token, handed to git through a credential helper. */
        TOKEN,

        /** Neither: a local path, or a scheme nothing here authenticates. */
        NONE
    }

    /**
     * Says what kind of credential a URL needs.
     *
     * @param url The repository.
     * @return The kind, never {@code null}.
     */
    public static Kind kindOf(final @Nullable String url) {
        if (url == null) {
            return Kind.NONE;
        }
        final String trimmed = url.strip();
        if (trimmed.startsWith("ssh://") || trimmed.matches("^[^/@]+@[^/:]+:.*")) {
            return Kind.KEY;
        }
        if (trimmed.startsWith("https://") || trimmed.startsWith("http://")) {
            return Kind.TOKEN;
        }
        return Kind.NONE;
    }

    /**
     * Returns the host a URL names, which is what an entry may be keyed by.
     *
     * @param url The repository.
     * @return The host, or {@code null} when the URL names none.
     */
    public static @Nullable String hostOf(final @Nullable String url) {
        if (url == null) {
            return null;
        }
        final String trimmed = url.strip();
        final int scheme = trimmed.indexOf("://");
        if (scheme >= 0) {
            final String rest = trimmed.substring(scheme + 3);
            // A user only in the authority: an '@' in the path made
            // 'ssh://forge.example/x/@github.com/y' read as github.com, and that host's key was
            // lent to the other.
            final int slash = rest.indexOf('/');
            final int at = (slash < 0 ? rest : rest.substring(0, slash)).lastIndexOf('@');
            final String host = leadingHost(at >= 0 ? rest.substring(at + 1) : rest);
            return host.isEmpty() ? null : host;
        }
        if (trimmed.matches("^[^/@]+@[^/:]+:.*")) {
            return leadingHost(trimmed.substring(trimmed.indexOf('@') + 1));
        }
        return null;
    }

    /**
     * Returns the key git matches a token's helper by: the scheme, the host and the port exactly as
     * the address writes them. git compares it with the whole authority of the address it connects
     * to, so a key without the address's port misses it. {@link #hostOf} is not this: an entry's
     * name stands for a host, on whatever port.
     *
     * @param url The repository.
     * @return The key, or {@code null} when the address is not {@code https://} - over {@code
     *     http://} a token crosses the wire in the clear - or names no host that can stand in a
     *     key, or a host other than the one {@link #hostOf} reads.
     */
    public static @Nullable String helperScopeOf(final @Nullable String url) {
        if (url == null) {
            return null;
        }
        final String trimmed = url.strip();
        if (!trimmed.startsWith(HTTPS)) {
            return null;
        }
        final String rest = trimmed.substring(HTTPS.length());
        // Where git ends the authority: an '@' after any of these names no host.
        final Matcher end = AUTHORITY_END.matcher(rest);
        final String authority = end.find() ? rest.substring(0, end.start()) : rest;
        final String hostAndPort = authority.substring(authority.indexOf('@') + 1);
        final Matcher parts = HOST_AND_PORT.matcher(hostAndPort);
        // The credential is chosen by hostOf: where it reads another host, that host's token would
        // be keyed for this one.
        return parts.matches() && parts.group(1).equals(hostOf(trimmed))
                ? HTTPS + hostAndPort
                : null;
    }

    /**
     * Tells whether git connects to the host this class reads. git decodes an {@code ssh://}
     * address before it ends the host, so {@code ssh://forge.example%2F@other.example/x} connects
     * to forge.example while {@link #hostOf} and the registry read other.example, and that host's
     * key would be offered to the other. Over http the address reaches libcurl undecoded and they
     * agree.
     *
     * @param url The repository.
     * @return {@code false} for an {@code ssh://} address with an encoded character in its
     *     authority.
     */
    public static boolean namesOneHost(final @Nullable String url) {
        if (url == null) {
            return true;
        }
        final String trimmed = url.strip();
        final boolean ssh = trimmed.regionMatches(true, 0, SSH_SCHEME, 0, SSH_SCHEME.length());
        final String rest = ssh ? trimmed.substring(SSH_SCHEME.length()) : trimmed;
        // git takes what follows "@[" for a bracketed part, also where it was encoded, and hands
        // ssh
        // "host/x@other", which ssh connects to as the host after the last '@'. Only a host itself
        // may stand in brackets.
        final String decoded = rest.replaceAll("(?i)%40", "@").replaceAll("(?i)%5b", "[");
        final int bracket = decoded.lastIndexOf("@[");
        if (bracket >= 0
                && (decoded.lastIndexOf('/', bracket) >= 0
                        || decoded.lastIndexOf(':', bracket) >= 0)) {
            return false;
        }
        if (!ssh) {
            return true;
        }
        final int slash = rest.indexOf('/');
        return (slash < 0 ? rest : rest.substring(0, slash)).indexOf('%') < 0;
    }

    /** The host a host and its path begin with; an IPv6 host keeps the colons in its brackets. */
    private static String leadingHost(final String hostAndPath) {
        final int close = hostAndPath.startsWith("[") ? hostAndPath.indexOf(']') : -1;
        if (close >= 0) {
            return hostAndPath.substring(0, close + 1);
        }
        final int end = firstOf(hostAndPath, '/', ':');
        return end < 0 ? hostAndPath : hostAndPath.substring(0, end);
    }

    /**
     * Returns the entries to look in for a URL, most specific first.
     *
     * @param url The repository.
     * @return Names to try in order, empty when the URL needs no credential.
     */
    public static List<String> candidatesFor(final @Nullable String url) {
        final String host = hostOf(url);
        return switch (kindOf(url)) {
            case KEY ->
                    host == null
                            ? List.of(SSH, LEGACY_SSH)
                            : List.of(SSH + "." + host, SSH, LEGACY_SSH);
            case TOKEN -> host == null ? List.of(TOKEN) : List.of(TOKEN + "." + host, TOKEN);
            case NONE -> List.of();
        };
    }

    /**
     * Returns what to tell somebody to type when nothing is stored for a URL.
     *
     * @param url The repository.
     * @return The command, naming the host when there is one.
     */
    public static String storeCommandFor(final @Nullable String url) {
        final List<String> candidates = candidatesFor(url);
        if (candidates.isEmpty()) {
            return "";
        }
        return "porelo vault put "
                + candidates.get(0)
                + (kindOf(url) == Kind.KEY ? " < <the private key file>" : "");
    }

    /**
     * Returns the name to store a credential under for a destination, when nobody said one.
     *
     * <p>The same name the fallback already looks in, so a credential declared without a name and
     * one stored without a declaration are the same entry rather than two that shadow each other.
     *
     * @param kind What the destination wants.
     * @param match The destination, normalised.
     * @return The name, or {@code null} when the destination names no host to build one from.
     */
    public static @Nullable String impliedName(final Kind kind, final String match) {
        final String host = hostOf(match);
        if (host == null) {
            return null;
        }
        return switch (kind) {
            case KEY -> SSH + "." + host;
            case TOKEN -> TOKEN + "." + host;
            case NONE -> null;
        };
    }

    /**
     * Tells whether a name is one of these rather than a provider's.
     *
     * @param name An entry name.
     * @return {@code true} for a git credential.
     */
    public static boolean isOne(final @Nullable String name) {
        return name != null
                && (name.equals(LEGACY_SSH)
                        || name.equals(SSH)
                        || name.equals(TOKEN)
                        || name.startsWith(SSH + ".")
                        || name.startsWith(TOKEN + "."));
    }

    private static int firstOf(final String text, final char one, final char other) {
        final int first = text.indexOf(one);
        final int second = text.indexOf(other);
        if (first < 0) {
            return second;
        }
        if (second < 0) {
            return first;
        }
        return Math.min(first, second);
    }
}
