package org.fuin.porelo.app;

import java.util.Locale;
import org.fuin.porelo.core.project.AddressForm;
import org.jspecify.annotations.Nullable;

/**
 * Tells whether two addresses name the same repository, whichever of git's spellings each uses.
 *
 * <p>{@code git@github.com:you/app.git}, {@code ssh://git@github.com/you/app} and {@code
 * https://github.com/you/app} are one repository. A checkout's {@code origin} is spelt however the
 * person cloned it, and a placed project's {@code upstream} however its author wrote it; comparing
 * the strings would put the same repository in two projects.
 */
final class RepositoryAddress {

    private RepositoryAddress() {}

    /**
     * Returns whether two addresses name the same repository.
     *
     * @param one An address.
     * @param other Another.
     * @return {@code true} when host and path agree, ignoring scheme, user, a trailing {@code .git}
     *     and the host's case.
     */
    static boolean same(final @Nullable String one, final @Nullable String other) {
        final String left = key(one);
        return left != null && left.equals(key(other));
    }

    /**
     * Returns whether an approval for one address holds for another. Stricter than {@link #same}: a
     * yes given to one repository must not reach another that only looks like it, so the port
     * counts and a path on this machine is never an address elsewhere. ssh and https of one host
     * and path stay one repository, so a clone by either is the same project.
     *
     * @param one An address.
     * @param other Another.
     * @return {@code true} when host, port and path agree, ignoring scheme, user, a port that is
     *     its scheme's own, a trailing {@code .git} and the host's case.
     */
    static boolean sameUpstream(final @Nullable String one, final @Nullable String other) {
        final String left = upstreamKey(one);
        return left != null && left.equals(upstreamKey(other));
    }

    private static @Nullable String upstreamKey(final @Nullable String address) {
        final String key = key(address);
        if (key == null || address == null) {
            return null;
        }
        final String trimmed = address.strip();
        final String host = GitCredentialNames.hostOf(trimmed);
        if (host == null) {
            final String path =
                    trimmed.startsWith("file://") ? trimmed.substring("file://".length()) : trimmed;
            return "local " + (path.startsWith("/") ? "/" : "") + key;
        }
        return "remote "
                + key.substring(0, host.length())
                + portOf(trimmed)
                + key.substring(host.length());
    }

    /**
     * The port an address writes, with its colon; none for its scheme's own or an ssh short form.
     */
    private static String portOf(final String address) {
        final int scheme = address.indexOf("://");
        if (scheme < 0) {
            return "";
        }
        final String rest = address.substring(scheme + 3);
        final int slash = rest.indexOf('/');
        String authority = slash < 0 ? rest : rest.substring(0, slash);
        authority = authority.substring(authority.lastIndexOf('@') + 1);
        final int colon = authority.indexOf(':', Math.max(authority.indexOf(']'), 0));
        if (colon < 0) {
            return "";
        }
        final String port = authority.substring(colon + 1).replaceFirst("^0+(?=.)", "");
        final String own =
                switch (address.substring(0, scheme).toLowerCase(Locale.ROOT)) {
                    case "ssh" -> "22";
                    case "https" -> "443";
                    case "http" -> "80";
                    case "git" -> "9418";
                    default -> "";
                };
        return port.isEmpty() || port.equals(own) ? "" : ":" + port;
    }

    /**
     * Returns what a repository is called, for a name nobody gave: the last part of its path.
     *
     * @param address Its address.
     * @return A repository name, lowercased, with anything a name cannot hold made a dash.
     */
    static String nameOf(final String address) {
        String path = address.strip();
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (path.endsWith(".git")) {
            path = path.substring(0, path.length() - ".git".length());
        }
        final int cut = Math.max(path.lastIndexOf('/'), path.lastIndexOf(':'));
        String last =
                path.substring(cut + 1)
                        .toLowerCase(Locale.ROOT)
                        .replaceAll("[^a-z0-9.-]+", "-")
                        .replaceAll("[.-]*\\.[.-]+|[.-]+\\.[.-]*", "-")
                        .replaceAll("^[.-]+", "");
        if (last.length() > 63) {
            last = last.substring(0, 63);
        }
        last = last.replaceAll("[.-]+$", "");
        return last.isEmpty() ? "repository" : last;
    }

    /**
     * Returns host and path, lowercased host, no user, no scheme, no {@code .git}; a local path as
     * it is.
     *
     * @param address The address.
     * @return The key, or {@code null} for none.
     */
    static @Nullable String key(final @Nullable String address) {
        if (address == null || address.isBlank()) {
            return null;
        }
        final String trimmed = address.strip();
        final String host = GitCredentialNames.hostOf(trimmed);
        String path;
        if (host == null) {
            // A path on this machine, or file://.
            path = trimmed.startsWith("file://") ? trimmed.substring("file://".length()) : trimmed;
            return trim(path);
        }
        final int scheme = trimmed.indexOf("://");
        if (scheme >= 0) {
            final String rest = trimmed.substring(scheme + 3);
            final int slash = rest.indexOf('/');
            path = slash < 0 ? "" : rest.substring(slash + 1);
        } else {
            // After the host's colon: an IPv6 host holds colons of its own.
            final int user = trimmed.indexOf('@');
            path = trimmed.substring(trimmed.indexOf(':', user + 1 + host.length()) + 1);
        }
        return host.toLowerCase(Locale.ROOT) + "/" + trim(path);
    }

    private static String trim(final String path) {
        String trimmed = path;
        while (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.endsWith(".git")
                ? trimmed.substring(0, trimmed.length() - ".git".length())
                : trimmed;
    }

    /**
     * Returns an address without the credential it may carry.
     *
     * <p>{@code https://user:token@host/owner/repo.git} is what cloning with a token leaves as a
     * checkout's {@code origin}. Kept as it is, it would be written into what Porelo keeps and
     * printed in what it says - a secret in a file and in scrollback. The user of an ssh address
     * ({@code git@host:...}) is not a secret and stays; an address that carries none is kept as
     * given, so what is fetched from it does not change.
     *
     * @param address The address.
     * @return It, stripped, as {@link AddressForm#withoutUserinfo} cuts it.
     */
    static String withoutCredential(final String address) {
        return AddressForm.withoutUserinfo(address.strip());
    }
}
