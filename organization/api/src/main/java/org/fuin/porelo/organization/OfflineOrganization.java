package org.fuin.porelo.organization;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

/**
 * An organization as a host checks it, against its pin, with no person's credential. An adapter may
 * reach the store and a public identity source within {@code check}; what it needs for that is
 * given when it is made, so the host's question stays the same.
 */
public interface OfflineOrganization {

    /**
     * Checks the copy for one host, against the key the host's admin was pinned by.
     *
     * @param host The host's name.
     * @param pinned The fingerprint of the pinned key.
     * @param since The revision applied last, or "" before the first.
     * @return The answer, in JSON's shapes, as the host's sync contract names its fields: {@code
     *     commit} for the revision, {@code host_card}, {@code admins}, {@code projects} and {@code
     *     persons}.
     * @throws IOException When the copy cannot be read.
     * @throws IllegalArgumentException When an argument is none this could check.
     * @throws OrganizationException When the check refuses the copy.
     */
    Map<String, Object> check(String host, String pinned, String since) throws IOException;

    /**
     * Checks the copy for one host, against every key its pin holds: a change of the host's own
     * files is signed by any one of them.
     *
     * @param host The host's name.
     * @param pinned The fingerprints of the keys, backup keys among them.
     * @param since The revision applied last, or "" before the first.
     * @return The answer, in JSON's shapes, with {@code revision}, {@code host_card}, {@code
     *     admins}, {@code persons} and {@code projects}.
     * @throws IOException When the copy cannot be read.
     * @throws IllegalArgumentException When an argument is none this could check.
     * @throws OrganizationException When the check refuses the copy.
     * @throws OrganizationQuestions.Refused When it refuses by a reason: {@code refused-state} for
     *     an older state or a change of the host's files not signed for the pin, {@code cannot-say}
     *     when it cannot reach the revision applied, {@code not-found} for a host it does not have.
     */
    default Map<String, Object> check(String host, Set<String> pinned, String since)
            throws IOException {
        if (pinned.size() != 1) {
            throw new IllegalArgumentException("this copy checks one pinned key");
        }
        return check(host, pinned.iterator().next(), since);
    }
}
