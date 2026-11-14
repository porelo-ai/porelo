package org.fuin.porelo.app;

import org.fuin.porelo.supervisor.Grants;
import org.fuin.porelo.vault.VaultEntry;
import org.jspecify.annotations.Nullable;

/**
 * Says whether a credential has an end: the one rule behind what storing says and what a list
 * marks.
 *
 * <p>A credential with no end is stored, since some providers issue nothing else, and named, so the
 * one long-lived secret left stays visible to whoever can replace it with one that ends. What ends
 * is recorded only as the setting {@value #EXPIRES_AT}: a sign-in that names an end keeps it, and
 * its renewal moves it. A grant entry's end is its grant's: a refresh token renews what ends, and
 * an access token held as granted ({@value #HELD}) never ends.
 */
public final class CredentialLifetime {

    /** The setting saying when the credential ends, as an ISO-8601 instant. */
    public static final String EXPIRES_AT = "expires_at";

    /** The type of a grant that holds an access token that never expires. */
    public static final String HELD = "access-token";

    /** What storing a credential with no end says, after the label. */
    public static final String SAID =
            "never - nothing ends this credential; if the provider issues one that ends, store that"
                    + " instead, or record its end with --setting "
                    + EXPIRES_AT
                    + "=<instant>";

    /** The same, as one sentence in a result's detail. */
    public static final String DETAIL = "expires " + SAID;

    private CredentialLifetime() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Says whether nothing Porelo holds ends the secret an entry attaches.
     *
     * @param entry The entry as it is listed.
     * @param grant Its grant, for a grant entry; {@code null} when there is none.
     * @return {@code true} for an entry with no end; a grant entry nobody granted has none to
     *     judge, and a renewable sign-in ends whether or not it names its end yet: both are {@code
     *     false}.
     */
    public static boolean endless(final VaultEntry entry, final @Nullable VaultEntry grant) {
        if (Grants.isGrant(entry.type())) {
            return grant != null && HELD.equals(grant.type());
        }
        // A renewable sign-in ends though its agent named no end: its first renewal writes one.
        return !entry.settings().containsKey(EXPIRES_AT) && !LoginCredentials.renewable(entry);
    }

    /**
     * Refuses an end that is not an instant: a typo would count as an end it is not.
     *
     * @param settings The settings an entry is stored with.
     * @return Why it is refused, or {@code null} when it holds or names none.
     */
    public static @Nullable String malformedEnd(final java.util.Map<String, String> settings) {
        final String end = settings.get(EXPIRES_AT);
        if (end == null) {
            return null;
        }
        try {
            java.time.Instant.parse(end);
            return null;
        } catch (java.time.format.DateTimeParseException ex) {
            return EXPIRES_AT
                    + " is '"
                    + end.replaceAll("\\p{Cntrl}", "?")
                    + "', not an ISO-8601 instant such as 2027-01-31T00:00:00Z";
        }
    }
}
