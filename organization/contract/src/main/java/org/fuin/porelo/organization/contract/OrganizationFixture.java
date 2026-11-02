package org.fuin.porelo.organization.contract;

import java.io.IOException;
import org.fuin.porelo.organization.OrganizationQuestions;
import org.jspecify.annotations.Nullable;

/**
 * What an adapter gives so the checks can run: one new store, each person's allowed changes, and
 * the attacker, in the adapter's own way. A change made here goes behind the store's API, as a
 * person's tool or an attacker with write access to the place it is kept would make it, and counts
 * only once {@link #read} has judged it.
 */
public interface OrganizationFixture {

    /**
     * Reads the organization as it stands, on the one machine this fixture plays, which keeps what
     * it trusted for its next read.
     *
     * @return The answers.
     * @throws IOException When it cannot be read.
     */
    OrganizationQuestions read() throws IOException;

    /**
     * Returns the key a person holds now, {@code ssh-ed25519 AAAA...}.
     *
     * @param login The person.
     * @return The key.
     */
    String key(String login);

    /**
     * Founds the organization, this person its only owner, as the identity source vouches.
     *
     * @param owner The person.
     * @throws IOException When the change cannot be made.
     */
    void found(String owner) throws IOException;

    /**
     * Files a person's own first card, whose key the identity source lists.
     *
     * @param login The person.
     * @throws IOException When the change cannot be made.
     */
    void fileCard(String login) throws IOException;

    /**
     * Files a first card for a person under a key another person's card already holds, which the
     * identity source lists for them too, so that one key holds two places.
     *
     * @param login The person.
     * @param holder The person whose key it is.
     * @throws IOException When the change cannot be made.
     */
    void fileCardWithKeyOf(String login, String holder) throws IOException;

    /**
     * An owner makes a project, whose first admin then lists themselves as its admin.
     *
     * @param owner The owner.
     * @param project The project's name.
     * @param admin Its first admin, who has a card.
     * @throws IOException When a change cannot be made.
     */
    void makeProject(String owner, String project, String admin) throws IOException;

    /**
     * A project's admin adds a member.
     *
     * @param admin The admin.
     * @param project The project's name.
     * @param login The new member, who has a card.
     * @param role {@code admin} or {@code developer}.
     * @throws IOException When the change cannot be made.
     */
    void addMember(String admin, String project, String login, String role) throws IOException;

    /**
     * An owner puts a new card in place of a person's lost key, which revokes that key. The
     * identity source lists both keys, so nothing but the revocation stops the old one.
     *
     * @param owner The owner.
     * @param login The person.
     * @throws IOException When the change cannot be made.
     */
    void replaceLostKey(String owner, String login) throws IOException;

    /**
     * An owner makes another person, who has a card, an owner too.
     *
     * @param owner The owner.
     * @param login The new owner.
     * @throws IOException When the change cannot be made.
     */
    void addOwner(String owner, String login) throws IOException;

    /**
     * An owner names a project's admin, which any owner may, so that a project whose admins lost
     * their keys can be recovered.
     *
     * @param owner The owner.
     * @param project The project's name.
     * @param login The new admin, who has a card.
     * @throws IOException When the change cannot be made.
     */
    void ownerNamesAdmin(String owner, String project, String login) throws IOException;

    /**
     * A project's admin locks a member, who stays listed but may nothing.
     *
     * @param admin The admin.
     * @param project The project's name.
     * @param login The member.
     * @throws IOException When the change cannot be made.
     */
    void lockMember(String admin, String project, String login) throws IOException;

    /**
     * Lists somebody as a project's admin, where the signer may not.
     *
     * @param signer Whose key signs the change, or {@code null} for no signature.
     * @param project The project's name.
     * @param login Who is listed.
     * @return The revision of the change.
     * @throws IOException When the change cannot be made.
     */
    String adminNobodyAllowed(@Nullable String signer, String project, String login)
            throws IOException;

    /**
     * Lists somebody as a project's developer, where the signer may not.
     *
     * @param signer Whose key signs the change, or {@code null} for no signature.
     * @param project The project's name.
     * @param login Who is listed.
     * @return The revision of the change.
     * @throws IOException When the change cannot be made.
     */
    String developerNobodyAllowed(@Nullable String signer, String project, String login)
            throws IOException;

    /**
     * Locks a member, where the signer may not.
     *
     * @param signer Whose key signs the change.
     * @param project The project's name.
     * @param login The member.
     * @return The revision of the change.
     * @throws IOException When the change cannot be made.
     */
    String lockNobodyAllowed(String signer, String project, String login) throws IOException;

    /**
     * Unlocks a member, where the signer may not.
     *
     * @param signer Whose key signs the change.
     * @param project The project's name.
     * @param login The member.
     * @return The revision of the change.
     * @throws IOException When the change cannot be made.
     */
    String unlockNobodyAllowed(String signer, String project, String login) throws IOException;

    /**
     * Makes somebody an owner beside the founder, where the signer may not.
     *
     * @param signer Whose key signs the change, or {@code null} for no signature.
     * @param login Who is made one.
     * @return The revision of the change.
     * @throws IOException When the change cannot be made.
     */
    String ownerNobodyAllowed(@Nullable String signer, String login) throws IOException;

    /**
     * Whoever holds a person's lost key lists somebody as a project's admin with it.
     *
     * @param lostKeyOf The person whose key {@link #replaceLostKey} revoked.
     * @param project The project's name.
     * @param login Who is listed.
     * @return The revision of the change.
     * @throws IOException When the change cannot be made.
     */
    String adminWithLostKey(String lostKeyOf, String project, String login) throws IOException;

    /**
     * Whoever holds an owner's lost key makes somebody an owner with it, the lost key itself still
     * being what the list of owners names.
     *
     * @param lostKeyOf The owner whose key {@link #replaceLostKey} revoked.
     * @param login Who is made one.
     * @return The revision of the change.
     * @throws IOException When the change cannot be made.
     */
    String ownerWithLostKey(String lostKeyOf, String login) throws IOException;

    /**
     * Whoever holds a person's lost key files that person's older card again, signed by it.
     *
     * @param login The person whose key {@link #replaceLostKey} revoked.
     * @return The revision of the change.
     * @throws IOException When the change cannot be made.
     */
    String playBackOlderCard(String login) throws IOException;

    /**
     * The signer overwrites another person's card with one holding the signer's own key.
     *
     * @param signer Whose key signs it and stands on it.
     * @param login Whose card it is.
     * @return The revision of the change.
     * @throws IOException When the change cannot be made.
     */
    String cardNobodyAllowed(String signer, String login) throws IOException;

    /**
     * The signer files a person's first card, under the person's own key, which the identity source
     * lists.
     *
     * @param signer Whose key signs the change.
     * @param login Whose card it is.
     * @return The revision of the change.
     * @throws IOException When the change cannot be made.
     */
    String cardFiledByAnother(String signer, String login) throws IOException;

    /**
     * The signer removes another person's card.
     *
     * @param signer Whose key signs the change; neither the card's nor an owner's.
     * @param login Whose card it is.
     * @return The revision of the change.
     * @throws IOException When the change cannot be made.
     */
    String removeCardNobodyAllowed(String signer, String login) throws IOException;

    /**
     * A person who is no owner does what only an owner may: puts a new card, under a new key signed
     * by itself, in place of another person's.
     *
     * @param signer Whose key signs the change.
     * @param login Whose card it is.
     * @return The revision of the change.
     * @throws IOException When the change cannot be made.
     */
    String replacementNobodyAllowed(String signer, String login) throws IOException;

    /**
     * The signer files a first card in the name of a login with no card yet, under the signer's own
     * key, which the identity source does not list for that login.
     *
     * @param signer Whose key signs it and stands on it.
     * @param login Whose name it is in.
     * @return The revision of the change.
     * @throws IOException When the change cannot be made.
     */
    String squatFirstCard(String signer, String login) throws IOException;

    /**
     * A person files their first card under a key the identity source says is not theirs.
     *
     * @param login The person.
     * @return The revision of the change.
     * @throws IOException When the change cannot be made.
     */
    String fileCardTheSourceDenies(String login) throws IOException;

    /**
     * The place it is kept serves the state before the last change again.
     *
     * @throws IOException When that cannot be arranged.
     */
    void serveOlderState() throws IOException;

    /**
     * The place it is kept drops the last change and goes on from before it with another.
     *
     * @throws IOException When that cannot be arranged.
     */
    void rewriteHistory() throws IOException;

    /**
     * The place it is kept drops the last change, a {@link #replaceLostKey}, and goes on from
     * before it with another, so the revocation is gone from what is served.
     *
     * @throws IOException When that cannot be arranged.
     */
    void discardRevocation() throws IOException;
}
