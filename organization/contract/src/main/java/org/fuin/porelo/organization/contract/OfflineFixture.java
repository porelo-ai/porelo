package org.fuin.porelo.organization.contract;

import java.io.IOException;
import org.fuin.porelo.organization.OfflineOrganization;
import org.jspecify.annotations.Nullable;

/**
 * What an adapter gives so a host's offline check can be held to its pinned key: an organization
 * with one host, the attacker in the adapter's own way, and the copy a host is brought.
 */
public interface OfflineFixture {

    /**
     * Makes an organization with one host carrying one project, whose admin has filed the host's
     * card.
     *
     * @param owner The organization's owner.
     * @param host The host's name.
     * @param admin The host's admin, and the project's.
     * @param project The project's name.
     * @throws IOException When a change cannot be made.
     */
    void hostWithAdmin(String owner, String host, String admin, String project) throws IOException;

    /**
     * Returns the fingerprint of the key a person holds; anyone may be asked for, a card or not.
     *
     * @param login The person.
     * @return The fingerprint, {@code SHA256:...}.
     */
    String fingerprint(String login);

    /**
     * Returns the copy a host is brought of the organization as it stands now.
     *
     * @return The copy.
     * @throws IOException When it cannot be made.
     */
    OfflineOrganization copy() throws IOException;

    /**
     * The attacker changes what the host's access says, where nobody allowed it.
     *
     * @param signer Whose key signs the change, or {@code null} for no signature.
     * @param host The host's name.
     * @throws IOException When the change cannot be made.
     */
    void hostChangeNobodyAllowed(@Nullable String signer, String host) throws IOException;

    /**
     * A person's allowed change, so that the history has one more state.
     *
     * @param login The person, who files their own first card.
     * @throws IOException When the change cannot be made.
     */
    void fileCard(String login) throws IOException;

    /**
     * What is brought is the state before the last change again.
     *
     * @throws IOException When that cannot be arranged.
     */
    void serveOlderState() throws IOException;

    /**
     * What is brought drops the last change and goes on from before it with another.
     *
     * @throws IOException When that cannot be arranged.
     */
    void rewriteHistory() throws IOException;
}
