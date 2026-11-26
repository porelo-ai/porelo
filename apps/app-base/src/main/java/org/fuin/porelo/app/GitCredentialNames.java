package org.fuin.porelo.app;

import java.net.URI;
import java.net.URISyntaxException;
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

    /** A scheme an address begins with, and its {@code ://}. */
    private static final Pattern SCHEME = Pattern.compile("([A-Za-z][A-Za-z0-9+.-]*)://");

    /** The host and port of an ssh authority, as {@link #HOST_AND_PORT}, the port maybe empty. */
    private static final Pattern SSH_HOST_AND_PORT =
            Pattern.compile("(\\[[^\\]\\s=@]+]|[^\\[\\]:=@*\\s\\p{Cntrl}]+)(:\\d*)?");

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
     * <p>The host is the one the address's reader connects to, so the scheme decides where the
     * authority ends. Of an {@code http://} or {@code https://} address, as libcurl reads it: the
     * authority as {@link URI} parses it, ending at the first {@code /}, {@code ?} or {@code #},
     * and the host after its last {@code @}, so {@code https://evil.example#@github.com/x} names
     * evil.example. Only the scheme and authority are parsed, so a character a URI may not hold in
     * its path still leaves the host; an authority the parser refuses names none. Of any other
     * {@code scheme://} address, as git hands it to ssh: all before the first {@code /}, and the
     * host after its last {@code @}, so {@code ssh://evil.example#@github.com/x} names github.com;
     * a host that cannot stand there names none. Of {@code user@host:path}, the host between the
     * {@code @} and the colon. The host is kept as written, in its case.
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
            final Matcher named = SCHEME.matcher(trimmed);
            if (!named.lookingAt() || named.end() != scheme + "://".length()) {
                return null;
            }
            final String protocol = named.group(1).toLowerCase(java.util.Locale.ROOT);
            return "http".equals(protocol) || "https".equals(protocol)
                    ? httpHost(trimmed, scheme)
                    : sshHost(trimmed.substring(named.end()));
        }
        if (trimmed.matches("^[^/@]+@[^/:]+:.*")) {
            return leadingHost(trimmed.substring(trimmed.indexOf('@') + 1));
        }
        return null;
    }

    /**
     * The host of an http(s) address: of its authority as {@link URI} parses it, after the last
     * {@code @}.
     *
     * @param address The address, stripped.
     * @param scheme Where its {@code ://} begins.
     * @return The host, or {@code null} when the parser refuses the authority or finds no host.
     */
    private static @Nullable String httpHost(final String address, final int scheme) {
        final int start = scheme + "://".length();
        final Matcher end = AUTHORITY_END.matcher(address).region(start, address.length());
        final int cut = end.find() ? end.start() : address.length();
        final String authority;
        try {
            authority = new URI(address.substring(0, cut)).getRawAuthority();
        } catch (URISyntaxException ex) {
            return null;
        }
        if (authority == null) {
            return null;
        }
        final String host = leadingHost(authority.substring(authority.lastIndexOf('@') + 1));
        return host.isEmpty() ? null : host;
    }

    /**
     * The host of an address git hands to ssh: after the last {@code @} before the first {@code /}.
     *
     * @param afterScheme The address after its {@code ://}.
     * @return The host, or {@code null} when what stands there is no host and port.
     */
    private static @Nullable String sshHost(final String afterScheme) {
        final int slash = afterScheme.indexOf('/');
        final String authority = slash < 0 ? afterScheme : afterScheme.substring(0, slash);
        final Matcher parts =
                SSH_HOST_AND_PORT.matcher(authority.substring(authority.lastIndexOf('@') + 1));
        return parts.matches() ? parts.group(1) : null;
    }

    /**
     * Whether a token address holds an {@code @} anywhere: in its user part, which a reader may
     * take for a host, or outside its authority, where another reader ends the host elsewhere.
     */
    private static boolean tokenWithAt(final Kind kind, final @Nullable String url) {
        return kind == Kind.TOKEN && url != null && url.indexOf('@') >= 0;
    }

    /**
     * Tells whether an address is taken by a token and holds an {@code @}, so that it implies no
     * vault entry: in its user part, which a reader may take for a host, or outside its authority,
     * where another reader ends the host elsewhere.
     *
     * @param url The repository.
     * @return {@code true} for such an address; a declared credential may still reach it.
     */
    public static boolean impliesNoToken(final @Nullable String url) {
        return tokenWithAt(kindOf(url), url);
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
     *     key, or a host other than the one {@link #hostOf} reads, or than a reader that ends the
     *     authority only at its first {@code /} takes.
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
        // A declaration's match is read up to the first '/': where that or hostOf reads another
        // host, that host's token would be keyed for this one.
        return parts.matches()
                        && parts.group(1).equals(hostOf(trimmed))
                        && parts.group(1).equals(hostBeforeSlash(rest))
                ? HTTPS + hostAndPort
                : null;
    }

    /** The host after the last '@' before the first '/', as a reader that ends it there takes. */
    private static String hostBeforeSlash(final String afterScheme) {
        final int slash = afterScheme.indexOf('/');
        final String upToSlash = slash < 0 ? afterScheme : afterScheme.substring(0, slash);
        return leadingHost(upToSlash.substring(upToSlash.lastIndexOf('@') + 1));
    }

    /**
     * Tells whether git connects to the host this class reads. git decodes an {@code ssh://}
     * address before it ends the host, so {@code ssh://forge.example%2F@other.example/x} connects
     * to forge.example while {@link #hostOf} and the registry read other.example, and that host's
     * key would be offered to the other. Over http the address reaches libcurl undecoded and they
     * agree. git also ends an {@code ssh://} host only at its first {@code /}, so {@code
     * ssh://forge.example#@other.example/x} connects to other.example while {@link #hostOf} reads
     * forge.example.
     *
     * @param url The repository.
     * @return {@code false} for an {@code ssh://} address with an encoded character, a {@code ?} or
     *     a {@code #} before its first {@code /}.
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
        final String authority = slash < 0 ? rest : rest.substring(0, slash);
        return authority.indexOf('%') < 0
                && authority.indexOf('?') < 0
                && authority.indexOf('#') < 0;
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
     * <p>Fails closed: an address that names no host, or that git and this class read as two hosts
     * ({@link #namesOneHost}), names no entry, not even the plain one. An address taken by a token
     * names none when it holds an {@code @}: in its user part, or outside its authority, as in
     * {@code https://forge.example/x/@github.com/y}. A key's address keeps its user, as an ssh
     * address normally carries one.
     *
     * @param url The repository.
     * @return Names to try in order, empty when the URL needs no credential or names none.
     */
    public static List<String> candidatesFor(final @Nullable String url) {
        final Kind kind = kindOf(url);
        final String host = hostOf(url);
        if (kind == Kind.NONE || host == null || !namesOneHost(url) || tokenWithAt(kind, url)) {
            return List.of();
        }
        return kind == Kind.KEY
                ? List.of(SSH + "." + host, SSH, LEGACY_SSH)
                : List.of(TOKEN + "." + host, TOKEN);
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
     * <p>The host is {@link #hostOf}'s, and a destination names none where {@link #candidatesFor}
     * offers none: one git reads as another host, or a token's that holds an {@code @}.
     *
     * @param kind What the destination wants.
     * @param match The destination, normalised.
     * @return The name, or {@code null} when the destination names no host to build one from, is
     *     read as two hosts, or is a token's and holds an {@code @}.
     */
    public static @Nullable String impliedName(final Kind kind, final String match) {
        final String host = hostOf(match);
        if (host == null || !namesOneHost(match) || tokenWithAt(kind, match)) {
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
