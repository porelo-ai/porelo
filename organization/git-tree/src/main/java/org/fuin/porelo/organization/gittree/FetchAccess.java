package org.fuin.porelo.organization.gittree;

import java.util.List;
import java.util.Map;

/**
 * What a fetch of the organization's repository needs, asked for by address and lent for the fetch
 * alone. Where it comes from stays with the caller: a person's vault on their machine, a key file
 * on a host.
 */
public interface FetchAccess {

    /** Lends nothing: what a public repository needs. */
    FetchAccess NONE = address -> Lease.EMPTY;

    /**
     * Lends what this address needs, for as long as the lease is open.
     *
     * @param address The repository's address.
     * @return The lease, which may hold nothing.
     */
    Lease lend(String address);

    /** What one fetch may use, and the undoing of it. */
    interface Lease extends AutoCloseable {

        /** A lease that holds nothing. */
        Lease EMPTY = new Lease() {};

        /**
         * Returns the variables the fetch runs with beside its own.
         *
         * @return The variables.
         */
        default Map<String, String> environment() {
            return Map.of();
        }

        /**
         * Returns git's own arguments, placed before the fetch and after its sealing settings.
         *
         * @return The arguments.
         */
        default List<String> arguments() {
            return List.of();
        }

        /**
         * Returns each value this lease hands git that git, or the forge through git, could say
         * back, in every form it may be said: the token, URL-encoded, and as a Basic authorization.
         * Whatever git writes is cleared of them before anything else is done with it.
         *
         * @return The values, empty when nothing secret is lent.
         */
        default java.util.Set<String> lent() {
            return java.util.Set.of();
        }

        /**
         * Returns a secret in each form git or curl may write it: as it is, URL-encoded, and as the
         * Basic authorization of the user and the secret, with and without its padding.
         *
         * @param user The user git sends with it, or {@code null} for none.
         * @param secret The secret.
         * @return The forms, empty for a blank secret.
         */
        static java.util.Set<String> formsOf(
                final @org.jspecify.annotations.Nullable String user, final String secret) {
            final java.util.Set<String> forms = new java.util.LinkedHashSet<>();
            for (final String value : java.util.List.of(secret, secret.strip())) {
                if (value.isBlank()) {
                    continue;
                }
                forms.add(value);
                forms.add(
                        java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8));
                forms.add(
                        java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8)
                                .replace("+", "%20"));
                final String basic =
                        java.util.Base64.getEncoder()
                                .encodeToString(
                                        ((user == null ? "" : user) + ":" + value)
                                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                forms.add(basic);
                forms.add(basic.replace("=", ""));
            }
            return java.util.Set.copyOf(forms);
        }

        @Override
        default void close() {}
    }
}
