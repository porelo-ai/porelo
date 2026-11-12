package org.fuin.porelo.core.project;

/**
 * A repository a build tool in a task reaches, as the project declares it.
 *
 * <p>What a task may reach for it, and any credential bound to it later, follows from its name and
 * URL alone, never from the block an adapter is given.
 *
 * @param ecosystem The adapter that writes the tool's settings, as {@code porelo ecosystems} lists
 *     it.
 * @param name Its name, letters, digits, {@code _} and {@code -}: a dot would split the key that
 *     names it in an adapter's block.
 * @param url Its URL, inside the task as written; with a credential, the prefix the task's
 *     repository broker reads under, which the task itself never reaches.
 * @param credential The vault entry the broker adds to each request, or {@code null}: then the task
 *     reaches the URL itself, through its egress.
 */
public record ToolRepository(
        String ecosystem,
        String name,
        String url,
        @org.jspecify.annotations.Nullable String credential) {

    /**
     * Constructor for a repository the task reaches itself.
     *
     * @param ecosystem The adapter.
     * @param name Its name.
     * @param url Its URL.
     */
    public ToolRepository(String ecosystem, String name, String url) {
        this(ecosystem, name, url, null);
    }

    /** A repository's name. */
    public static final String NAME_PATTERN = "[A-Za-z0-9_-]{1,64}";

    /**
     * A vault entry's name a repository's credential is read from: one name, or two joined by a
     * slash ({@code repository/corp-nexus}).
     */
    public static final String CREDENTIAL_PATTERN =
            "[a-z0-9][a-z0-9_-]{0,62}(/[a-z0-9][a-z0-9_-]{0,62})?";

    /**
     * Returns whether a URL is a prefix a repository broker can hold a request to: {@code https}
     * with a host, no user information, query or fragment, and no {@code .} or {@code ..} segment,
     * plain or percent-encoded, nor an encoded slash.
     *
     * @param url The URL as written.
     * @return Whether it is one.
     */
    public static boolean isPrefix(final String url) {
        final java.net.URI uri;
        try {
            uri = new java.net.URI(url);
        } catch (final java.net.URISyntaxException ex) {
            return false;
        }
        final String path = uri.getRawPath();
        if (!"https".equals(uri.getScheme())
                || host(url) == null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || url.endsWith("?")
                || url.endsWith("#")
                || path == null) {
            return false;
        }
        final String lower = path.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("%2f") || lower.contains("%5c") || lower.contains("%2e")) {
            return false;
        }
        for (final String segment : path.split("/", -1)) {
            if (".".equals(segment) || "..".equals(segment)) {
                return false;
            }
        }
        return true;
    }

    /** An adapter's name, as an installed one's file is named. */
    public static final String ECOSYSTEM_PATTERN = "[a-z][a-z0-9-]{0,62}";

    /**
     * Returns the host a repository's URL reaches, when it is a URL a task may use: {@code https}
     * with a host, or plain {@code http} on a literal loopback address, and never with user
     * information, which would put a secret in the project's file.
     *
     * @param url The URL as written.
     * @return Its host, lower-case; {@code null} when the URL is none a task may use.
     */
    public static @org.jspecify.annotations.Nullable String host(final String url) {
        final java.net.URI uri;
        try {
            uri = new java.net.URI(url);
        } catch (final java.net.URISyntaxException ex) {
            return null;
        }
        final String host = uri.getHost();
        if (host == null
                || uri.getUserInfo() != null
                || url.chars().anyMatch(each -> each < 0x20 || each == 0x7f)) {
            return null;
        }
        final String lower = host.toLowerCase(java.util.Locale.ROOT);
        final boolean secure = "https".equals(uri.getScheme());
        final boolean plain = "http".equals(uri.getScheme()) && isLoopback(lower);
        return secure || plain ? lower : null;
    }

    /**
     * Tells whether a host is a literal loopback address, which no egress names.
     *
     * @param host The host, as {@link #host(String)} returns it.
     * @return {@code true} for {@code 127.0.0.1} and {@code [::1]}.
     */
    public static boolean isLoopback(final String host) {
        return "127.0.0.1".equals(host) || "[::1]".equals(host);
    }
}
