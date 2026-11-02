package org.fuin.porelo.organization;

import java.io.IOException;

/** Where one organization is kept, opened by its address or its place on this machine. */
public interface OrganizationStore {

    /**
     * Returns the address the organization is read from, exactly as the store names it.
     *
     * @return The address.
     */
    String address();

    /**
     * Reads the organization as it stands, asking the identity source what it needs, and keeps what
     * it trusted for the next read.
     *
     * @param identities Who says whose keys are whose.
     * @return What the organization says.
     * @throws IOException When it cannot be read or what was read cannot be kept.
     * @throws OrganizationException When what was kept or what was read is refused as a whole; an
     *     adapter of the new structure throws {@link OrganizationQuestions.Refused} for an older
     *     state served again. A single change nobody allowed is not thrown: it is left out and
     *     named among the refusals of what is answered.
     */
    Organization read(IdentitySource identities) throws IOException;
}
