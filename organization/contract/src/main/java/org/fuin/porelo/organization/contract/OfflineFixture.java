package org.fuin.porelo.organization.contract;

import java.io.IOException;
import java.util.List;
import org.fuin.porelo.organization.OfflineOrganization;
import org.jspecify.annotations.Nullable;

/**
 * What an adapter gives so a host's offline check can be held to its pinned key: an organization
 * with one host, the attacker in the adapter's own way, and the copy a host is brought.
 */
public interface OfflineFixture {

    /**
     * Makes an organization with one host carrying one project: an owner makes the host, whose
     * admin then signs the host's card, at the address {@code <host>.example.org}. From that commit
     * on the host's files answer to its pin.
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
     * The host's admin narrows what the host carries of a project to some of its repositories.
     *
     * @param admin The host's admin.
     * @param host The host's name.
     * @param project The project's name, which the host carries.
     * @param repositories The names of the repositories it carries from now on, at least one.
     * @throws IOException When the change cannot be made.
     */
    void carrySome(String admin, String host, String project, List<String> repositories)
            throws IOException;

    /**
     * An owner makes somebody the host's admin too, which the store allows and the pin does not.
     *
     * @param owner The owner.
     * @param host The host's name.
     * @param login The person, who has a card.
     * @throws IOException When the change cannot be made.
     */
    void adminByOwner(String owner, String host, String login) throws IOException;

    /**
     * The host's admin makes another person admin beside them, and that person then changes the
     * host's files: the handover a pin follows.
     *
     * @param admin The host's admin.
     * @param host The host's name.
     * @param login The new admin, who has a card.
     * @throws IOException When a change cannot be made.
     */
    void handOverHost(String admin, String host, String login) throws IOException;

    /**
     * The host's admin lists a person as a member of the project and as the host's admin too, and a
     * card is filed in that person's name, of which the identity source cannot say anything.
     *
     * @param admin The host's admin.
     * @param host The host's name.
     * @param project The project's name.
     * @param login The person.
     * @throws IOException When a change cannot be made.
     */
    void firstSeenPerson(String admin, String host, String project, String login)
            throws IOException;

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
