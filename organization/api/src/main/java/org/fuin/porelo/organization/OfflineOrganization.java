package org.fuin.porelo.organization;

import java.io.IOException;
import java.util.Map;

/** A copy of an organization that a host checks with no network and no identity source. */
public interface OfflineOrganization {

    /**
     * Checks the copy for one host, against the key the host's admin was pinned by.
     *
     * @param host The host's name.
     * @param pinned The fingerprint of the pinned key.
     * @param since The revision applied last, or "" before the first.
     * @return The answer, in JSON's shapes: the revision, {@code host_card}, {@code admins}, {@code
     *     projects} and {@code persons}, the fields the host reads.
     * @throws IOException When the copy cannot be read.
     * @throws IllegalArgumentException When an argument is none this could check.
     * @throws OrganizationException When the check refuses the copy.
     */
    Map<String, Object> check(String host, String pinned, String since) throws IOException;
}
