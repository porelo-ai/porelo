package org.fuin.porelo.organization.git;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.fuin.porelo.organization.Organization;
import org.jspecify.annotations.Nullable;

/**
 * What a walk of the organization's repository ended with: the whole read, which stays inside this
 * adapter, answering each of the organization's questions.
 *
 * @param readTo The commit the read reached, or {@code null}.
 * @param revision The commit it accepted, or {@code null}.
 * @param owners The owners.
 * @param hostAdmins Each host's admins.
 * @param people The persons.
 * @param hosts The hosts.
 * @param revoked The fingerprints of the keys it revokes.
 * @param warnings What the read warns of.
 * @param refusals What the read refused.
 * @param soleWithoutBackup Each place held by one key with no backup key.
 */
record GitOrganization(
        @Nullable String readTo,
        @Nullable String revision,
        List<Owner> owners,
        Map<String, List<Owner>> hostAdmins,
        List<Person> people,
        List<Host> hosts,
        List<String> revoked,
        List<String> warnings,
        List<Refusal> refusals,
        List<Sole> soleWithoutBackup)
        implements Organization {

    /** Compact constructor: every collection is copied and cannot be changed afterwards. */
    GitOrganization {
        owners = List.copyOf(owners);
        final Map<String, List<Owner>> admins = new TreeMap<>();
        hostAdmins.forEach((host, list) -> admins.put(host, List.copyOf(list)));
        hostAdmins = Collections.unmodifiableMap(admins);
        people = List.copyOf(people);
        hosts = List.copyOf(hosts);
        revoked = List.copyOf(revoked);
        warnings = List.copyOf(warnings);
        refusals = List.copyOf(refusals);
        soleWithoutBackup = List.copyOf(soleWithoutBackup);
    }

    @Override
    public @Nullable Refusal lastRefusal() {
        return refusals.isEmpty() ? null : refusals.getLast();
    }

    @Override
    public @Nullable String keyHolder(final String fingerprint, final @Nullable String login) {
        for (final Person person : people) {
            // A refused card is answered but is no card.
            if (!person.refused()
                    && person.fingerprint().equals(fingerprint)
                    && !person.login().equals(login)) {
                return person.login();
            }
        }
        return null;
    }
}
