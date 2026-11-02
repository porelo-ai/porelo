package org.fuin.porelo.organization.contract;

import java.io.IOException;
import org.fuin.porelo.organization.OrganizationQuestions;
import org.fuin.porelo.organization.OrganizationWrites;

/**
 * What an adapter gives {@link WritesContract}: a store with nothing in it, its changes, and a
 * fresh reader of what they made.
 */
public interface WritesFixture {

    /**
     * Returns the store's changes.
     *
     * @return The changes.
     */
    OrganizationWrites writes();

    /**
     * Reads the store as someone who never read it before.
     *
     * @return What a fresh reader answers.
     * @throws IOException When it cannot be read.
     */
    OrganizationQuestions read() throws IOException;

    /**
     * Returns the key of this name, made the first time it is asked for. The identity source lists
     * it for the login of that name.
     *
     * @param holder The name.
     * @return Its public key, {@code ssh-ed25519 AAAA...}.
     */
    String key(String holder);

    /**
     * Returns a card already signed by a key.
     *
     * @param login The login it is for.
     * @param holder The name of the key it carries and is signed with.
     * @return The card.
     */
    String card(String login, String holder);

    /**
     * Returns a card handing a login on to a new key, signed by the new key and by the old one.
     *
     * @param login The login it is for.
     * @param holder The name of the new key.
     * @param previous The name of the key it hands on from.
     * @return The card.
     */
    String handedOn(String login, String holder, String previous);

    /**
     * Has the identity source list these keys for the login, in place of what it listed.
     *
     * @param login The login.
     * @param holders The names of the keys.
     */
    void sourceLists(String login, String... holders);

    /**
     * Has the identity source say whether someone owns the organization where it is kept.
     *
     * @param login The login.
     * @param owns Whether they do.
     */
    void sourceSaysOwner(String login, boolean owns);
}
